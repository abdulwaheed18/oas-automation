package com.oastest.automation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.oastest.automation.model.EndpointInfo;
import com.oastest.automation.model.TestCase;
import com.oastest.automation.service.SpecParserService;
import com.oastest.automation.service.TestCaseGeneratorService;

import io.swagger.v3.oas.models.OpenAPI;

/**
 * Unit-level checks for parsing and negative test-case generation using the bundled sample spec.
 * These run without starting the web server or making network calls.
 */
class TestCaseGenerationTest {

    private final SpecParserService parser = new SpecParserService();
    private final TestCaseGeneratorService generator =
            new TestCaseGeneratorService(new com.oastest.automation.config.TestingProperties());

    private OpenAPI loadSample() throws Exception {
        String yaml = Files.readString(Path.of("samples/petstore.yaml"));
        return parser.parse(yaml).getOpenAPI();
    }

    private OpenAPI loadSampleJson() throws Exception {
        String json = Files.readString(Path.of("samples/petstore.json"));
        return parser.parse(json).getOpenAPI();
    }

    @Test
    void parsesSwagger2SpecViaConversion() throws Exception {
        String json = Files.readString(Path.of("samples/petstore-swagger2.json"));
        OpenAPI api = parser.parse(json).getOpenAPI();
        // A Swagger 2.0 doc is converted to OpenAPI 3.x internally.
        assertThat(api.getOpenapi()).startsWith("3.");
        List<EndpointInfo> endpoints = parser.listEndpoints(api);
        assertThat(endpoints).extracting(EndpointInfo::key)
                .contains("GET /pets", "POST /pets");

        // Generation still works on the converted model.
        List<TestCase> cases = generator.generate(api, List.of("POST /pets"));
        assertThat(cases).anyMatch(c -> "POSITIVE".equals(c.category));
        assertThat(cases).anyMatch(c -> "BODY".equals(c.category) && "name".equals(c.negativeField));
    }

    @Test
    void parsesJsonSpecToo() throws Exception {
        OpenAPI api = loadSampleJson();
        List<EndpointInfo> endpoints = parser.listEndpoints(api);
        assertThat(endpoints).extracting(EndpointInfo::key)
                .contains("GET /pets", "POST /pets", "GET /pets/{petId}");
    }

    @Test
    void yamlAndJsonProduceTheSameEndpointsAndCases() throws Exception {
        List<EndpointInfo> fromYaml = parser.listEndpoints(loadSample());
        List<EndpointInfo> fromJson = parser.listEndpoints(loadSampleJson());
        assertThat(fromJson).extracting(EndpointInfo::key)
                .containsExactlyInAnyOrderElementsOf(
                        fromYaml.stream().map(EndpointInfo::key).toList());

        int yamlCases = generator.generate(loadSample(), List.of("POST /pets")).size();
        int jsonCases = generator.generate(loadSampleJson(), List.of("POST /pets")).size();
        assertThat(jsonCases).isEqualTo(yamlCases);
    }

    @Test
    void parsesEndpoints() throws Exception {
        OpenAPI api = loadSample();
        List<EndpointInfo> endpoints = parser.listEndpoints(api);
        assertThat(endpoints).extracting(e -> e.key())
                .contains("GET /pets", "POST /pets", "GET /pets/{petId}");
        assertThat(endpoints).allMatch(e -> e.secured); // global bearerAuth
    }

    @Test
    void generatesAuthNegativesForSecuredEndpoint() throws Exception {
        OpenAPI api = loadSample();
        List<TestCase> cases = generator.generate(api, List.of("GET /pets"));

        assertThat(cases).anyMatch(c -> "POSITIVE".equals(c.category));
        assertThat(cases).filteredOn(c -> "AUTH".equals(c.category))
                .extracting(c -> c.name)
                .contains("Missing Authorization header", "Malformed bearer token", "Empty bearer token",
                        "Wrong auth scheme (Basic)");
        // Every auth negative expects the configured unauthorized codes.
        assertThat(cases).filteredOn(c -> "AUTH".equals(c.category))
                .allMatch(c -> c.expectedStatuses != null && c.expectedStatuses.contains("401"));
    }

    @Test
    void everyNegativeCaseTargetsExactlyOneField() throws Exception {
        OpenAPI api = loadSample();
        List<TestCase> cases = generator.generate(api, List.of("POST /pets"));

        // Missing required body field cases exist and each names a single field.
        assertThat(cases).filteredOn(c -> "BODY".equals(c.category))
                .allMatch(c -> c.negativeField != null && !c.negativeField.isBlank());

        // An enum violation for 'category' is present.
        assertThat(cases).anyMatch(c ->
                "category".equals(c.negativeField) && c.name.toLowerCase().contains("enum"));
    }

