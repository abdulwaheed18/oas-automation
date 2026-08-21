package com.oastest.automation.controller;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.oastest.automation.model.EndpointInfo;
import com.oastest.automation.model.ParseResult;
import com.oastest.automation.service.NexusSpecFetcher;
import com.oastest.automation.service.SpecParserService;
import com.oastest.automation.service.SpecSession;
import com.oastest.automation.service.SpecSessionStore;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.core.models.SwaggerParseResult;

/**
 * Screen 1 backend: accepts the OpenAPI spec from one of three sources (uploaded file, pasted
 * text, or a Nexus ZIP URL), parses it and returns the list of endpoints for screen 2.
 */
@RestController
@RequestMapping("/api/spec")
public class SpecController {

    private final SpecParserService parser;
    private final NexusSpecFetcher nexus;
    private final SpecSessionStore store;

    public SpecController(SpecParserService parser, NexusSpecFetcher nexus, SpecSessionStore store) {
        this.parser = parser;
        this.nexus = nexus;
        this.store = store;
    }

    @PostMapping(value = "/parse", consumes = {"multipart/form-data"})
    public ParseResult parse(
            @RequestParam String apiName,
            @RequestParam(required = false) String apiVersion,
            @RequestParam(required = false) String note,
            @RequestParam String sourceType,
            @RequestParam(required = false) MultipartFile file,
            @RequestParam(required = false) String content,
            @RequestParam(required = false) String nexusUrl) {

        String specContent = resolveContent(sourceType, file, content, nexusUrl);
        SwaggerParseResult parsed = parser.parse(specContent);
        OpenAPI openAPI = parsed.getOpenAPI();

        String sessionId = UUID.randomUUID().toString();
        store.put(new SpecSession(sessionId, openAPI, apiName, apiVersion, note));

        List<EndpointInfo> endpoints = parser.listEndpoints(openAPI);

        ParseResult result = new ParseResult();
        result.sessionId = sessionId;
        result.apiName = apiName;
        result.apiVersion = apiVersion;
        result.note = note;
        result.specTitle = openAPI.getInfo() != null ? openAPI.getInfo().getTitle() : null;
        result.specVersion = openAPI.getInfo() != null ? openAPI.getInfo().getVersion() : null;
        result.endpoints = endpoints;
        result.endpointCount = endpoints.size();
        return result;
    }

    private String resolveContent(String sourceType, MultipartFile file, String content, String nexusUrl) {
        String type = sourceType == null ? "" : sourceType.trim().toUpperCase();
        switch (type) {
            case "FILE" -> {
                if (file == null || file.isEmpty()) {
                    throw new IllegalArgumentException("No file was uploaded.");
                }
                try {
                    return new String(file.getBytes(), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    throw new IllegalArgumentException("Could not read the uploaded file: " + e.getMessage(), e);
                }
            }
            case "CLIPBOARD" -> {
                if (content == null || content.isBlank()) {
                    throw new IllegalArgumentException("Pasted spec content is empty.");
                }
                return content;
            }
            case "NEXUS" -> {
                if (nexusUrl == null || nexusUrl.isBlank()) {
                    throw new IllegalArgumentException("Nexus ZIP URL is empty.");
                }
                return nexus.fetchFromZipUrl(nexusUrl.trim()).content;
            }
            default -> throw new IllegalArgumentException("Unknown source type: " + sourceType);
        }
    }
}
