package com.cheapquest.backend.endpoint;

import java.time.Instant;

/**
 * Standard error body returned by every endpoint on a non-2xx
 * response. Carries a stable machine-readable {@code code}, a
 * human-readable {@code message}, and the moment the error was
 * raised (server time, UTC). The shape is fixed; clients can
 * rely on the three fields always being present.
 */
public record ErrorResponse(String code, String message, Instant timestamp) {

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, Instant.now());
    }
}
