package com.oastest.automation.controller;

import java.util.List;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oastest.automation.model.GenerateRequest;
import com.oastest.automation.model.GenerateResult;
import com.oastest.automation.model.TestCase;
import com.oastest.automation.service.SpecSession;
import com.oastest.automation.service.SpecSessionStore;
import com.oastest.automation.service.TestCaseGeneratorService;

/**
 * Screen 3 backend: generates negative/edge-case test cases for the endpoints selected on screen 2.
 */
@RestController
@RequestMapping("/api/testcases")
public class TestCaseController {

    private final SpecSessionStore store;
    private final TestCaseGeneratorService generator;

    public TestCaseController(SpecSessionStore store, TestCaseGeneratorService generator) {
        this.store = store;
        this.generator = generator;
    }

    @PostMapping("/generate")
    public GenerateResult generate(@RequestBody GenerateRequest request) {
        SpecSession session = store.get(request.sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Unknown or expired session. Please re-upload the spec.");
        }
        List<TestCase> cases = generator.generate(session.openAPI, request.endpointKeys);

        GenerateResult result = new GenerateResult();
        result.sessionId = request.sessionId;
        result.cases = cases;
        result.totalCases = cases.size();
        return result;
    }
}
