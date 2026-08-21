package com.oastest.automation.model;

import java.util.List;

/**
 * Request to generate test cases for a subset of the parsed endpoints.
 */
public class GenerateRequest {
    public String sessionId;

    /** Endpoint keys ("METHOD path") selected on the previous screen. */
    public List<String> endpointKeys;
}
