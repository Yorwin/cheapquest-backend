package com.cheapquest.backend.util;

import java.util.Locale;
import java.util.regex.Pattern;

public final class StringNormalize {

    private static final String NON_ALNUM_REGEX = "[^a-z0-9]";
    private static final Pattern NON_ALNUM = Pattern.compile(NON_ALNUM_REGEX);

    private StringNormalize() {
    }

    /**
     * Lowercases, trims and removes every non-alphanumeric character
     * (spaces, hyphens, underscores, punctuation, unicode symbols).
     * Used for fuzzy matching of game titles across APIs:
     * "Half-Life 2", "HALFLIFE2" and "half life 2" all reduce to
     * "halflife2". Throws NullPointerException on null input,
     * matching the prior behaviour of the duplicate in-line
     * implementations this method replaces.
     */
    public static String matchKey(String s) {
        return NON_ALNUM.matcher(s.trim().toLowerCase(Locale.ROOT)).replaceAll("");
    }
}
