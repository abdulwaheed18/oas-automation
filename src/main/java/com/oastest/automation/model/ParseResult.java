package com.oastest.automation.model;

import java.util.List;

/**
 * Response returned after an OpenAPI spec is parsed. The {@code sessionId} is used on subsequent
 * steps so the server can reload the parsed model without re-uploading.
 */
public class ParseResult {
    public String sessionId;
    public String apiName;
    public String apiVersion;
    public String note;
    public String specTitle;
    public String specVersion;
    public int endpointCount;
    public List<EndpointInfo> endpoints;
}
