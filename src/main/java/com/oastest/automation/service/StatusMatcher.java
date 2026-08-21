package com.oastest.automation.service;

/**
 * Matches an HTTP status code against a specification string made of comma/space-separated codes
 * and inclusive ranges, e.g. {@code "200,201,204"}, {@code "400-499"}, {@code "400,401,403,422,429"}.
 */
public final class StatusMatcher {

    private StatusMatcher() {
    }

    public static boolean matches(String spec, int code) {
        if (spec == null || spec.isBlank()) {
            return false;
        }
        for (String rawToken : spec.split("[,\\s]+")) {
            String token = rawToken.trim();
            if (token.isEmpty()) {
                continue;
            }
            try {
                int dash = token.indexOf('-');
                if (dash > 0) {
                    int lo = Integer.parseInt(token.substring(0, dash).trim());
                    int hi = Integer.parseInt(token.substring(dash + 1).trim());
                    if (code >= Math.min(lo, hi) && code <= Math.max(lo, hi)) {
                        return true;
                    }
                } else if (Integer.parseInt(token) == code) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
                // skip malformed tokens rather than failing the whole run
            }
        }
        return false;
    }
}
