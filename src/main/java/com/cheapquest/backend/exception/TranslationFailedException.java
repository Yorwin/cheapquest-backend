package com.cheapquest.backend.exception;

/**
 * Thrown when the DeepL translation pipeline fails for a reason
 * the caller cannot recover from inside the request: a permanent
 * 4xx other than 429 (bad API key, unsupported language pair), a
 * network outage that has exhausted its retry budget, or a quota
 * exhaustion (HTTP 456).
 *
 * <p>Translated 502 by {@code GlobalExceptionHandler}: from the
 * backend's point of view, DeepL is an upstream that is currently
 * unavailable or unusable.
 */
public class TranslationFailedException extends RuntimeException {

    public TranslationFailedException(String message) {
        super(message);
    }

    public TranslationFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
