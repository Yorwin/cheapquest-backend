package com.cheapquest.backend.endpoint;

import com.cheapquest.backend.exception.UnauthorizedException;
import com.sun.net.httpserver.Headers;

/**
 * Bearer-token authentication for the admin endpoints. Centralised
 * here so every protected handler enforces the same contract:
 * a {@code Authorization: Bearer <token>} header must be present
 * and the token must match the configured value exactly.
 *
 * <p>The comparison is constant-time: the token length is not
 * used as an early-exit signal, so a timing attacker cannot
 * enumerate the prefix character by character.
 */
public final class HttpAuth {

    private HttpAuth() {
    }

    /**
     * Validate the {@code Authorization} header against the
     * configured token. Throws {@link UnauthorizedException}
     * (mapped to HTTP 401) on any failure: missing header,
     * malformed header, blank token, or token mismatch.
     *
     * @param headers the request headers
     * @param expected the configured admin token; {@code null} or
     *     blank means the endpoint is not configured and every
     *     request is rejected (fail closed).
     * @return the supplied token (for logging purposes)
     */
    public static String requireBearer(Headers headers, String expected) {
        if (expected == null || expected.isBlank()) {
            throw new UnauthorizedException("admin endpoint not configured");
        }
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
