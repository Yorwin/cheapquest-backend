package com.cheapquest.backend.endpoint;

import com.cheapquest.backend.exception.UnauthorizedException;
import com.sun.net.httpserver.Headers;

/**
 * Token authentication for the admin endpoints. Centralised
 * here so every protected handler enforces the same contract.
 *
 * <p>Two sources are accepted (checked in this order):
 * <ol>
 *   <li>{@code X-Admin-Token} header — plain token, no prefix.
 *       Used by Cloud Scheduler which sends its own OIDC
 *       token in {@code Authorization} and puts the admin
 *       token in this custom header.</li>
 *   <li>{@code Authorization: Bearer <token>} — standard
 *       bearer token for direct curl / dev usage.</li>
 * </ol>
 *
 * <p>The comparison is constant-time: the token length is not
 * used as an early-exit signal, so a timing attacker cannot
 * enumerate the prefix character by character.
 */
public final class HttpAuth {

    private HttpAuth() {
    }

    /**
     * Validate the request against the configured token.
     * Checks {@code X-Admin-Token} first, then falls back
     * to {@code Authorization: Bearer}. Throws
     * {@link UnauthorizedException} (mapped to HTTP 401) on
     * any failure: header missing, malformed, or token mismatch.
     *
     * @param headers  the request headers
     * @param expected the configured admin token; {@code null}
     *     or blank means the endpoint is not configured and
     *     every request is rejected (fail closed).
     * @return the supplied token (for logging purposes)
     */
    public static String requireBearer(Headers headers, String expected) {
        if (expected == null || expected.isBlank()) {
            throw new UnauthorizedException("admin endpoint not configured");
        }

        // 1) Try X-Admin-Token (Cloud Scheduler path)
        String custom = headers.getFirst("X-Admin-Token");
        if (custom != null && !custom.isBlank()) {
            if (!constantTimeEquals(custom, expected)) {
                throw new UnauthorizedException("invalid admin token");
            }
            return custom;
        }

        // 2) Fallback to Authorization: Bearer (curl / dev path)
        String header = headers.getFirst("Authorization");
        if (header == null) {
            throw new UnauthorizedException("missing Authorization header");
        }
        if (!header.startsWith("Bearer ")) {
            throw new UnauthorizedException("malformed Authorization header");
        }
        String token = header.substring("Bearer ".length()).trim();
        if (token.isEmpty()) {
            throw new UnauthorizedException("empty bearer token");
        }
        if (!constantTimeEquals(token, expected)) {
            throw new UnauthorizedException("invalid bearer token");
        }
        return token;
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] ab = a.getBytes();
        byte[] bb = b.getBytes();
        if (ab.length != bb.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < ab.length; i++) {
            diff |= ab[i] ^ bb[i];
        }
        return diff == 0;
    }
}
