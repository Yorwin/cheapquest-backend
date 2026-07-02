package com.cheapquest.backend.util;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Instant helpers. Intentionally minimal — extend only when the
 * same null/parse idiom appears in three or more call sites.
 */
public final class InstantUtils {

    private InstantUtils() {
    }

    /**
     * Null-safe ISO-8601 parser. {@code null} or unparseable input
     * returns {@code null} instead of throwing. Mirrors the
     * behaviour of {@link StringUtils#isBlank(String)}: the caller
     * treats {@code null} as "absent" and decides what "absent"
     * means in context.
     */
    public static Instant parseOrNull(String iso) {
        if (iso == null) {
            return null;
        }
        try {
            return Instant.parse(iso);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
