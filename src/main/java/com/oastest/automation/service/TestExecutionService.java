package com.oastest.automation.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.oastest.automation.model.ExecuteRequest;
import com.oastest.automation.model.ExecuteResult;
import com.oastest.automation.model.TestCase;
import com.oastest.automation.model.TestResult;

/**
 * Executes generated test cases against a live gateway target. TLS certificate validation is
 * intentionally skipped (see {@link InsecureHttp}) so internal/self-signed endpoints can be tested.
 */
@Service
public class TestExecutionService {

    private static final int SNIPPET_LEN = 400;
    private final HttpClient http = InsecureHttp.client(Duration.ofSeconds(15));

    public ExecuteResult execute(ExecuteRequest request) {
        String base = request.targetBaseUrl == null ? "" : request.targetBaseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }

        ExecuteResult result = new ExecuteResult();
        result.targetBaseUrl = base;
        result.results = new ArrayList<>();
        result.executedAtEpochMs = System.currentTimeMillis();

        List<TestCase> cases = request.cases == null ? List.of() : request.cases;
        for (TestCase tc : cases) {
            result.results.add(runOne(base, request.bearerToken, tc));
        }

        result.total = result.results.size();
        for (TestResult r : result.results) {
            switch (r.verdict) {
                case "PASS" -> result.passed++;
                case "FAIL" -> result.failed++;
                default -> result.errored++;
            }
        }
        return result;
    }

    private TestResult runOne(String base, String bearerToken, TestCase tc) {
        TestResult r = new TestResult();
        r.id = tc.id;
        r.method = tc.method;
        r.endpointPath = tc.endpointPath;
        r.category = tc.category;
        r.name = tc.name;
        r.negativeField = tc.negativeField;
        r.expectedOutcome = tc.expectedOutcome;
        r.expectedStatusFamily = tc.expectedStatusFamily;
        r.requestUrl = base + tc.requestPath;

        long start = System.currentTimeMillis();
        try {
            HttpRequest httpRequest = buildRequest(base, bearerToken, tc);
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            r.latencyMs = System.currentTimeMillis() - start;
            r.actualStatus = response.statusCode();
            r.responseSnippet = snippet(response.body());
            evaluate(r, tc);
        } catch (Exception e) {
            r.latencyMs = System.currentTimeMillis() - start;
            r.actualStatus = 0;
            r.verdict = "ERROR";
            r.message = "Request could not be completed: " + e.getClass().getSimpleName()
                    + " - " + e.getMessage();
            r.responseSnippet = "";
        }
        return r;
    }

    private void evaluate(TestResult r, TestCase tc) {
        if (StatusMatcher.matches(tc.expectedStatuses, r.actualStatus)) {
            r.verdict = "PASS";
            r.message = "Gateway behaved as expected (HTTP " + r.actualStatus
                    + " is in expected " + tc.expectedStatuses + ").";
            return;
        }
        r.verdict = "FAIL";
        if ("POSITIVE".equals(tc.category)) {
            r.message = "Valid request did not succeed (HTTP " + r.actualStatus + "; expected "
                    + tc.expectedStatuses + "). Check the base URL / bearer token — other results may be "
                    + "unreliable if this fails.";
        } else if (r.actualStatus >= 200 && r.actualStatus < 300) {
            r.message = "GATEWAY DID NOT ENFORCE THE CONTRACT: an invalid request was accepted (HTTP "
                    + r.actualStatus + "). Expected " + tc.expectedStatuses + ".";
        } else if (r.actualStatus >= 500) {
            r.message = "Server error (HTTP " + r.actualStatus + ") — the gateway/upstream may have "
                    + "mishandled the input. Expected " + tc.expectedStatuses + ".";
        } else {
            r.message = "Unexpected status HTTP " + r.actualStatus + "; expected " + tc.expectedStatuses + ".";
        }
    }

    private HttpRequest buildRequest(String base, String bearerToken, TestCase tc) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(base + tc.requestPath))
                .timeout(Duration.ofSeconds(30));

        for (Map.Entry<String, String> h : tc.headers.entrySet()) {
            builder.header(h.getKey(), h.getValue());
        }

        switch (tc.authMode == null ? "NONE" : tc.authMode) {
            case "VALID" -> {
                if (bearerToken != null && !bearerToken.isBlank()) {
                    builder.header("Authorization", "Bearer " + bearerToken.trim());
                }
            }
            case "OVERRIDE" -> {
                if (tc.authorization != null) {
                    builder.header("Authorization", tc.authorization);
                }
            }
            case "MISSING", "NONE" -> { /* no Authorization header */ }
            default -> { }
        }

        HttpRequest.BodyPublisher publisher;
        if (tc.body != null) {
            publisher = HttpRequest.BodyPublishers.ofString(tc.body);
            if (tc.contentType != null) {
                builder.header("Content-Type", tc.contentType);
            }
        } else {
            publisher = HttpRequest.BodyPublishers.noBody();
        }
        builder.method(tc.method, publisher);
        return builder.build();
    }

    private String snippet(String body) {
        if (body == null) {
            return "";
        }
        String trimmed = body.strip();
        return trimmed.length() > SNIPPET_LEN ? trimmed.substring(0, SNIPPET_LEN) + "…" : trimmed;
    }
}
