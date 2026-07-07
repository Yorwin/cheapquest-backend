package com.cheapquest.backend.service.sections.builders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cheapquest.backend.domain.Offer;
import com.cheapquest.backend.domain.sections.CheapsharkView;
import com.cheapquest.backend.domain.sections.GameView;
import com.cheapquest.backend.domain.sections.SectionItem;
import com.cheapquest.backend.domain.sections.SectionName;
import com.cheapquest.backend.service.sections.BuildResult;
import com.cheapquest.backend.service.sections.SectionContext;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class MejoresPromosBuilderTest {

    // -------- construction / contract ---------------------------------------

    @Test
    void name_is_mejores_promos() {
        assertThat(new MejoresPromosBuilder(5).name())
                .isEqualTo(SectionName.MEJORES_PROMOS);
    }

    @Test
    void rejects_zero_max_items() {
        assertThatThrownBy(() -> new MejoresPromosBuilder(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxItems");
    }

    @Test
    void rejects_negative_max_items() {
        assertThatThrownBy(() -> new MejoresPromosBuilder(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxItems");
    }

    // -------- build ---------------------------------------------------------

    @Test
    void empty_catalog_yields_empty_result() {
        BuildResult r = new MejoresPromosBuilder(5).build(SectionContext.empty());
        assertThat(r.items()).isEmpty();
        assertThat(r.totalCandidates()).isZero();
    }

    @Test
    void games_with_null_cheapshark_view_are_filtered_out() {
        BuildResult r = new MejoresPromosBuilder(5).build(new SectionContext(List.of(
                new GameView("a", "A", null, null),
                gameWith("b", "B", new BigDecimal("80.00"), true, true))));
        assertThat(r.totalCandidates()).isEqualTo(1);
        assertThat(r.items()).hasSize(1);
        assertThat(r.items().get(0).slug()).isEqualTo("b");
    }

    @Test
    void unsynced_cheapshark_blocks_are_filtered_out() {
        BuildResult r = new MejoresPromosBuilder(5).build(new SectionContext(List.of(
                gameWith("a", "A", new BigDecimal("80.00"), false, true),
                gameWith("b", "B", new BigDecimal("80.00"), true, true))));
        assertThat(r.totalCandidates()).isEqualTo(1);
        assertThat(r.items().get(0).slug()).isEqualTo("b");
    }

    @Test
    void games_with_null_best_deal_are_filtered_out() {
        BuildResult r = new MejoresPromosBuilder(5).build(new SectionContext(List.of(
                gameWithNullBestDeal("a", "A"),
                gameWith("b", "B", new BigDecimal("50.00"), true, true))));
        assertThat(r.totalCandidates()).isEqualTo(1);
        assertThat(r.items().get(0).slug()).isEqualTo("b");
    }

    @Test
    void eligible_games_are_sorted_by_savings_descending() {
        BuildResult r = new MejoresPromosBuilder(5).build(new SectionContext(List.of(
                gameWith("a", "A", new BigDecimal("10.00"), true, true),
                gameWith("b", "B", new BigDecimal("90.00"), true, true),
                gameWith("c", "C", new BigDecimal("50.00"), true, true))));
        assertThat(r.totalCandidates()).isEqualTo(3);
        assertThat(r.items()).extracting(SectionItem::slug)
                .containsExactly("b", "c", "a");
    }

    @Test
    void top_n_is_capped_at_max_items() {
        List<GameView> six = List.of(
                gameWith("a", "A", new BigDecimal("10.00"), true, true),
                gameWith("b", "B", new BigDecimal("20.00"), true, true),
                gameWith("c", "C", new BigDecimal("30.00"), true, true),
                gameWith("d", "D", new BigDecimal("40.00"), true, true),
                gameWith("e", "E", new BigDecimal("50.00"), true, true),
                gameWith("f", "F", new BigDecimal("60.00"), true, true));
        BuildResult r = new MejoresPromosBuilder(5).build(new SectionContext(six));
        assertThat(r.totalCandidates()).isEqualTo(6);
        assertThat(r.items()).extracting(SectionItem::slug)
                .containsExactly("f", "e", "d", "c", "b");
    }

    @Test
    void ties_keep_catalog_order() {
        List<GameView> tied = List.of(
                gameWith("first", "First", new BigDecimal("50.00"), true, true),
                gameWith("second", "Second", new BigDecimal("50.00"), true, true),
                gameWith("third", "Third", new BigDecimal("50.00"), true, true));
        BuildResult r = new MejoresPromosBuilder(5).build(new SectionContext(tied));
        assertThat(r.items()).extracting(SectionItem::slug)
                .containsExactly("first", "second", "third");
    }

    @Test
    void item_exposes_savingsPct_in_extra() {
        BuildResult r = new MejoresPromosBuilder(5).build(new SectionContext(List.of(
                gameWith("a", "A", new BigDecimal("66.70"), true, true))));
        assertThat(r.items().get(0).extra()).containsEntry("savingsPct", "66.70");
    }

    @Test
    void item_score_equals_best_deal_savings() {
        BuildResult r = new MejoresPromosBuilder(5).build(new SectionContext(List.of(
                gameWith("a", "A", new BigDecimal("42.50"), true, true))));
        SectionItem item = r.items().get(0);
        assertThat(item.score()).isEqualByComparingTo("42.50");
        assertThat(item.bestDeal().savings()).isEqualByComparingTo("42.50");
    }

    @Test
    void build_rejects_null_context() {
        assertThatThrownBy(() -> new MejoresPromosBuilder(5).build(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ctx");
    }

    // -------- helpers --------------------------------------------------------

    private static GameView gameWith(String slug, String title,
            BigDecimal savings, boolean synced, boolean withBestDeal) {
        Offer best = withBestDeal
                ? new Offer("1", "Steam", null,
                        BigDecimal.TEN, BigDecimal.valueOf(20), savings, null, null)
                : null;
        return new GameView(slug, title,
                new CheapsharkView(synced, best, null, null, List.of()),
                null);
    }

    private static GameView gameWithNullBestDeal(String slug, String title) {
        return new GameView(slug, title,
                new CheapsharkView(true, null, null, null, List.of()),
                null);
    }
}
