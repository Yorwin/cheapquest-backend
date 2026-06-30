package com.cheapquest.backend.exception;

/**
 * Thrown when a Firestore read or write fails for any reason:
 * network, auth, quota, missing project id, etc. The caller
 * decides whether to retry, log and skip, or surface to the
 * operator UI.
 */
public class FirebaseUnavailableException extends RuntimeException {

    public FirebaseUnavailableException(String message) {
        super(message);
    }

    public FirebaseUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
