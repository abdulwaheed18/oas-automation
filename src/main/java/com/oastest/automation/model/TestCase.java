package com.oastest.automation.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single, self-contained test case. Each negative case mutates exactly ONE thing relative to an
 * otherwise-valid baseline request (e.g. one missing required header, or one malformed field),
 * so a failure points at a single root cause.
 */
public class TestCase {
    /** Stable id, unique within a generation batch. */
    public String id;

    /** Endpoint this case belongs to. */
    public String method;
    public String endpointPath;

    /** Human-readable category: AUTH, HEADER, QUERY, PATH, BODY, POSITIVE. */
    public String category;

    /** Short title, e.g. "Missing required header: X-Client-Id". */
    public String name;

    /** Longer explanation of what is being exercised. */
    public String description;

    /** The single field/aspect being made negative (null for the positive baseline). */
    public String negativeField;

    // ---- Concrete request blueprint (relative to the target base URL) ----

    /** Path with path-parameters already substituted (may contain a query string). */
    public String requestPath;

    /** Extra request headers (never includes Authorization; see {@link #authMode}). */
    public Map<String, String> headers = new LinkedHashMap<>();

    /** Raw request body (JSON), or null when the case sends no body. */
    public String body;

    /** Content-Type to send with the body, when a body is present. */
    public String contentType;

    /**
     * How the Authorization header is handled:
     * VALID   - attach the bearer token supplied at execution time,
     * MISSING - send no Authorization header,
     * MALFORMED - send a clearly invalid bearer token,
     * EMPTY   - send "Authorization: Bearer " with an empty value,
     * NONE    - endpoint is not secured, no auth involved.
     */
    public String authMode = "VALID";

    // ---- Expectation ----

    /** Expected HTTP status family the gateway SHOULD return, e.g. "4xx", "2xx". */
    public String expectedStatusFamily;

    /** Lower/upper bound (inclusive) of the acceptable status range for a PASS. */
    public int expectedMin;
    public int expectedMax;

    /** Plain-language statement of what a correct gateway does. */
    public String expectedOutcome;
}
