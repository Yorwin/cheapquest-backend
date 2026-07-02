package com.cheapquest.backend.exception;

/**
 * Thrown by the admin endpoints when the caller did not present a
 * valid bearer token. Mapped to HTTP 401 by
 * {@code GlobalExceptionHandler}.
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
