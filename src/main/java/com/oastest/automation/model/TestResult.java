package com.oastest.automation.model;

/**
 * Outcome of executing a single {@link TestCase} against the target gateway.
 */
public class TestResult {
    public String id;
    public String method;
    public String endpointPath;
    public String category;
    public String name;
    public String negativeField;
    public String requestUrl;
    public String expectedOutcome;
    public String expectedStatusFamily;

    /** Actual HTTP status returned by the target (0 if the call itself errored). */
    public int actualStatus;

    /** PASS, FAIL or ERROR. FAIL means the gateway did not enforce the contract. */
    public String verdict;

    /** Short explanation of the verdict. */
    public String message;

    /** First slice of the response body, for debugging. */
    public String responseSnippet;

    public long latencyMs;
}
