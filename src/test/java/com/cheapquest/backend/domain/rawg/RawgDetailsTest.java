package com.cheapquest.backend.domain.rawg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RawgDetailsTest {

    private static final Instant T = Instant.parse("2026-06-30T10:00:00Z");

    @Test
    void rejects_null_fetchedAt() {
        assertThatNullPointerException()
                .isThrownBy(() -> new RawgDetails(
                        "portal", "Portal", "Portal", "2007-10-10",
                        "desc", "descRaw", "header", "trailer", null,
                        null, null, null, 0, 0, 0, 0,
                        List.of(), List.of(), List.of(), List.of(),
                        null, null, null, null, List.of(),
                        false, null, null, List.of(), 0, 0, 0, null, 0, 0, 0, 0, 0,
                        null, List.of(), null, List.of(), List.of(),
                        java.util.Map.of(), java.util.Map.of(), 0,
                        null))
                .withMessageContaining("fetchedAt");
    }

    @Test
    void accepts_non_null_fetchedAt() {
        RawgDetails d = new RawgDetails(
                "portal", "Portal", "Portal", "2007-10-10",
                "desc", "descRaw", "header", "trailer", null,
                null, null, null, 0, 0, 0, 0,
                List.of(), List.of(), List.of(), List.of(),
                null, null, null, null, List.of(),
                false, null, null, List.of(), 0, 0, 0, null, 0, 0, 0, 0, 0,
                null, List.of(), null, List.of(), List.of(),
                java.util.Map.of(), java.util.Map.of(), 0,
                T);
        assertThat(d.fetchedAt()).isEqualTo(T);
    }
}
