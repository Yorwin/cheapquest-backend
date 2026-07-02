package com.cheapquest.backend.exception;

/**
 * Centralised exception-to-HTTP mapping. Every endpoint runs its
 * handler body inside a try/catch that delegates here, so the
 * status code and the {@link com.cheapquest.backend.endpoint.ErrorResponse}
 * body are decided in a single place. Adding a new exception
 * type means: add a branch below, nothing else.
 *
 * <p>Unrecognised exceptions are mapped to 500 with a generic
 * message and logged with the full stack trace; the on-the-wire
 * response does not leak internal details.
 */
public final class GlobalExceptionHandler {

    public static final int SC_BAD_REQUEST = 400;
    public static final int SC_UNAUTHORIZED = 401;
    public static final int SC_NOT_FOUND = 404;
    public static final int SC_CONFLICT = 409;
    public static final int SC_INTERNAL_SERVER_ERROR = 500;

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private GlobalExceptionHandler() {
    }

    /** Pair of HTTP status and error body to write back to the client. */
    public record Mapped(int status, com.cheapquest.backend.endpoint.ErrorResponse body) {
    }

    public static Mapped handle(Throwable t) {
        if (t instanceof UnauthorizedException e) {
            return new Mapped(SC_UNAUTHORIZED,
                    com.cheapquest.backend.endpoint.ErrorResponse.of("unauthorized", e.getMessage()));
        }
        if (t instanceof ConflictException e) {
            return new Mapped(SC_CONFLICT,
                    com.cheapquest.backend.endpoint.ErrorResponse.of("conflict", e.getMessage()));
        }
        if (t instanceof GameNotFoundException e) {
            return new Mapped(SC_NOT_FOUND,
                    com.cheapquest.backend.endpoint.ErrorResponse.of("not_found", e.getMessage()));
        }
        if (t instanceof IllegalArgumentException e) {
            return new Mapped(SC_BAD_REQUEST,
                    com.cheapquest.backend.endpoint.ErrorResponse.of("bad_request", e.getMessage()));
        }
        log.error("internal_server_error type={} message={}",
                t.getClass().getSimpleName(), t.getMessage(), t);
        return new Mapped(SC_INTERNAL_SERVER_ERROR,
                com.cheapquest.backend.endpoint.ErrorResponse.of("internal_error", "internal error"));
    }
}