    @Test
    void authorizationHeaderParamIsTreatedAsAuthNotSampled() throws Exception {
        // A spec that declares Authorization as a plain header parameter (no securitySchemes).
        String yaml = """
                openapi: 3.0.3
                info: { title: HdrAuth, version: 1.0.0 }
                paths:
                  /ping:
                    get:
                      parameters:
                        - name: Authorization
                          in: header
                          required: true
                          schema: { type: string }
                      responses:
                        '200': { description: OK }
                """;
        OpenAPI api = parser.parse(yaml).getOpenAPI();

        // The endpoint should be flagged secured because of the Authorization header param.
        assertThat(parser.listEndpoints(api)).allMatch(e -> e.secured);

        List<TestCase> cases = generator.generate(api, List.of("GET /ping"));

        // The positive baseline must inject the bearer token (authMode VALID), and must NOT put a
        // sampled "Authorization: sample" header.
        TestCase positive = cases.stream().filter(c -> "POSITIVE".equals(c.category)).findFirst().orElseThrow();
        assertThat(positive.authMode).isEqualTo("VALID");
        assertThat(positive.headers).doesNotContainKey("Authorization");

        // Auth negatives must be generated even though there was no securityScheme.
        assertThat(cases).anyMatch(c -> "AUTH".equals(c.category) && "Missing Authorization header".equals(c.name));

        // No generic HEADER case should target the Authorization header.
        assertThat(cases).noneMatch(c -> "HEADER".equals(c.category)
                && "Authorization".equalsIgnoreCase(c.negativeField));
    }

    @Test
    void regexSamplerProducesMatchingValues() {
        for (String pattern : new String[]{"[A-Z0-9]{3}", "[a-z]{2,5}", "\\d{4}", "[A-Za-z0-9_-]+",
                "PRE-[0-9]{3}", "(cat|dog|bird)", "[A-Z]{2}\\d{2}"}) {
            String s = com.oastest.automation.service.RegexSampler.sample(pattern);
            assertThat(s).as("sample for %s", pattern).isNotNull();
            assertThat(s).matches(pattern);
        }
    }

    @Test
    void patternedFieldGetsAValidMatchingBaseline() throws Exception {
        String yaml = """
                openapi: 3.0.3
                info: { title: Pat, version: 1.0.0 }
                paths:
                  /x:
                    get:
                      parameters:
                        - name: X-Code
                          in: header
                          required: true
                          schema: { type: string, pattern: '[A-Z0-9]{3}' }
                      responses:
                        '200': { description: OK }
                """;
        OpenAPI api = parser.parse(yaml).getOpenAPI();
        List<TestCase> cases = generator.generate(api, List.of("GET /x"));

        // The positive baseline header value must satisfy the pattern.
        TestCase positive = cases.stream().filter(c -> "POSITIVE".equals(c.category)).findFirst().orElseThrow();
        assertThat(positive.headers.get("X-Code")).matches("[A-Z0-9]{3}");

        // A pattern-violation negative should still be generated (its value must NOT match).
        TestCase patCase = cases.stream()
                .filter(c -> c.name.toLowerCase().contains("pattern violation")).findFirst().orElseThrow();
        assertThat(patCase.headers.get("X-Code")).doesNotMatch("[A-Z0-9]{3}");
    }

    @Test
    void statusMatcherHandlesListsAndRanges() {
        assertThat(com.oastest.automation.service.StatusMatcher.matches("200,201,202,204", 201)).isTrue();
        assertThat(com.oastest.automation.service.StatusMatcher.matches("400-499", 422)).isTrue();
        assertThat(com.oastest.automation.service.StatusMatcher.matches("400,401,403,429", 500)).isFalse();
        assertThat(com.oastest.automation.service.StatusMatcher.matches("100-499", 503)).isFalse();
        assertThat(com.oastest.automation.service.StatusMatcher.matches("200, 201 , 400-404", 404)).isTrue();
    }

    @Test
    void generatesRobustnessAndBoundaryCases() throws Exception {
        OpenAPI api = loadSample();
        List<TestCase> cases = generator.generate(api, List.of("POST /pets"));
        // Injection / XSS robustness probes expect "no 5xx".
        assertThat(cases).anyMatch(c -> c.name.toLowerCase().contains("injection"));
        assertThat(cases).anyMatch(c -> "no 5xx (handled)".equals(c.expectedStatusFamily));
        // Boundary-valid cases expect success codes.
        assertThat(cases).anyMatch(c -> c.name.toLowerCase().contains("boundary")
                && c.expectedStatuses.contains("200"));
        // Wrong-root-type and extra-field cases exist.
        assertThat(cases).anyMatch(c -> c.name.contains("Wrong root type"));
        assertThat(cases).anyMatch(c -> c.name.contains("Unexpected extra field"));
    }

    @Test
    void queryAndHeaderNegativesAreGenerated() throws Exception {
        OpenAPI api = loadSample();
        List<TestCase> cases = generator.generate(api, List.of("GET /pets"));

        assertThat(cases).anyMatch(c -> "QUERY".equals(c.category)
                && "limit".equals(c.negativeField));
        assertThat(cases).anyMatch(c -> "HEADER".equals(c.category)
                && "X-Client-Id".equals(c.negativeField));
    }
}
