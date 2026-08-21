package com.oastest.automation.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Simple in-memory store for parsed specs. Old entries are evicted opportunistically once the
 * store grows past a soft cap; this tool is a single-user desktop-style utility so a full cache
 * abstraction would be overkill.
 */
@Component
public class SpecSessionStore {

    private static final int SOFT_CAP = 50;

    private final Map<String, SpecSession> sessions = new ConcurrentHashMap<>();

    public void put(SpecSession session) {
        if (sessions.size() > SOFT_CAP) {
            sessions.values().stream()
                    .min((a, b) -> Long.compare(a.createdAtEpochMs, b.createdAtEpochMs))
                    .ifPresent(oldest -> sessions.remove(oldest.sessionId));
        }
        sessions.put(session.sessionId, session);
    }

    public SpecSession get(String sessionId) {
        return sessions.get(sessionId);
    }
}
