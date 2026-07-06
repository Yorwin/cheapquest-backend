package com.cheapquest.backend.domain.sections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.cheapquest.backend.domain.Offer;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SectionSnapshotTest {

    private static final LocalDate DAY = LocalDate.parse("2026-07-06");
    private static final Instant NOW = Instant.parse("2026-07-06T00:00:05Z");

    private static SectionItem item() {
        return new SectionItem(
                "slug", "Title",
                new Offer("1", "Steam", null,
                        new BigDecimal("9.99"), new BigDecimal("29.99"),
                        new BigDecimal("66.70"), null),
                new BigDecimal("66.70"),
                Map.of("savingsPct", "66.70"));
    }

    @Test
    void rejects_null_name() {
        assertThatNullPointerException()
                .isThrownBy(() -> new SectionSnapshot(null, DAY, NOW, 0, List.of()))
                .withMessageContaining("name");
    }

    @Test
    void rejects_null_date() {
        assertThatNullPointerException()
                .isThrownBy(() -> new SectionSnapshot(SectionName.MEJORES_PROMOS, null, NOW, 0, List.of()))
                .withMessageContaining("date");
    }

    @Test
    void rejects_null_computedAt() {
        assertThatNullPointerException()
                .isThrownBy(() -> new SectionSnapshot(SectionName.MEJORES_PROMOS, DAY, null, 0, List.of()))
                .withMessageContaining("computedAt");
    }

    @Test
    void accepts_null_items_and_returns_emptyList() {
        SectionSnapshot s = new SectionSnapshot(
                SectionName.MEJORES_PROMOS, DAY, NOW, 0, null);
        assertThat(s.items()).isEmpty();
        assertThat(s.items()).hasSize(0);
    }

    @Test
    void items_is_defensive_copy() {
        List<SectionItem> mutable = new ArrayList<>();
        mutable.add(item());
        SectionSnapshot s = new SectionSnapshot(
                SectionName.MEJORES_PROMOS, DAY, NOW, 1, mutable);
        mutable.clear();
        assertThat(s.items()).hasSize(1);
    }

    @Test
    void items_is_unmodifiable() {
        SectionSnapshot s = new SectionSnapshot(
                SectionName.MEJORES_PROMOS, DAY, NOW, 1, List.of(item()));
        assertThat(s.items()).isUnmodifiable();
    }

    @Test
    void carries_total_candidates_and_items_size() {
        SectionSnapshot s = new SectionSnapshot(
                SectionName.MEJORES_PROMOS, DAY, NOW, 42, List.of(item()));
        assertThat(s.totalCandidates()).isEqualTo(42);
        assertThat(s.items()).hasSize(1);
        assertThat(s.name()).isEqualTo(SectionName.MEJORES_PROMOS);
        assertThat(s.date()).isEqualTo(DAY);
        assertThat(s.computedAt()).isEqualTo(NOW);
    }
}
