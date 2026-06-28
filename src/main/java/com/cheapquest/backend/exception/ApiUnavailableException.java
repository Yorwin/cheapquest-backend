package com.cheapquest.backend.exception;

public class ApiUnavailableException extends RuntimeException {

    private final int status;
    private final String body;

    public ApiUnavailableException(String message) {
        super(message);
        this.status = 0;
        this.body = null;
    }

    public ApiUnavailableException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
        this.body = null;
    }

    public ApiUnavailableException(String message, int status, String body) {
        super(message);
        this.status = status;
        this.body = body;
    }

    public int status() {
        return status;
    }

    public String body() {
        return body;
    }
}
