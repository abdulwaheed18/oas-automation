package com.oastest.automation.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oastest.automation.model.ExecuteRequest;
import com.oastest.automation.model.ExecuteResult;
import com.oastest.automation.service.TestExecutionService;

/**
 * Screen 3 backend: executes the (possibly edited) test cases against the target gateway URL.
 */
@RestController
@RequestMapping("/api/execute")
public class ExecutionController {

    private final TestExecutionService execution;

    public ExecutionController(TestExecutionService execution) {
        this.execution = execution;
    }

    @PostMapping
    public ExecuteResult execute(@RequestBody ExecuteRequest request) {
        if (request.targetBaseUrl == null || request.targetBaseUrl.isBlank()) {
            throw new IllegalArgumentException("Target base URL is required.");
        }
        return execution.execute(request);
    }
}
