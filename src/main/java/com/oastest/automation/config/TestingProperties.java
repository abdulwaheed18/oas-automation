package com.oastest.automation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Default expected-status configuration. These drive what a "correct" gateway is expected to return
 * for each class of test case. They can be changed here (properties / environment) and are also
 * editable per-run from the UI.
 *
 * <p>Each value is a comma-separated list of individual codes and/or inclusive ranges, e.g.
 * {@code 200,201,202,204} or {@code 400-499} or {@code 400,401,403,422,429}.</p>
 */
@ConfigurationProperties(prefix = "oas.testing")
public class TestingProperties {

    /** Codes a valid (positive baseline) request is expected to return. */
    private String successCodes = "200,201,202,204";

    /** Codes an invalid request (validation negative) is expected to be rejected with. */
    private String rejectCodes = "400,401,403,404,405,406,409,415,422,429,499";

    /** Codes an unauthenticated / bad-token request is expected to be rejected with. */
    private String authRejectCodes = "401,403";

    /**
     * Acceptable range for "robustness" probes (fuzzing, injection, traversal): the gateway may
     * sanitise (2xx) or reject (4xx), but must not crash with a 5xx.
     */
    private String robustnessCodes = "100-499";

    public String getSuccessCodes() {
        return successCodes;
    }

    public void setSuccessCodes(String successCodes) {
        this.successCodes = successCodes;
    }

    public String getRejectCodes() {
        return rejectCodes;
    }

    public void setRejectCodes(String rejectCodes) {
        this.rejectCodes = rejectCodes;
    }

    public String getAuthRejectCodes() {
        return authRejectCodes;
    }

    public void setAuthRejectCodes(String authRejectCodes) {
        this.authRejectCodes = authRejectCodes;
    }

    public String getRobustnessCodes() {
        return robustnessCodes;
    }

    public void setRobustnessCodes(String robustnessCodes) {
        this.robustnessCodes = robustnessCodes;
    }
}
