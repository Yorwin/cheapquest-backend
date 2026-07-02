package com.cheapquest.backend.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class InstantUtilsTest {

    @Test
    void parseOrNull_returnsNullForNull() {
        assertThat(InstantUtils.parseOrNull(null)).isNull();
    }

    @Test
    void parseOrNull_returnsNullForBlankString() {
        // The helper is null-safe; blank input is treated like
        // null because ISO-8601 parsing would throw on it anyway.
        assertThat(InstantUtils.parseOrNull("")).isNull();
        assertThat(InstantUtils.parseOrNull("   ")).isNull();
    }

    @Test
    void parseOrNull_returnsNullForMalformed() {
        assertThat(InstantUtils.parseOrNull("not-a-date")).isNull();
        assertThat(InstantUtils.parseOrNull("2026-13-40T25:99:99Z")).isNull();
    }

    @Test
    void parseOrNull_returnsInstantForValidIso() {
        Instant expected = Instant.parse("2026-06-30T10:00:00Z");
        assertThat(InstantUtils.parseOrNull("2026-06-30T10:00:00Z")).isEqualTo(expected);
    }

    @Test
    void parseOrNull_preservesNanosecondPrecision() {
        // ISO-8601 with fractional seconds is preserved round-trip
        // by the parser, so a stale-pending recovery that compares
        // Instants does not lose precision.
        Instant expected = Instant.parse("2026-06-30T10:00:00.123456789Z");
        assertThat(InstantUtils.parseOrNull("2026-06-30T10:00:00.123456789Z")).isEqualTo(expected);
    }
}
