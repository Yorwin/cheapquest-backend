package com.cheapquest.backend.util;

/**
 * String helpers. Intentionally minimal — extend only when the same
 * null/blank idiom appears in three or more call sites.
 */
public final class StringUtils {

    private StringUtils() {
    }

    /**
     * Null-safe blank check. {@code null} or whitespace-only counts as blank.
     * Mirrors the behaviour of the JDK 11+ {@code String.isBlank()} extended
     * to tolerate {@code null}.
     */
    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
