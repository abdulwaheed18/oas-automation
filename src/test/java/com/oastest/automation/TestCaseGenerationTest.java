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
    private final TestCaseGeneratorService generator = new TestCaseGeneratorService();

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
                .extracting(c -> c.authMode)
                .contains("MISSING", "MALFORMED", "EMPTY");
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
    void queryAndHeaderNegativesAreGenerated() throws Exception {
        OpenAPI api = loadSample();
        List<TestCase> cases = generator.generate(api, List.of("GET /pets"));

        assertThat(cases).anyMatch(c -> "QUERY".equals(c.category)
                && "limit".equals(c.negativeField));
        assertThat(cases).anyMatch(c -> "HEADER".equals(c.category)
                && "X-Client-Id".equals(c.negativeField));
    }
}
