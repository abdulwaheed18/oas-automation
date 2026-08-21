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
import com.oastest.automation.config.TestingProperties;
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
 * baseline request. That keeps each failure attributable to a single root cause.</p>
 *
 * <p>Cases fall into four expectation classes (see {@link Expect}) whose accepted status codes come
 * from {@link TestingProperties} and are editable per-run from the UI.</p>
 */
@Service
public class TestCaseGeneratorService {

    private static final ObjectMapper JSON = new ObjectMapper();

    // Reusable hostile payloads.
    private static final String SQL_INJECTION = "' OR '1'='1";
    private static final String XSS = "<script>alert(1)</script>";
    private static final String PATH_TRAVERSAL = "../../../../etc/passwd";
    private static final String INVALID_JWT =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ0ZXN0In0.invalid-signature-here";

    private final TestingProperties props;

    public TestCaseGeneratorService(TestingProperties props) {
        this.props = props;
    }

    /** Expectation class for a case; maps to a configurable set of accepted status codes. */
    private enum Expect { SUCCESS, REJECT, AUTH_REJECT, NO_5XX }

    /** A single-field negative value + a label + the expectation class for the mutated request. */
    private static class Violation {
        final String label;
        final Object value;
        final Expect expect;

        Violation(String label, Object value, Expect expect) {
            this.label = label;
            this.value = value;
            this.expect = expect;
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
        TestCase positive = base(method, path, "POSITIVE", "Valid baseline request", null,
                "Fully-valid request; the gateway should forward it to the upstream.",
                secured, params, body, counter);
        setExpect(positive, Expect.SUCCESS);
        sink.add(positive);

        // 2. Auth negatives.
        if (secured) {
            sink.add(authCase(method, path, params, body, counter, "MISSING", null,
                    "Missing Authorization header",
                    "No bearer token is sent; a secured route must reject this."));
            sink.add(authCase(method, path, params, body, counter, "OVERRIDE", "Bearer this.is.not-a-valid-jwt",
                    "Malformed bearer token", "A structurally-invalid bearer token is sent."));
            sink.add(authCase(method, path, params, body, counter, "OVERRIDE", "Bearer ",
                    "Empty bearer token", "Authorization header is present but the token value is empty."));
            sink.add(authCase(method, path, params, body, counter, "OVERRIDE", "Basic dXNlcjpwYXNz",
                    "Wrong auth scheme (Basic)", "Basic auth is sent where a bearer token is required."));
            sink.add(authCase(method, path, params, body, counter, "OVERRIDE", "Bearer " + INVALID_JWT,
                    "Well-formed JWT, bad signature",
                    "A syntactically-valid JWT with an invalid signature is sent."));
            sink.add(authCase(method, path, params, body, counter, "OVERRIDE", "Bearer " + SQL_INJECTION,
                    "Injection in bearer token", "An injection string is sent as the bearer token."));
        }

        // 3. Parameter negatives (headers, query, path) — one bad param per case.
        for (Parameter p : params) {
            String in = p.getIn();
            String name = p.getName();
            Schema<?> schema = p.getSchema();
            boolean required = Boolean.TRUE.equals(p.getRequired()) || "path".equals(in);

            if (required && !"path".equals(in)) {
                TestCase c = base(method, path, in.toUpperCase(),
                        "Missing required " + in + " parameter: " + name, name,
                        "Omit the required " + in + " parameter '" + name + "'.",
                        secured, params, body, counter);
                removeParam(c, p, params, path);
                setExpect(c, Expect.REJECT);
                sink.add(c);
            }

            for (Violation v : paramViolations(schema, in)) {
                TestCase c = base(method, path, in.toUpperCase(),
                        in + " parameter '" + name + "': " + v.label, name,
                        "Set " + in + " parameter '" + name + "' to: " + v.label + ".",
                        secured, params, body, counter);
                overrideParam(c, p, params, path, String.valueOf(v.value));
                setExpect(c, v.expect);
                sink.add(c);
            }
        }

        // 4. Body negatives.
        if (body != null && body.schema != null) {
            if (body.required) {
                TestCase c = base(method, path, "BODY", "Missing required request body", "<body>",
                        "Send no request body although the spec marks it required.",
                        secured, params, body, counter);
                c.body = null;
                c.contentType = null;
                setExpect(c, Expect.REJECT);
                sink.add(c);
            }

            TestCase malformed = base(method, path, "BODY", "Malformed JSON body", "<body>",
                    "Send a syntactically-broken JSON body.", secured, params, body, counter);
            malformed.body = "{ \"broken\": ";
            setExpect(malformed, Expect.REJECT);
            sink.add(malformed);

            TestCase wrongRootArray = base(method, path, "BODY", "Wrong root type (array for object)", "<body>",
                    "Send a JSON array where an object is expected.", secured, params, body, counter);
            wrongRootArray.body = "[]";
            setExpect(wrongRootArray, Expect.REJECT);
            sink.add(wrongRootArray);

            TestCase wrongRootString = base(method, path, "BODY", "Wrong root type (string for object)", "<body>",
                    "Send a bare JSON string where an object is expected.", secured, params, body, counter);
            wrongRootString.body = "\"not-an-object\"";
            setExpect(wrongRootString, Expect.REJECT);
            sink.add(wrongRootString);

            Map<String, Schema> props0 = body.schema.getProperties();
            List<String> requiredProps = body.schema.getRequired();
            if (props0 != null && !props0.isEmpty()) {
                if (requiredProps != null && !requiredProps.isEmpty()) {
                    TestCase empty = base(method, path, "BODY", "Empty JSON object (missing all required fields)",
                            "<body>", "Send {} although required fields are declared.",
                            secured, params, body, counter);
                    empty.body = "{}";
                    setExpect(empty, Expect.REJECT);
                    sink.add(empty);
                }

                for (Map.Entry<String, Schema> e : props0.entrySet()) {
                    String field = e.getKey();
                    Schema<?> fieldSchema = e.getValue();

                    if (requiredProps != null && requiredProps.contains(field)) {
                        TestCase miss = base(method, path, "BODY", "Missing required body field: " + field,
                                field, "Omit the required body field '" + field + "'.",
                                secured, params, body, counter);
                        miss.body = bodyWithout(body.schema, field);
                        setExpect(miss, Expect.REJECT);
                        sink.add(miss);

                        TestCase nul = base(method, path, "BODY", "Null value for required body field: " + field,
                                field, "Send null for the required body field '" + field + "'.",
                                secured, params, body, counter);
                        nul.body = bodyWith(body.schema, field, null);
                        setExpect(nul, Expect.REJECT);
                        sink.add(nul);
                    }

                    for (Violation v : bodyFieldViolations(fieldSchema)) {
                        TestCase c = base(method, path, "BODY", "Body field '" + field + "': " + v.label,
                                field, "Set body field '" + field + "' to: " + v.label + ".",
                                secured, params, body, counter);
                        c.body = bodyWith(body.schema, field, v.value);
                        setExpect(c, v.expect);
                        sink.add(c);
                    }
                }

                // Unexpected extra property.
                boolean additionalDisallowed = Boolean.FALSE.equals(body.schema.getAdditionalProperties())
                        || (body.schema.getAdditionalProperties() instanceof Boolean bb && !bb);
                TestCase extra = base(method, path, "BODY", "Unexpected extra field", "__unexpected_field__",
                        "Add a property not declared in the schema.", secured, params, body, counter);
                extra.body = bodyWith(body.schema, "__unexpected_field__", "surprise");
                setExpect(extra, additionalDisallowed ? Expect.REJECT : Expect.NO_5XX);
                sink.add(extra);
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
        c.requestPath = buildPath(path, validPathValues(params), includedQuery(params, null));
        for (Parameter p : params) {
            if ("header".equals(p.getIn()) && Boolean.TRUE.equals(p.getRequired())) {
                c.headers.put(p.getName(), String.valueOf(SchemaSampler.valid(p.getSchema())));
            }
        }
        if (body != null && body.schema != null) {
            c.body = toJson(SchemaSampler.valid(body.schema));
            c.contentType = body.mediaType;
        }
        return c;
    }

    private TestCase authCase(String method, String path, List<Parameter> params, BodyInfo body, int[] counter,
                             String authMode, String authorization, String name, String description) {
        TestCase c = base(method, path, "AUTH", name, "Authorization", description, true, params, body, counter);
        c.authMode = authMode;
        c.authorization = authorization;
        setExpect(c, Expect.AUTH_REJECT);
        return c;
    }

    private void setExpect(TestCase c, Expect e) {
        switch (e) {
            case SUCCESS -> {
                c.expectedStatuses = props.getSuccessCodes();
                c.expectedStatusFamily = "2xx (accept)";
                c.expectedOutcome = "Gateway accepts the valid request and forwards it upstream.";
            }
            case REJECT -> {
                c.expectedStatuses = props.getRejectCodes();
                c.expectedStatusFamily = "4xx (reject)";
                c.expectedOutcome = "Gateway rejects the invalid request per the OpenAPI contract.";
            }
            case AUTH_REJECT -> {
                c.expectedStatuses = props.getAuthRejectCodes();
                c.expectedStatusFamily = "401/403 (unauthorized)";
                c.expectedOutcome = "Gateway rejects the request as unauthorized.";
            }
            case NO_5XX -> {
                c.expectedStatuses = props.getRobustnessCodes();
                c.expectedStatusFamily = "no 5xx (handled)";
                c.expectedOutcome = "Gateway should handle the hostile input gracefully (no server error).";
            }
        }
    }

    private void removeParam(TestCase c, Parameter target, List<Parameter> all, String path) {
        if ("header".equals(target.getIn())) {
            c.headers.remove(target.getName());
        } else if ("query".equals(target.getIn())) {
            c.requestPath = buildPath(path, validPathValues(all), includedQuery(all, target.getName()));
        }
    }

    private void overrideParam(TestCase c, Parameter target, List<Parameter> all, String path, String badValue) {
        String in = target.getIn();
        if ("header".equals(in)) {
            c.headers.put(target.getName(), badValue);
        } else if ("query".equals(in)) {
            Map<String, String> q = includedQuery(all, null);
            q.put(target.getName(), badValue);
            c.requestPath = buildPath(path, validPathValues(all), q);
        } else if ("path".equals(in)) {
            Map<String, String> pv = validPathValues(all);
            pv.put(target.getName(), badValue);
            c.requestPath = buildPath(path, pv, includedQuery(all, null));
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

    private Map<String, String> includedQuery(List<Parameter> params, String excludeName) {
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

    private String buildPath(String template, Map<String, String> pathValues, Map<String, String> query) {
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

    private List<Violation> paramViolations(Schema<?> schema, String in) {
        List<Violation> out = new ArrayList<>();
        if (schema == null) {
            return out;
        }
        String type = SchemaSampler.type(schema);
        List<?> enums = schema.getEnum();
        if (enums != null && !enums.isEmpty()) {
            out.add(new Violation("value not in enum", "___not_in_enum___", Expect.REJECT));
        }
        if ("integer".equals(type) || "number".equals(type)) {
            out.add(new Violation("non-numeric value", "not-a-number", Expect.REJECT));
            if (schema.getMinimum() != null) {
                out.add(new Violation("below minimum", schema.getMinimum().subtract(BigDecimal.ONE).toString(), Expect.REJECT));
                out.add(new Violation("minimum boundary (valid)", schema.getMinimum().toString(), Expect.SUCCESS));
            }
            if (schema.getMaximum() != null) {
                out.add(new Violation("above maximum", schema.getMaximum().add(BigDecimal.ONE).toString(), Expect.REJECT));
                out.add(new Violation("maximum boundary (valid)", schema.getMaximum().toString(), Expect.SUCCESS));
            } else {
                out.add(new Violation("numeric overflow", "999999999999999999999", Expect.NO_5XX));
            }
        } else if ("boolean".equals(type)) {
            out.add(new Violation("non-boolean value", "notaboolean", Expect.REJECT));
        } else { // string and other on-the-wire values
            if (schema.getPattern() != null) {
                out.add(new Violation("pattern violation w/ special chars", "@@@!!!___###", Expect.REJECT));
            }
            if (schema.getMinLength() != null && schema.getMinLength() > 0) {
                out.add(new Violation("below minLength", "", Expect.REJECT));
            }
            if (schema.getMaxLength() != null) {
                out.add(new Violation("above maxLength", "x".repeat(schema.getMaxLength() + 1), Expect.REJECT));
            }
            String fmt = schema.getFormat();
            if ("uuid".equals(fmt)) {
                out.add(new Violation("invalid uuid format", "not-a-uuid", Expect.REJECT));
            } else if ("email".equals(fmt)) {
                out.add(new Violation("invalid email format", "not-an-email", Expect.REJECT));
            } else if ("date".equals(fmt) || "date-time".equals(fmt)) {
                out.add(new Violation("invalid " + fmt + " format", "13/40/9999", Expect.REJECT));
            }
            // Robustness probes (should be handled without a 5xx).
            out.add(new Violation("SQL injection string", SQL_INJECTION, Expect.NO_5XX));
            out.add(new Violation("XSS string", XSS, Expect.NO_5XX));
            if ("path".equals(in)) {
                out.add(new Violation("path traversal", PATH_TRAVERSAL, Expect.NO_5XX));
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
            out.add(new Violation("value not in enum", "___not_in_enum___", Expect.REJECT));
        }
        if ("integer".equals(type) || "number".equals(type)) {
            out.add(new Violation("wrong type (string for number)", "not-a-number", Expect.REJECT));
            if (schema.getMinimum() != null) {
                out.add(new Violation("below minimum", schema.getMinimum().subtract(BigDecimal.ONE), Expect.REJECT));
                out.add(new Violation("minimum boundary (valid)", schema.getMinimum(), Expect.SUCCESS));
            }
            if (schema.getMaximum() != null) {
                out.add(new Violation("above maximum", schema.getMaximum().add(BigDecimal.ONE), Expect.REJECT));
                out.add(new Violation("maximum boundary (valid)", schema.getMaximum(), Expect.SUCCESS));
            }
        } else if ("boolean".equals(type)) {
            out.add(new Violation("wrong type (string for boolean)", "maybe", Expect.REJECT));
        } else if ("array".equals(type)) {
            out.add(new Violation("wrong type (string for array)", "not-an-array", Expect.REJECT));
        } else if ("object".equals(type)) {
            out.add(new Violation("wrong type (string for object)", "not-an-object", Expect.REJECT));
        } else { // string
            out.add(new Violation("wrong type (number for string)", 1234567, Expect.REJECT));
            if (schema.getPattern() != null) {
                out.add(new Violation("pattern violation w/ special chars", "@@@!!!___###", Expect.REJECT));
            }
            if (schema.getMinLength() != null && schema.getMinLength() > 0) {
                out.add(new Violation("below minLength", "", Expect.REJECT));
            }
            if (schema.getMaxLength() != null) {
                out.add(new Violation("above maxLength", "x".repeat(schema.getMaxLength() + 1), Expect.REJECT));
            }
            String fmt = schema.getFormat();
            if ("uuid".equals(fmt)) {
                out.add(new Violation("invalid uuid format", "not-a-uuid", Expect.REJECT));
            } else if ("email".equals(fmt)) {
                out.add(new Violation("invalid email format", "not-an-email", Expect.REJECT));
            } else if ("date".equals(fmt) || "date-time".equals(fmt)) {
                out.add(new Violation("invalid " + fmt + " format", "13/40/9999", Expect.REJECT));
            }
            out.add(new Violation("SQL injection string", SQL_INJECTION, Expect.NO_5XX));
            out.add(new Violation("XSS string", XSS, Expect.NO_5XX));
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
            ((Map<String, Object>) valid).put(field, value);
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
