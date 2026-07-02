package com.cheapquest.backend.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StringUtilsTest {

    @Test
    void isBlank_returnsTrueForNull() {
        assertThat(StringUtils.isBlank(null)).isTrue();
    }

    @Test
    void isBlank_returnsTrueForEmptyString() {
        assertThat(StringUtils.isBlank("")).isTrue();
    }

    @Test
    void isBlank_returnsTrueForWhitespace() {
        assertThat(StringUtils.isBlank(" ")).isTrue();
        assertThat(StringUtils.isBlank("\t")).isTrue();
        assertThat(StringUtils.isBlank("\n")).isTrue();
        assertThat(StringUtils.isBlank("   \t\n  ")).isTrue();
    }

    @Test
    void isBlank_returnsFalseForNonBlank() {
        assertThat(StringUtils.isBlank("x")).isFalse();
        assertThat(StringUtils.isBlank(" x ")).isFalse();
        assertThat(StringUtils.isBlank("hello")).isFalse();
    }

    @Test
    void isBlank_treatsSingleSpaceAsBlankSoMissignFieldsAreCounted() {
        // This is the rule MissingFieldRules relies on: a single
        // space in a Firestore field is treated as "missing" so
        // the validator flags the field and the cron re-fetches.
        assertThat(StringUtils.isBlank(" ")).isTrue();
    }
}
