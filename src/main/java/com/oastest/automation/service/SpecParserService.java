package com.oastest.automation.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.oastest.automation.model.EndpointInfo;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;

/**
 * Parses raw OpenAPI content (YAML or JSON) into a model and lists its operations.
 */
@Service
public class SpecParserService {

    public SwaggerParseResult parse(String content) {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(true);
        options.setResolveCombinators(true);
        SwaggerParseResult result = new OpenAPIV3Parser().readContents(content, null, options);
        if (result.getOpenAPI() == null) {
            String messages = (result.getMessages() == null || result.getMessages().isEmpty())
                    ? "unknown parse error"
                    : String.join("; ", result.getMessages());
            throw new IllegalArgumentException("Could not parse OpenAPI spec: " + messages);
        }
        return result;
    }

    public List<EndpointInfo> listEndpoints(OpenAPI openAPI) {
        List<EndpointInfo> endpoints = new ArrayList<>();
        if (openAPI.getPaths() == null) {
            return endpoints;
        }
        boolean globallySecured = openAPI.getSecurity() != null && !openAPI.getSecurity().isEmpty();

        for (Map.Entry<String, PathItem> pathEntry : openAPI.getPaths().entrySet()) {
            String path = pathEntry.getKey();
            PathItem item = pathEntry.getValue();
            for (Map.Entry<PathItem.HttpMethod, Operation> opEntry : item.readOperationsMap().entrySet()) {
                String method = opEntry.getKey().name();
                Operation op = opEntry.getValue();
                boolean secured = resolveSecured(op, globallySecured);
                boolean hasBody = op.getRequestBody() != null;
                String summary = op.getSummary() != null ? op.getSummary()
                        : (op.getDescription() != null ? op.getDescription() : "");
                endpoints.add(new EndpointInfo(method, path, op.getOperationId(), summary, secured, hasBody));
            }
        }
        return endpoints;
    }

    private boolean resolveSecured(Operation op, boolean globallySecured) {
        if (op.getSecurity() != null) {
            // An explicit (possibly empty) security list on the operation overrides the global one.
            return !op.getSecurity().isEmpty();
        }
        return globallySecured;
    }
}
