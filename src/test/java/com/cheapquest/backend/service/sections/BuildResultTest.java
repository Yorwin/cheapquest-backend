package com.cheapquest.backend.service.sections;

import static org.assertj.core.api.Assertions.assertThat;

import com.cheapquest.backend.domain.Offer;
import com.cheapquest.backend.domain.sections.SectionItem;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BuildResultTest {

    private static final SectionItem ITEM = new SectionItem(
            "slug", "Title",
            new Offer("1", "Steam", null,
                    new BigDecimal("9.99"), new BigDecimal("29.99"),
                    new BigDecimal("66.70"), null),
            new BigDecimal("66.70"),
            Map.of("savingsPct", "66.70"));

    @Test
    void accepts_null_items_and_returns_emptyList() {
        BuildResult r = new BuildResult(0, null);
        assertThat(r.items()).isEmpty();
    }

    @Test
    void items_is_defensive_copy() {
        List<SectionItem> mutable = new ArrayList<>();
        mutable.add(ITEM);
        BuildResult r = new BuildResult(1, mutable);
        mutable.clear();
        assertThat(r.items()).hasSize(1);
    }

    @Test
    void items_is_unmodifiable() {
        BuildResult r = new BuildResult(1, new ArrayList<>(List.of(ITEM)));
        assertThat(r.items()).isUnmodifiable();
    }

    @Test
    void empty_factory_carries_totalCandidates() {
        BuildResult r = BuildResult.empty(42);
        assertThat(r.totalCandidates()).isEqualTo(42);
        assertThat(r.items()).isEmpty();
    }
}
