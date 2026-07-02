package com.cheapquest.backend.exception;

/**
 * Thrown when a request cannot be served because the resource or
 * the operation is in a conflicting state (typically: another
 * refresh is already running). Mapped to HTTP 409 by
 * {@code GlobalExceptionHandler}.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
