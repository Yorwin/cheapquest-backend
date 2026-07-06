package com.cheapquest.backend.dto.firebase.sections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SectionSnapshotDtoTest {

    @Test
    void rejects_null_name() {
        assertThatNullPointerException()
                .isThrownBy(() -> new SectionSnapshotDto(
                        null, "2026-07-06", "2026-07-06T00:00:05Z", 0, List.of()))
                .withMessageContaining("name");
    }

    @Test
    void rejects_null_date() {
        assertThatNullPointerException()
                .isThrownBy(() -> new SectionSnapshotDto(
                        "mejores-promos", null, "2026-07-06T00:00:05Z", 0, List.of()))
                .withMessageContaining("date");
    }

    @Test
    void rejects_null_computedAt() {
        assertThatNullPointerException()
                .isThrownBy(() -> new SectionSnapshotDto(
                        "mejores-promos", "2026-07-06", null, 0, List.of()))
                .withMessageContaining("computedAt");
    }

    @Test
    void rejects_null_totalCandidates() {
        assertThatNullPointerException()
                .isThrownBy(() -> new SectionSnapshotDto(
                        "mejores-promos", "2026-07-06", "2026-07-06T00:00:05Z", null, List.of()))
                .withMessageContaining("totalCandidates");
    }

    @Test
    void accepts_null_items_and_returns_emptyList() {
        SectionSnapshotDto dto = new SectionSnapshotDto(
                "mejores-promos", "2026-07-06", "2026-07-06T00:00:05Z", 0, null);
        assertThat(dto.items()).isEmpty();
    }

    @Test
    void items_is_defensive_copy() {
        List<SectionItemDto> mutable = new ArrayList<>();
        SectionSnapshotDto dto = new SectionSnapshotDto(
                "mejores-promos", "2026-07-06", "2026-07-06T00:00:05Z", 1, mutable);
        mutable.clear();
        assertThat(dto.items()).isEmpty();
    }

    @Test
    void items_is_unmodifiable() {
        SectionSnapshotDto dto = new SectionSnapshotDto(
                "mejores-promos", "2026-07-06", "2026-07-06T00:00:05Z", 0, List.of());
        assertThat(dto.items()).isUnmodifiable();
    }
}
