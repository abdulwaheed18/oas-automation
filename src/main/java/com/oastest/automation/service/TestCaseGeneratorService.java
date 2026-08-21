package com.oastest.automation.service;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oastest.automation.model.TestCase;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;

/**
 * Generates negative / edge-case test cases from an OpenAPI operation.
 *
 * <p>Design rule: every negative case mutates <b>exactly one</b> thing relative to a fully-valid
 * baseline request. That keeps each failure attributable to a single root cause, which is exactly
 * what you need to know whether the gateway enforces one specific constraint.</p>
 */
@Service
public class TestCaseGeneratorService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String MALFORMED_BEARER = "this.is.not-a-valid-jwt";

    /** Small holder for a single-field negative value + a label describing the violation. */
    private static class Violation {
        final String label;
        final Object value;

        Violation(String label, Object value) {
            this.label = label;
            this.value = value;
        }
    }

    public List<TestCase> generate(OpenAPI openAPI, List<String> endpointKeys) {
        List<TestCase> all = new ArrayList<>();
        boolean globallySecured = openAPI.getSecurity() != null && !openAPI.getSecurity().isEmpty();
        int[] counter = {0};

        if (openAPI.getPaths() == null) {
            return all;
        }
        for (Map.Entry<String, PathItem> pathEntry : openAPI.getPaths().entrySet()) {
            String path = pathEntry.getKey();
            PathItem item = pathEntry.getValue();
            for (Map.Entry<PathItem.HttpMethod, Operation> opEntry : item.readOperationsMap().entrySet()) {
                String method = opEntry.getKey().name();
                String key = method + " " + path;
                if (endpointKeys != null && !endpointKeys.contains(key)) {
                    continue;
                }
                generateForOperation(method, path, item, opEntry.getValue(), globallySecured, all, counter);
            }
        }
        return all;
    }

    private void generateForOperation(String method, String path, PathItem pathItem, Operation op,
                                      boolean globallySecured, List<TestCase> sink, int[] counter) {
        boolean secured = op.getSecurity() != null ? !op.getSecurity().isEmpty() : globallySecured;
        List<Parameter> params = mergeParameters(pathItem, op);
        BodyInfo body = resolveBody(op);

        // 1. Positive baseline — everything valid.
        TestCase positive = base(method, path, "POSITIVE",
                "Valid baseline request", null,
                "Fully-valid request; the gateway should forward it to the upstream.",
                secured, params, body, counter);
        positive.expectedStatusFamily = "2xx/3xx (accept)";
        positive.expectedMin = 200;
        positive.expectedMax = 399;
        positive.expectedOutcome = "Gateway accepts the valid request and forwards upstream.";
        sink.add(positive);

        // 2. Auth negatives.
        if (secured) {
            sink.add(authCase(method, path, params, body, "MISSING",
                    "Missing Authorization header",
                    "No bearer token is sent. A secured route must reject this.", counter));
            sink.add(authCase(method, path, params, body, "MALFORMED",
                    "Malformed bearer token",
                    "A structurally-invalid bearer token is sent (" + MALFORMED_BEARER + ").", counter));
            sink.add(authCase(method, path, params, body, "EMPTY",
                    "Empty bearer token",
                    "Authorization header is present but the token value is empty.", counter));
        }

        // 3. Parameter negatives (headers, query, path) — one bad param per case.
        for (Parameter p : params) {
            String in = p.getIn();
            String name = p.getName();
            boolean required = Boolean.TRUE.equals(p.getRequired()) || "path".equals(in);
            Schema<?> schema = p.getSchema();

            if (required && !"path".equals(in)) {
                TestCase c = base(method, path, in.toUpperCase(),
                        "Missing required " + in + " parameter: " + name,
                        name,
                        "Omit the required " + in + " parameter '" + name + "'; the gateway should reject it.",
                        secured, params, body, counter);
                removeParam(c, p, params, method, path, secured, body);
                markReject(c);
                sink.add(c);
            }

            for (Violation v : paramViolations(schema)) {
                TestCase c = base(method, path, in.toUpperCase(),
                        in + " parameter '" + name + "': " + v.label,
                        name,
                        "Set " + in + " parameter '" + name + "' to an invalid value (" + v.label
                                + "); the gateway should reject it.",
                        secured, params, body, counter);
                overrideParam(c, p, params, method, path, secured, body, String.valueOf(v.value));
                markReject(c);
                sink.add(c);
            }
        }

        // 4. Body negatives.
        if (body != null && body.schema != null) {
            if (body.required) {
                TestCase c = base(method, path, "BODY",
                        "Missing required request body", "<body>",
                        "Send no request body although the spec marks it required.",
                        secured, params, body, counter);
                c.body = null;
                c.contentType = null;
                markReject(c);
                sink.add(c);
            }

            TestCase malformed = base(method, path, "BODY",
                    "Malformed JSON body", "<body>",
                    "Send a syntactically-broken JSON body; the gateway should reject it.",
                    secured, params, body, counter);
            malformed.body = "{ \"broken\": ";
            malformed.contentType = "application/json";
            markReject(malformed);
            sink.add(malformed);

            Map<String, Schema> props = body.schema.getProperties();
            List<String> requiredProps = body.schema.getRequired();
            if (props != null) {
                for (Map.Entry<String, Schema> e : props.entrySet()) {
                    String field = e.getKey();
                    Schema<?> fieldSchema = e.getValue();

                    if (requiredProps != null && requiredProps.contains(field)) {
                        TestCase c = base(method, path, "BODY",
                                "Missing required body field: " + field, field,
                                "Omit the required body field '" + field + "'.",
                                secured, params, body, counter);
                        c.body = bodyWithout(body.schema, field);
                        markReject(c);
                        sink.add(c);

                        TestCase nullCase = base(method, path, "BODY",
                                "Null value for required body field: " + field, field,
                                "Send null for the required body field '" + field + "'.",
                                secured, params, body, counter);
                        nullCase.body = bodyWith(body.schema, field, null);
                        markReject(nullCase);
                        sink.add(nullCase);
                    }

                    for (Violation v : bodyFieldViolations(fieldSchema)) {
                        TestCase c = base(method, path, "BODY",
                                "Body field '" + field + "': " + v.label, field,
                                "Set body field '" + field + "' to an invalid value (" + v.label + ").",
                                secured, params, body, counter);
                        c.body = bodyWith(body.schema, field, v.value);
                        markReject(c);
                        sink.add(c);
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Baseline request construction
    // ---------------------------------------------------------------------

    private TestCase base(String method, String path, String category, String name, String negativeField,
                          String description, boolean secured, List<Parameter> params, BodyInfo body,
                          int[] counter) {
        TestCase c = new TestCase();
        c.id = "TC-" + (++counter[0]);
        c.method = method;
        c.endpointPath = path;
        c.category = category;
        c.name = name;
        c.negativeField = negativeField;
        c.description = description;
        c.authMode = secured ? "VALID" : "NONE";
        c.requestPath = buildPath(path, params, validPathValues(params), includedQuery(params, null, null));
        for (Parameter p : params) {
            if ("header".equals(p.getIn()) && isIncludedHeader(p)) {
                c.headers.put(p.getName(), String.valueOf(SchemaSampler.valid(p.getSchema())));
            }
        }
        if (body != null && body.schema != null) {
            c.body = toJson(SchemaSampler.valid(body.schema));
            c.contentType = body.mediaType;
        }
        return c;
    }

    /** True for required headers, or optional ones we still include to keep the baseline realistic. */
    private boolean isIncludedHeader(Parameter p) {
        return Boolean.TRUE.equals(p.getRequired());
    }

    private TestCase authCase(String method, String path, List<Parameter> params, BodyInfo body,
                              String authMode, String name, String description, int[] counter) {
        TestCase c = base(method, path, "AUTH", name, "Authorization", description,
                true, params, body, counter);
        c.authMode = authMode;
        c.expectedStatusFamily = "401/403 (unauthorized)";
        c.expectedMin = 400;
        c.expectedMax = 403;
        c.expectedOutcome = "Gateway rejects the request as unauthorized.";
        return c;
    }

    private void markReject(TestCase c) {
        c.expectedStatusFamily = "4xx (reject)";
        c.expectedMin = 400;
        c.expectedMax = 499;
        c.expectedOutcome = "Gateway rejects the invalid request per the OpenAPI contract.";
    }

    private void removeParam(TestCase c, Parameter target, List<Parameter> all, String method, String path,
                             boolean secured, BodyInfo body) {
        if ("header".equals(target.getIn())) {
            c.headers.remove(target.getName());
        } else if ("query".equals(target.getIn())) {
            Map<String, String> q = includedQuery(all, target.getName(), null);
            c.requestPath = buildPath(path, all, validPathValues(all), q);
        }
    }

    private void overrideParam(TestCase c, Parameter target, List<Parameter> all, String method, String path,
                               boolean secured, BodyInfo body, String badValue) {
        String in = target.getIn();
        if ("header".equals(in)) {
            c.headers.put(target.getName(), badValue);
        } else if ("query".equals(in)) {
            Map<String, String> q = includedQuery(all, null, null);
            q.put(target.getName(), badValue);
            c.requestPath = buildPath(path, all, validPathValues(all), q);
        } else if ("path".equals(in)) {
            Map<String, String> pv = validPathValues(all);
            pv.put(target.getName(), badValue);
            c.requestPath = buildPath(path, all, pv, includedQuery(all, null, null));
        }
    }

    // ---------------------------------------------------------------------
    // Path / query assembly
    // ---------------------------------------------------------------------

    private Map<String, String> validPathValues(List<Parameter> params) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Parameter p : params) {
            if ("path".equals(p.getIn())) {
                values.put(p.getName(), String.valueOf(SchemaSampler.valid(p.getSchema())));
            }
        }
        return values;
    }

    /** Required query params with valid values, optionally excluding one (for a "missing" case). */
    private Map<String, String> includedQuery(List<Parameter> params, String excludeName, Void unused) {
        Map<String, String> q = new LinkedHashMap<>();
        for (Parameter p : params) {
            if ("query".equals(p.getIn()) && Boolean.TRUE.equals(p.getRequired())) {
                if (excludeName != null && excludeName.equals(p.getName())) {
                    continue;
                }
                q.put(p.getName(), String.valueOf(SchemaSampler.valid(p.getSchema())));
            }
        }
        return q;
    }

    private String buildPath(String template, List<Parameter> params, Map<String, String> pathValues,
                             Map<String, String> query) {
        String resolved = template;
        for (Map.Entry<String, String> e : pathValues.entrySet()) {
            resolved = resolved.replace("{" + e.getKey() + "}", encodePathSegment(e.getValue()));
        }
        if (query != null && !query.isEmpty()) {
            StringBuilder sb = new StringBuilder(resolved).append('?');
            boolean first = true;
            for (Map.Entry<String, String> e : query.entrySet()) {
                if (!first) {
                    sb.append('&');
                }
                sb.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
                first = false;
            }
            resolved = sb.toString();
        }
        return resolved;
    }

    private String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private String encodePathSegment(String s) {
        return enc(s).replace("+", "%20");
    }

    // ---------------------------------------------------------------------
    // Violation catalogues
    // ---------------------------------------------------------------------

    private List<Violation> paramViolations(Schema<?> schema) {
        List<Violation> out = new ArrayList<>();
        if (schema == null) {
            return out;
        }
        String type = SchemaSampler.type(schema);
        List<?> enums = schema.getEnum();
        if (enums != null && !enums.isEmpty()) {
            out.add(new Violation("value not in enum", "___not_in_enum___"));
        }
        if ("integer".equals(type) || "number".equals(type)) {
            out.add(new Violation("non-numeric value", "not-a-number"));
            if (schema.getMinimum() != null) {
                out.add(new Violation("below minimum", schema.getMinimum().subtract(BigDecimal.ONE).toString()));
            }
            if (schema.getMaximum() != null) {
                out.add(new Violation("above maximum", schema.getMaximum().add(BigDecimal.ONE).toString()));
            }
        } else if ("boolean".equals(type)) {
            out.add(new Violation("non-boolean value", "notaboolean"));
        } else { // string and everything else on the wire
            if (schema.getPattern() != null) {
                out.add(new Violation("pattern violation w/ special chars", "@@@!!!___###"));
            }
            if (schema.getMinLength() != null && schema.getMinLength() > 0) {
                out.add(new Violation("below minLength", ""));
            }
            if (schema.getMaxLength() != null) {
                out.add(new Violation("above maxLength", "x".repeat(schema.getMaxLength() + 1)));
            }
            String fmt = schema.getFormat();
            if ("uuid".equals(fmt)) {
                out.add(new Violation("invalid uuid format", "not-a-uuid"));
            } else if ("email".equals(fmt)) {
                out.add(new Violation("invalid email format", "not-an-email"));
            } else if ("date".equals(fmt) || "date-time".equals(fmt)) {
                out.add(new Violation("invalid " + fmt + " format", "13/40/9999"));
            }
        }
        return out;
    }

    @SuppressWarnings("rawtypes")
    private List<Violation> bodyFieldViolations(Schema schema) {
        List<Violation> out = new ArrayList<>();
        if (schema == null) {
            return out;
        }
        String type = SchemaSampler.type(schema);
        List<?> enums = schema.getEnum();
        if (enums != null && !enums.isEmpty()) {
            out.add(new Violation("value not in enum", "___not_in_enum___"));
        }
        if ("integer".equals(type) || "number".equals(type)) {
            out.add(new Violation("wrong type (string for number)", "not-a-number"));
            if (schema.getMinimum() != null) {
                out.add(new Violation("below minimum", schema.getMinimum().subtract(BigDecimal.ONE)));
            }
            if (schema.getMaximum() != null) {
                out.add(new Violation("above maximum", schema.getMaximum().add(BigDecimal.ONE)));
            }
        } else if ("boolean".equals(type)) {
            out.add(new Violation("wrong type (string for boolean)", "maybe"));
        } else if ("array".equals(type)) {
            out.add(new Violation("wrong type (string for array)", "not-an-array"));
        } else if ("object".equals(type)) {
            out.add(new Violation("wrong type (string for object)", "not-an-object"));
        } else { // string
            out.add(new Violation("wrong type (number for string)", 1234567));
            if (schema.getPattern() != null) {
                out.add(new Violation("pattern violation w/ special chars", "@@@!!!___###"));
            }
            if (schema.getMinLength() != null && schema.getMinLength() > 0) {
                out.add(new Violation("below minLength", ""));
            }
            if (schema.getMaxLength() != null) {
                out.add(new Violation("above maxLength", "x".repeat(schema.getMaxLength() + 1)));
            }
            String fmt = schema.getFormat();
            if ("uuid".equals(fmt)) {
                out.add(new Violation("invalid uuid format", "not-a-uuid"));
            } else if ("email".equals(fmt)) {
                out.add(new Violation("invalid email format", "not-an-email"));
            } else if ("date".equals(fmt) || "date-time".equals(fmt)) {
                out.add(new Violation("invalid " + fmt + " format", "13/40/9999"));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // Body helpers
    // ---------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private String bodyWith(Schema<?> schema, String field, Object value) {
        Object valid = SchemaSampler.valid(schema);
        if (valid instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) valid;
            map.put(field, value);
        }
        return toJson(valid);
    }

    @SuppressWarnings("unchecked")
    private String bodyWithout(Schema<?> schema, String field) {
        Object valid = SchemaSampler.valid(schema);
        if (valid instanceof Map) {
            ((Map<String, Object>) valid).remove(field);
        }
        return toJson(valid);
    }

    private String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    // ---------------------------------------------------------------------
    // Spec traversal helpers
    // ---------------------------------------------------------------------

    private List<Parameter> mergeParameters(PathItem pathItem, Operation op) {
        Map<String, Parameter> merged = new LinkedHashMap<>();
        if (pathItem.getParameters() != null) {
            for (Parameter p : pathItem.getParameters()) {
                merged.put(p.getIn() + ":" + p.getName(), p);
            }
        }
        if (op.getParameters() != null) {
            for (Parameter p : op.getParameters()) {
                merged.put(p.getIn() + ":" + p.getName(), p);
            }
        }
        return new ArrayList<>(merged.values());
    }

    private static class BodyInfo {
        Schema<?> schema;
        String mediaType;
        boolean required;
    }

    private BodyInfo resolveBody(Operation op) {
        RequestBody rb = op.getRequestBody();
        if (rb == null || rb.getContent() == null || rb.getContent().isEmpty()) {
            return null;
        }
        BodyInfo info = new BodyInfo();
        info.required = Boolean.TRUE.equals(rb.getRequired());
        var content = rb.getContent();
        var json = content.get("application/json");
        if (json != null) {
            info.schema = json.getSchema();
            info.mediaType = "application/json";
        } else {
            var first = content.entrySet().iterator().next();
            info.schema = first.getValue().getSchema();
            info.mediaType = first.getKey();
        }
        return info;
    }
}
