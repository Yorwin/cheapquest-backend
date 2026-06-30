package com.cheapquest.backend.domain;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class GameDealsTest {

    private static final Instant T = Instant.parse("2026-06-30T10:00:00Z");

    @Test
    void rejects_null_fetchedAt() {
        assertThatNullPointerException()
                .isThrownBy(() -> new GameDeals(
                        "82", "Portal", "Portal", "PORTAL",
                        "https://example.com/thumb.jpg",
                        new BigDecimal("0.99"), 1, null, List.of(), null))
                .withMessageContaining("fetchedAt");
    }

    @Test
    void accepts_non_null_fetchedAt() {
        GameDeals d = new GameDeals(
                "82", "Portal", "Portal", "PORTAL",
                "https://example.com/thumb.jpg",
                new BigDecimal("0.99"), 1, null, List.of(), T);
        org.assertj.core.api.Assertions.assertThat(d.fetchedAt()).isEqualTo(T);
    }
}
