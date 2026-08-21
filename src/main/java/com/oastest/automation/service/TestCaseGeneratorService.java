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
 * baseline request, so each failure is attributable to a single root cause. Coverage includes
 * auth, headers, query params (incl. arrays), path params, and request bodies with
 * <b>nested</b> object/array fields and {@code allOf/oneOf/anyOf} composition.</p>
 */
@Service
public class TestCaseGeneratorService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_BODY_DEPTH = 4;

    private static final String SQL_INJECTION = "' OR '1'='1";
    private static final String XSS = "<script>alert(1)</script>";
    private static final String PATH_TRAVERSAL = "../../../../etc/passwd";
    private static final String INVALID_JWT =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ0ZXN0In0.invalid-signature-here";

    private final TestingProperties props;

    public TestCaseGeneratorService(TestingProperties props) {
        this.props = props;
    }

    private enum Expect { SUCCESS, REJECT, AUTH_REJECT, NO_5XX }

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

    private Schema<?> resolve(Schema<?> s) {
        return SchemaComposer.resolve(s);
    }

    private boolean isAuthHeader(Parameter p) {
        return "header".equals(p.getIn()) && p.getName() != null
                && "authorization".equalsIgnoreCase(p.getName().trim());
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
                if (endpointKeys == null || endpointKeys.contains(method + " " + path)) {
                    generateForOperation(method, path, item, opEntry.getValue(), globallySecured, all, counter);
                }
            }
        }
        return all;
    }

    private void generateForOperation(String method, String path, PathItem pathItem, Operation op,
                                      boolean globallySecured, List<TestCase> sink, int[] counter) {
        List<Parameter> params = mergeParameters(pathItem, op);
        BodyInfo body = resolveBody(op);
        boolean schemeSecured = op.getSecurity() != null ? !op.getSecurity().isEmpty() : globallySecured;
        boolean secured = schemeSecured || params.stream().anyMatch(this::isAuthHeader);

        // 1. Positive baseline.
        TestCase positive = base(method, path, "POSITIVE", "Valid baseline request", null,
                "Fully-valid request; the gateway should forward it to the upstream.",
                secured, params, body, counter);
        setExpect(positive, Expect.SUCCESS);
        sink.add(positive);

        // 2. Auth negatives.
        if (secured) {
            sink.add(authCase(method, path, params, body, counter, "MISSING", null,
                    "Missing Authorization header", "No bearer token is sent; a secured route must reject this."));
            sink.add(authCase(method, path, params, body, counter, "OVERRIDE", "Bearer this.is.not-a-valid-jwt",
                    "Malformed bearer token", "A structurally-invalid bearer token is sent."));
            sink.add(authCase(method, path, params, body, counter, "OVERRIDE", "Bearer ",
                    "Empty bearer token", "Authorization header present but the token value is empty."));
            sink.add(authCase(method, path, params, body, counter, "OVERRIDE", "Basic dXNlcjpwYXNz",
                    "Wrong auth scheme (Basic)", "Basic auth sent where a bearer token is required."));
            sink.add(authCase(method, path, params, body, counter, "OVERRIDE", "Bearer " + INVALID_JWT,
                    "Well-formed JWT, bad signature", "A syntactically-valid JWT with an invalid signature."));
            sink.add(authCase(method, path, params, body, counter, "OVERRIDE", "Bearer " + SQL_INJECTION,
                    "Injection in bearer token", "An injection string is sent as the bearer token."));
        }

        // 3. Parameter negatives.
        for (Parameter p : params) {
            if (isAuthHeader(p)) {
                continue;
            }
            String in = p.getIn();
            String name = p.getName();
            Schema<?> schema = resolve(p.getSchema());
            boolean required = Boolean.TRUE.equals(p.getRequired()) || "path".equals(in);

            if (required && !"path".equals(in)) {
                TestCase c = base(method, path, in.toUpperCase(),
                        "Missing required " + in + " parameter: " + name, name,
                        "Omit the required " + in + " parameter '" + name + "'.", secured, params, body, counter);
                removeParam(c, p, params, path);
                setExpect(c, Expect.REJECT);
                sink.add(c);
            }

            if ("query".equals(in) && "array".equals(SchemaSampler.type(schema))) {
                for (Violation v : queryArrayViolations(schema)) {
                    @SuppressWarnings("unchecked")
                    List<String> values = (List<String>) v.value;
                    TestCase c = base(method, path, "QUERY", "query array '" + name + "': " + v.label, name,
                            "Set query array '" + name + "' to: " + v.label + ".", secured, params, body, counter);
                    overrideArrayQuery(c, p, params, path, values);
                    setExpect(c, v.expect);
                    sink.add(c);
                }
            } else {
                for (Violation v : paramViolations(schema, in)) {
                    TestCase c = base(method, path, in.toUpperCase(),
                            in + " parameter '" + name + "': " + v.label, name,
                            "Set " + in + " parameter '" + name + "' to: " + v.label + ".", secured, params, body, counter);
                    overrideScalarParam(c, p, params, path, String.valueOf(v.value));
                    setExpect(c, v.expect);
                    sink.add(c);
                }
            }
        }

        // 4. Body negatives.
        if (body != null && body.schema != null) {
            generateBodyCases(method, path, secured, params, body, sink, counter);
        }
    }

    // ---------------------------------------------------------------------
    // Body cases (structural + nested fields)
    // ---------------------------------------------------------------------

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void generateBodyCases(String method, String path, boolean secured, List<Parameter> params,
                                   BodyInfo body, List<TestCase> sink, int[] counter) {
        Schema<?> root = resolve(body.schema);

        if (body.required) {
            TestCase c = base(method, path, "BODY", "Missing required request body", "<body>",
                    "Send no request body although the spec marks it required.", secured, params, body, counter);
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

        if ("application/json".equals(body.mediaType)) {
            TestCase wrongCt = base(method, path, "BODY", "Unsupported Content-Type (text/plain)", "<body>",
                    "Send a valid JSON body but declare Content-Type: text/plain.", secured, params, body, counter);
            wrongCt.contentType = "text/plain";
            setExpect(wrongCt, Expect.REJECT);
            wrongCt.expectedStatuses = "415,422"; // Tyk may answer either
            sink.add(wrongCt);
        }

        TestCase wrongRootArray = base(method, path, "BODY", "Wrong root type (array for object)", "<body>",
                "Send a JSON array where an object is expected.", secured, params, body, counter);
        wrongRootArray.body = "[]";
        setExpect(wrongRootArray, Expect.REJECT);
        sink.add(wrongRootArray);

        Map<String, Schema> props = root.getProperties();
        List<String> required = root.getRequired();
        if (props != null && !props.isEmpty()) {
            if (required != null && !required.isEmpty()) {
                TestCase empty = base(method, path, "BODY", "Empty JSON object (missing all required fields)",
                        "<body>", "Send {} although required fields are declared.", secured, params, body, counter);
                empty.body = "{}";
                setExpect(empty, Expect.REJECT);
                sink.add(empty);
            }
            TestCase extra = base(method, path, "BODY", "Unexpected extra field", "__unexpected_field__",
                    "Add a property not declared in the schema.", secured, params, body, counter);
            boolean additionalDisallowed = Boolean.FALSE.equals(root.getAdditionalProperties());
            extra.body = bodyAtPath(root, List.of("__unexpected_field__"), "surprise", false);
            setExpect(extra, additionalDisallowed ? Expect.REJECT : Expect.NO_5XX);
            sink.add(extra);

            // Nested field mutations (top level + any depth).
            addFieldMutations(method, path, secured, params, body, root, new ArrayList<>(), root, sink, counter, 0);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void addFieldMutations(String method, String path, boolean secured, List<Parameter> params,
                                   BodyInfo body, Schema<?> root, List<String> tokens, Schema<?> node,
                                   List<TestCase> sink, int[] counter, int depth) {
        node = resolve(node);
        if (node == null || depth > MAX_BODY_DEPTH || !"object".equals(SchemaSampler.type(node))
                || node.getProperties() == null) {
            return;
        }
        List<String> required = node.getRequired() != null ? node.getRequired() : List.of();
        for (Map.Entry<String, Schema> e : ((Map<String, Schema>) node.getProperties()).entrySet()) {
            String prop = e.getKey();
            Schema<?> ps = resolve(e.getValue());
            List<String> child = new ArrayList<>(tokens);
            child.add(prop);
            String lbl = pathLabel(child);

            if (required.contains(prop)) {
                TestCase miss = base(method, path, "BODY", "Missing required body field: " + lbl, lbl,
                        "Omit the required body field '" + lbl + "'.", secured, params, body, counter);
                miss.body = bodyAtPath(root, child, null, true);
                setExpect(miss, Expect.REJECT);
                sink.add(miss);

                TestCase nul = base(method, path, "BODY", "Null value for required body field: " + lbl, lbl,
                        "Send null for the required body field '" + lbl + "'.", secured, params, body, counter);
                nul.body = bodyAtPath(root, child, null, false);
                setExpect(nul, Expect.REJECT);
                sink.add(nul);
            }

            for (Violation v : bodyFieldViolations(ps)) {
                TestCase c = base(method, path, "BODY", "Body field '" + lbl + "': " + v.label, lbl,
                        "Set body field '" + lbl + "' to: " + v.label + ".", secured, params, body, counter);
                c.body = bodyAtPath(root, child, v.value, false);
                setExpect(c, v.expect);
                sink.add(c);
            }

            String pt = SchemaSampler.type(ps);
            if ("object".equals(pt)) {
                addFieldMutations(method, path, secured, params, body, root, child, ps, sink, counter, depth + 1);
            } else if ("array".equals(pt) && ps.getItems() != null
                    && "object".equals(SchemaSampler.type(resolve(ps.getItems())))) {
                List<String> arrTokens = new ArrayList<>(child);
                arrTokens.add("[0]");
                addFieldMutations(method, path, secured, params, body, root, arrTokens,
                        resolve(ps.getItems()), sink, counter, depth + 1);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Baseline request construction
    // ---------------------------------------------------------------------

    private TestCase base(String method, String path, String category, String name, String negativeField,
                          String description, boolean secured, List<Parameter> params, BodyInfo body, int[] counter) {
        TestCase c = new TestCase();
        c.id = "TC-" + (++counter[0]);
        c.method = method;
        c.endpointPath = path;
        c.category = category;
        c.name = name;
        c.negativeField = negativeField;
        c.description = description;
        c.authMode = secured ? "VALID" : "NONE";
        c.requestPath = buildPath(path, validPathValues(params), baselineQuery(params, null));
        for (Parameter p : params) {
            if ("header".equals(p.getIn()) && Boolean.TRUE.equals(p.getRequired()) && !isAuthHeader(p)) {
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
                c.expectedOutcome = "Gateway rejects the invalid request per the OpenAPI contract (Tyk: 422).";
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
            c.requestPath = buildPath(path, validPathValues(all), baselineQuery(all, target.getName()));
        }
    }

    private void overrideScalarParam(TestCase c, Parameter target, List<Parameter> all, String path, String badValue) {
        String in = target.getIn();
        if ("header".equals(in)) {
            c.headers.put(target.getName(), badValue);
        } else if ("query".equals(in)) {
            List<String[]> q = baselineQuery(all, target.getName());
            q.add(new String[]{target.getName(), badValue});
            c.requestPath = buildPath(path, validPathValues(all), q);
        } else if ("path".equals(in)) {
            Map<String, String> pv = validPathValues(all);
            pv.put(target.getName(), badValue);
            c.requestPath = buildPath(path, pv, baselineQuery(all, null));
        }
    }

    private void overrideArrayQuery(TestCase c, Parameter target, List<Parameter> all, String path, List<String> values) {
        List<String[]> q = baselineQuery(all, target.getName());
        if (explodeOf(target)) {
            for (String v : values) {
                q.add(new String[]{target.getName(), v});
            }
        } else if (!values.isEmpty()) {
            q.add(new String[]{target.getName(), String.join(",", values)});
        }
        c.requestPath = buildPath(path, validPathValues(all), q);
    }

    // ---------------------------------------------------------------------
    // Path / query assembly (query supports arrays via repeated pairs)
    // ---------------------------------------------------------------------

    private boolean explodeOf(Parameter p) {
        return p.getExplode() == null || Boolean.TRUE.equals(p.getExplode());
    }

    private Map<String, String> validPathValues(List<Parameter> params) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Parameter p : params) {
            if ("path".equals(p.getIn())) {
                values.put(p.getName(), String.valueOf(SchemaSampler.valid(p.getSchema())));
            }
        }
        return values;
    }

    private List<String[]> baselineQuery(List<Parameter> params, String excludeName) {
        List<String[]> q = new ArrayList<>();
        for (Parameter p : params) {
            if (!"query".equals(p.getIn()) || !Boolean.TRUE.equals(p.getRequired())) {
                continue;
            }
            if (excludeName != null && excludeName.equals(p.getName())) {
                continue;
            }
            Schema<?> s = resolve(p.getSchema());
            if ("array".equals(SchemaSampler.type(s))) {
                List<String> items = validQueryItems(s);
                if (explodeOf(p)) {
                    for (String v : items) {
                        q.add(new String[]{p.getName(), v});
                    }
                } else {
                    q.add(new String[]{p.getName(), String.join(",", items)});
                }
            } else {
                q.add(new String[]{p.getName(), String.valueOf(SchemaSampler.valid(s))});
            }
        }
        return q;
    }

    private List<String> validQueryItems(Schema<?> arr) {
        Schema<?> items = resolve(arr.getItems());
        int min = arr.getMinItems() != null && arr.getMinItems() > 0 ? arr.getMinItems() : 1;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < min; i++) {
            out.add(String.valueOf(SchemaSampler.valid(items)));
        }
        return out;
    }

    private String buildPath(String template, Map<String, String> pathValues, List<String[]> query) {
        String resolved = template;
        for (Map.Entry<String, String> e : pathValues.entrySet()) {
            resolved = resolved.replace("{" + e.getKey() + "}", encodePathSegment(e.getValue()));
        }
        if (query != null && !query.isEmpty()) {
            StringBuilder sb = new StringBuilder(resolved).append('?');
            for (int i = 0; i < query.size(); i++) {
                if (i > 0) {
                    sb.append('&');
                }
                sb.append(enc(query.get(i)[0])).append('=').append(enc(query.get(i)[1]));
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
            addEnumCaseVariant(out, enums);
        }
        if ("integer".equals(type) || "number".equals(type)) {
            out.add(new Violation("non-numeric value", "not-a-number", Expect.REJECT));
            if ("integer".equals(type)) {
                out.add(new Violation("decimal for integer", "1.5", Expect.REJECT));
            }
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
        } else {
            addPatternViolation(out, schema.getPattern());
            if (schema.getMinLength() != null && schema.getMinLength() > 0) {
                out.add(new Violation("below minLength", "", Expect.REJECT));
            }
            if (schema.getMaxLength() != null) {
                out.add(new Violation("above maxLength", "x".repeat(schema.getMaxLength() + 1), Expect.REJECT));
            }
            addFormatViolation(out, schema.getFormat());
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
            addEnumCaseVariant(out, enums);
        }
        if ("integer".equals(type) || "number".equals(type)) {
            out.add(new Violation("wrong type (string for number)", "not-a-number", Expect.REJECT));
            if ("integer".equals(type)) {
                out.add(new Violation("decimal for integer", new BigDecimal("1.5"), Expect.REJECT));
            }
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
            out.add(new Violation("wrong type (number for boolean)", 1, Expect.REJECT));
        } else if ("array".equals(type)) {
            out.add(new Violation("wrong type (string for array)", "not-an-array", Expect.REJECT));
            addArrayViolations(out, schema);
        } else if ("object".equals(type)) {
            out.add(new Violation("wrong type (string for object)", "not-an-object", Expect.REJECT));
        } else {
            out.add(new Violation("wrong type (number for string)", 1234567, Expect.REJECT));
            addPatternViolation(out, schema.getPattern());
            if (schema.getMinLength() != null && schema.getMinLength() > 0) {
                out.add(new Violation("below minLength", "", Expect.REJECT));
            }
            if (schema.getMaxLength() != null) {
                out.add(new Violation("above maxLength", "x".repeat(schema.getMaxLength() + 1), Expect.REJECT));
            }
            addFormatViolation(out, schema.getFormat());
            out.add(new Violation("SQL injection string", SQL_INJECTION, Expect.NO_5XX));
            out.add(new Violation("XSS string", XSS, Expect.NO_5XX));
        }
        return out;
    }

    private void addFormatViolation(List<Violation> out, String fmt) {
        if ("uuid".equals(fmt)) {
            out.add(new Violation("invalid uuid format", "not-a-uuid", Expect.REJECT));
        } else if ("email".equals(fmt)) {
            out.add(new Violation("invalid email format", "not-an-email", Expect.REJECT));
        } else if ("date".equals(fmt) || "date-time".equals(fmt)) {
            out.add(new Violation("invalid " + fmt + " format", "13/40/9999", Expect.REJECT));
        }
    }

    private void addPatternViolation(List<Violation> out, String pattern) {
        if (pattern == null) {
            return;
        }
        for (String candidate : new String[]{"@@@!!!___###", "", "   ", "___lower___", "1"}) {
            if (!RegexSampler.matchesFully(pattern, candidate)) {
                out.add(new Violation("pattern violation (" + describe(candidate) + ")", candidate, Expect.REJECT));
                return;
            }
        }
    }

    private void addEnumCaseVariant(List<Violation> out, List<?> enums) {
        Object first = enums.get(0);
        if (!(first instanceof String s) || s.isEmpty()) {
            return;
        }
        String flipped = s.equals(s.toLowerCase()) ? s.toUpperCase() : s.toLowerCase();
        if (flipped.equals(s)) {
            return;
        }
        for (Object e : enums) {
            if (flipped.equals(e)) {
                return;
            }
        }
        out.add(new Violation("enum value with wrong case (" + flipped + ")", flipped, Expect.REJECT));
    }

    @SuppressWarnings("rawtypes")
    private void addArrayViolations(List<Violation> out, Schema schema) {
        Schema items = (Schema) resolve(schema.getItems());
        Object itemSample = items != null ? SchemaSampler.valid(items) : "x";
        if (schema.getMinItems() != null && schema.getMinItems() > 0) {
            out.add(new Violation("empty array (below minItems)", new ArrayList<>(), Expect.REJECT));
        }
        if (schema.getMaxItems() != null) {
            List<Object> tooMany = new ArrayList<>();
            for (int k = 0; k < schema.getMaxItems() + 1; k++) {
                tooMany.add(itemSample);
            }
            out.add(new Violation("too many items (above maxItems)", tooMany, Expect.REJECT));
        }
        String itemType = SchemaSampler.type(items);
        Object wrongItem = "object".equals(itemType) || "array".equals(itemType)
                ? "wrong-item-type" : Map.of("wrong", true);
        out.add(new Violation("array with wrong item type", List.of(wrongItem), Expect.REJECT));
    }

    private List<Violation> queryArrayViolations(Schema<?> arr) {
        List<Violation> out = new ArrayList<>();
        Schema<?> items = resolve(arr.getItems());
        String itemType = SchemaSampler.type(items);
        List<?> itemEnum = items != null ? items.getEnum() : null;
        String validItem = items != null ? String.valueOf(SchemaSampler.valid(items)) : "x";

        if (itemEnum != null && !itemEnum.isEmpty()) {
            out.add(new Violation("item not in enum", List.of("___not_in_enum___"), Expect.REJECT));
        }
        if ("integer".equals(itemType) || "number".equals(itemType)) {
            out.add(new Violation("item wrong type (non-numeric)", List.of("not-a-number"), Expect.REJECT));
        }
        if (arr.getMinItems() != null && arr.getMinItems() > 0) {
            out.add(new Violation("empty array (below minItems)", List.of(), Expect.REJECT));
        }
        if (arr.getMaxItems() != null) {
            List<String> many = new ArrayList<>();
            for (int k = 0; k < arr.getMaxItems() + 1; k++) {
                many.add(validItem);
            }
            out.add(new Violation("too many items (above maxItems)", many, Expect.REJECT));
        }
        return out;
    }

    private String describe(String s) {
        if (s.isEmpty()) {
            return "empty";
        }
        return s.isBlank() ? "whitespace" : s;
    }

    // ---------------------------------------------------------------------
    // Body path helpers (build a fresh valid tree, then mutate at a path)
    // ---------------------------------------------------------------------

    private String bodyAtPath(Schema<?> root, List<String> tokens, Object value, boolean remove) {
        Object tree = SchemaSampler.valid(root);
        if (tokens.isEmpty()) {
            return toJson(tree);
        }
        Object parent = tree;
        for (int i = 0; i < tokens.size() - 1; i++) {
            parent = step(parent, tokens.get(i));
            if (parent == null) {
                return toJson(tree);
            }
        }
        applyAt(parent, tokens.get(tokens.size() - 1), value, remove);
        return toJson(tree);
    }

    @SuppressWarnings("rawtypes")
    private Object step(Object parent, String token) {
        if ("[0]".equals(token)) {
            return parent instanceof List<?> l && !l.isEmpty() ? l.get(0) : null;
        }
        return parent instanceof Map<?, ?> m ? ((Map) m).get(token) : null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void applyAt(Object parent, String token, Object value, boolean remove) {
        if ("[0]".equals(token)) {
            if (parent instanceof List l && !l.isEmpty()) {
                if (remove) {
                    l.remove(0);
                } else {
                    l.set(0, value);
                }
            }
        } else if (parent instanceof Map m) {
            if (remove) {
                m.remove(token);
            } else {
                m.put(token, value);
            }
        }
    }

    private String pathLabel(List<String> tokens) {
        StringBuilder sb = new StringBuilder();
        for (String t : tokens) {
            if ("[0]".equals(t)) {
                sb.append("[0]");
            } else {
                if (sb.length() > 0) {
                    sb.append('.');
                }
                sb.append(t);
            }
        }
        return sb.toString();
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
