package com.cheapquest.backend.exception;

/**
 * Raised by {@code GameDao.update} when the target document
 * no longer exists at the time of the write. This is distinct
 * from {@link FirebaseUnavailableException}: a missing document
 * is a logical state (the operator deleted it between read and
 * write, or a previous hydration's {@code moveToFailed} cleared
 * it), not a backend failure. Callers can decide to log, drop
 * the doc, or count it separately in their metrics.
 */
public class DocumentNotFoundException extends FirebaseUnavailableException {

    public DocumentNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
