package com.cheapquest.backend.scripts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BackfillFirstSeenAtTest {

    private static final String EPOCH = "1970-01-01T00:00:00Z";

    @Test
    void both_null_falls_back_to_epoch() {
        assertThat(BackfillFirstSeenAt.pickFirstSeen(null, null)).isEqualTo(EPOCH);
    }

    @Test
    void both_blank_falls_back_to_epoch() {
        assertThat(BackfillFirstSeenAt.pickFirstSeen("", "")).isEqualTo(EPOCH);
    }

    @Test
    void whitespace_only_falls_back_to_epoch() {
        assertThat(BackfillFirstSeenAt.pickFirstSeen("   ", "   ")).isEqualTo(EPOCH);
    }

    @Test
    void null_fetchedAt_uses_addedAt() {
        assertThat(BackfillFirstSeenAt.pickFirstSeen(null, "2026-01-01T00:00:00Z"))
                .isEqualTo("2026-01-01T00:00:00Z");
    }

    @Test
    void blank_fetchedAt_uses_addedAt() {
        assertThat(BackfillFirstSeenAt.pickFirstSeen("", "2026-01-01T00:00:00Z"))
                .isEqualTo("2026-01-01T00:00:00Z");
    }

    @Test
    void null_addedAt_uses_fetchedAt() {
        assertThat(BackfillFirstSeenAt.pickFirstSeen("2026-07-01T00:00:00Z", null))
                .isEqualTo("2026-07-01T00:00:00Z");
    }

    @Test
    void both_present_prefers_fetchedAt() {
        assertThat(BackfillFirstSeenAt.pickFirstSeen(
                "2026-07-01T00:00:00Z", "2026-01-01T00:00:00Z"))
                .isEqualTo("2026-07-01T00:00:00Z");
    }

    @Test
    void blank_addedAt_still_uses_fetchedAt() {
        assertThat(BackfillFirstSeenAt.pickFirstSeen(
                "2026-07-01T00:00:00Z", ""))
                .isEqualTo("2026-07-01T00:00:00Z");
    }

    @Test
    void blank_fetchedAt_with_null_addedAt_falls_back_to_epoch() {
        assertThat(BackfillFirstSeenAt.pickFirstSeen("", null)).isEqualTo(EPOCH);
    }
}
