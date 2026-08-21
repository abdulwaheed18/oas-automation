package com.oastest.automation.model;

import java.util.List;

/**
 * Aggregate execution outcome with per-case results and summary counts.
 */
public class ExecuteResult {
    public String targetBaseUrl;
    public int total;
    public int passed;
    public int failed;
    public int errored;
    public long executedAtEpochMs;
    public List<TestResult> results;
}
