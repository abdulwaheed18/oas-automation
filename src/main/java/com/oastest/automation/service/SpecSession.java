package com.oastest.automation.service;

import io.swagger.v3.oas.models.OpenAPI;

/**
 * Server-side holder for a parsed spec and the metadata entered on screen 1. Kept in memory and
 * keyed by a session id so the wizard can move between screens without re-uploading.
 */
public class SpecSession {
    public final String sessionId;
    public final OpenAPI openAPI;
    public final String apiName;
    public final String apiVersion;
    public final String note;
    public final long createdAtEpochMs;

    public SpecSession(String sessionId, OpenAPI openAPI, String apiName, String apiVersion, String note) {
        this.sessionId = sessionId;
        this.openAPI = openAPI;
        this.apiName = apiName;
        this.apiVersion = apiVersion;
        this.note = note;
        this.createdAtEpochMs = System.currentTimeMillis();
    }
}
