package com.oastest.automation.model;

import java.util.List;

/**
 * Request to execute a set of test cases against a live gateway target.
 */
public class ExecuteRequest {
    public String sessionId;

    /** Base URL the requests are sent to, e.g. {@code https://gateway.company.com/my-api}. */
    public String targetBaseUrl;

    /** Bearer token used for the "valid auth" cases. May be blank for unsecured APIs. */
    public String bearerToken;

    /** The test cases to run (as returned by /api/testcases/generate). */
    public List<TestCase> cases;
}
