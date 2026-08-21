package com.oastest.automation.model;

/**
 * A single operation (method + path) discovered in the OpenAPI spec, shown on the
 * "choose endpoints" screen.
 */
public class EndpointInfo {
    public String method;
    public String path;
    public String operationId;
    public String summary;
    public boolean secured;
    public boolean hasRequestBody;

    public EndpointInfo() {
    }

    public EndpointInfo(String method, String path, String operationId, String summary,
                        boolean secured, boolean hasRequestBody) {
        this.method = method;
        this.path = path;
        this.operationId = operationId;
        this.summary = summary;
        this.secured = secured;
        this.hasRequestBody = hasRequestBody;
    }

    /** Stable identifier used by the UI checkboxes. */
    public String key() {
        return method + " " + path;
    }
}
