package com.cheapquest.backend.service.sections.builders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cheapquest.backend.domain.Offer;
import com.cheapquest.backend.domain.sections.CheapsharkView;
import com.cheapquest.backend.domain.sections.GameView;
import com.cheapquest.backend.domain.sections.RawgView;
import com.cheapquest.backend.domain.sections.SectionItem;
import com.cheapquest.backend.domain.sections.SectionName;
import com.cheapquest.backend.service.sections.BuildResult;
import com.cheapquest.backend.service.sections.SectionContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PopularesBuilderTest {

    private static final BigDecimal SAVINGS_50 = new BigDecimal("50");
    private static final BigDecimal SAVINGS_80 = new BigDecimal("80");
    private static final BigDecimal SAVINGS_BELOW_50 = new BigDecimal("49.99");
    private static final BigDecimal RETAIL = new BigDecimal("39.99");

    // -------- construction / contract ---------------------------------------

    @Test
    void name_is_populares() {
        assertThat(new PopularesBuilder(11).name())
                .isEqualTo(SectionName.POPULARES);
    }

    @Test
    void rejects_zero_max_items() {
        assertThatThrownBy(() -> new PopularesBuilder(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxItems");
    }

    @Test
    void rejects_negative_max_items() {
        assertThatThrownBy(() -> new PopularesBuilder(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxItems");
    }

    @Test
    void build_rejects_null_context() {
        assertThatThrownBy(() -> new PopularesBuilder(11).build(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ctx");
    }

    // -------- build / empty -------------------------------------------------

    @Test
    void empty_catalog_yields_empty_result() {
        BuildResult r = new PopularesBuilder(11).build(SectionContext.empty());
        assertThat(r.items()).isEmpty();
        assertThat(r.totalCandidates()).isZero();
    }

    // -------- filter: cheapshark -------------------------------------------

    @Test
    void games_with_null_cheapshark_view_are_filtered_out() {
        BuildResult r = new PopularesBuilder(11).build(new SectionContext(List.of(
                new GameView("a", "A", null, null),
                popularGame("b", "B", 100, Map.of("owned", 50), 0, 0, SAVINGS_80))));
        assertThat(r.totalCandidates()).isEqualTo(1);
        assertThat(r.items().get(0).slug()).isEqualTo("b");
    }

    @Test
    void unsynced_cheapshark_blocks_are_filtered_out() {
        BuildResult r = new PopularesBuilder(11).build(new SectionContext(List.of(
                popularGame("a", "A", 100, Map.of("owned", 50), 0, 0, SAVINGS_80, false),
                popularGame("b", "B", 100, Map.of("owned", 50), 0, 0, SAVINGS_80))));
        assertThat(r.totalCandidates()).isEqualTo(1);
        assertThat(r.items().get(0).slug()).isEqualTo("b");
    }

    @Test
    void games_with_null_best_deal_are_filtered_out() {
        BuildResult r = new PopularesBuilder(11).build(new SectionContext(List.of(
                gameNoBestDeal("a", "A"),
                popularGame("b", "B", 100, Map.of("owned", 50), 0, 0, SAVINGS_80))));
        assertThat(r.totalCandidates()).isEqualTo(1);
        assertThat(r.items().get(0).slug()).isEqualTo("b");
    }

    // -------- filter: savings threshold -----------------------------------

    @Test
    void games_with_savings_below_threshold_are_filtered_out() {
        BuildResult r = new PopularesBuilder(11).build(new SectionContext(List.of(
                popularGame("a", "A", 100, Map.of("owned", 50), 0, 0, SAVINGS_BELOW_50),
                popularGame("b", "B", 100, Map.of("owned", 50), 0, 0, SAVINGS_80))));
        assertThat(r.totalCandidates()).isEqualTo(1);
        assertThat(r.items().get(0).slug()).isEqualTo("b");
    }

    @Test
    void games_with_savings_exactly_at_threshold_are_accepted() {
        BuildResult r = new PopularesBuilder(11).build(new SectionContext(List.of(
                popularGame("a", "A", 100, Map.of("owned", 50), 0, 0, SAVINGS_50))));
        assertThat(r.totalCandidates()).isEqualTo(1);
        assertThat(r.items().get(0).slug()).isEqualTo("a");
    }

    // -------- filter: rawg / engagement -------------------------------------

    @Test
    void games_with_null_rawg_view_are_filtered_out() {
        BuildResult r = new PopularesBuilder(11).build(new SectionContext(List.of(
                new GameView("a", "A", cheapshark(SAVINGS_80), null),
                popularGame("b", "B", 100, Map.of("owned", 50), 0, 0, SAVINGS_80))));
        assertThat(r.totalCandidates()).isEqualTo(1);
        assertThat(r.items().get(0).slug()).isEqualTo("b");
    }

    @Test
    void games_with_no_engagement_signals_are_filtered_out() {
        BuildResult r = new PopularesBuilder(11).build(new SectionContext(List.of(
                popularGame("a", "A", null, null, null, null, SAVINGS_80),
                popularGame("b", "B", 100, Map.of("owned", 50), 0, 0, SAVINGS_80))));
        assertThat(r.totalCandidates()).isEqualTo(1);
        assertThat(r.items().get(0).slug()).isEqualTo("b");
    }

    @Test
    void games_with_only_negative_or_interest_status_are_filtered_out() {
        BuildResult r = new PopularesBuilder(11).build(new SectionContext(List.of(
                popularGame("a", "A", null, Map.of("yet", 999, "dropped", 999), null, null, SAVINGS_80),
                popularGame("b", "B", 100, Map.of("owned", 50), 0, 0, SAVINGS_80))));
        assertThat(r.totalCandidates()).isEqualTo(1);
        assertThat(r.items().get(0).slug()).isEqualTo("b");
    }

    // -------- score: multiplicative -----------------------------------------

    @Test
    void score_is_pop_times_savings() {
        BuildResult r = new PopularesBuilder(11).build(new SectionContext(List.of(
                popularGame("a", "A", 100, Map.of("owned", 50), 0, 0, SAVINGS_80))));
        assertThat(r.items().get(0).score()).isEqualByComparingTo("20000");
    }

    @Test
    void score_uses_only_owned_beaten_playing_from_addedByStatus() {
        BuildResult r = new PopularesBuilder(11).build(new SectionContext(List.of(
                popularGame("a", "A", 100,
                        Map.of("owned", 1000, "beaten", 500, "playing", 100,
                                "yet", 999, "dropped", 999),
                        0, 0, SAVINGS_50))));
        assertThat(r.items().get(0).score()).isEqualByComparingTo("90000");
    }

    @Test
    void ratings_count_is_weighted_double() {
        BuildResult r = new PopularesBuilder(11).build(new SectionContext(List.of(
                popularGame("a", "A", 100, Map.of(), 0, 0, SAVINGS_50),
                popularGame("b", "B", 50, Map.of(), 0, 0, SAVINGS_50))));
        assertThat(r.items().get(0).slug()).isEqualTo("a");
        assertThat(r.items().get(1).slug()).isEqualTo("b");
    }

    @Test
    void mid_popular_with_huge_deal_can_beat_mega_popular_with_just_above_threshold_deal() {
        BuildResult r = new PopularesBuilder(11).build(new SectionContext(List.of(
                popularGame("mega", "Mega", 150, Map.of(), 0, 0, SAVINGS_50),
                popularGame("mid", "Mid", 100, Map.of(), 0, 0, SAVINGS_80))));
        assertThat(r.items().get(0).slug()).isEqualTo("mid");
        assertThat(r.items().get(1).slug()).isEqualTo("mega");
    }

    // -------- ordering / limit ----------------------------------------------

    @Test
    void eligible_games_are_sorted_by_score_descending() {
        BuildResult r = new PopularesBuilder(11).build(new SectionContext(List.of(
                popularGame("a", "A", 50, Map.of("owned", 50), 0, 0, SAVINGS_50),
                popularGame("b", "B", 200, Map.of("owned", 50), 0, 0, SAVINGS_50),
                popularGame("c", "C", 100, Map.of("owned", 50), 0, 0, SAVINGS_80))));
        assertThat(r.totalCandidates()).isEqualTo(3);
        assertThat(r.items()).extracting(SectionItem::slug)
                .containsExactly("b", "c", "a");
    }

    @Test
    void top_n_is_capped_at_max_items() {
        List<GameView> twelve = List.of(
                popularGame("a", "A", 10, Map.of(), 0, 0, SAVINGS_50),
                popularGame("b", "B", 20, Map.of(), 0, 0, SAVINGS_50),
                popularGame("c", "C", 30, Map.of(), 0, 0, SAVINGS_50),
                popularGame("d", "D", 40, Map.of(), 0, 0, SAVINGS_50),
                popularGame("e", "E", 50, Map.of(), 0, 0, SAVINGS_50),
                popularGame("f", "F", 60, Map.of(), 0, 0, SAVINGS_50),
                popularGame("g", "G", 70, Map.of(), 0, 0, SAVINGS_50),
                popularGame("h", "H", 80, Map.of(), 0, 0, SAVINGS_50),
                popularGame("i", "I", 90, Map.of(), 0, 0, SAVINGS_50),
                popularGame("j", "J", 100, Map.of(), 0, 0, SAVINGS_50),
                popularGame("k", "K", 110, Map.of(), 0, 0, SAVINGS_50),
                popularGame("l", "L", 120, Map.of(), 0, 0, SAVINGS_50));
        BuildResult r = new PopularesBuilder(5).build(new SectionContext(twelve));
        assertThat(r.totalCandidates()).isEqualTo(12);
        assertThat(r.items()).extracting(SectionItem::slug)
                .containsExactly("l", "k", "j", "i", "h");
    }

    @Test
    void ties_keep_catalog_order() {
        BuildResult r = new PopularesBuilder(11).build(new SectionContext(List.of(
                popularGame("first", "F", 100, Map.of(), 0, 0, SAVINGS_50),
                popularGame("second", "S", 100, Map.of(), 0, 0, SAVINGS_50),
                popularGame("third", "T", 100, Map.of(), 0, 0, SAVINGS_50))));
        assertThat(r.items()).extracting(SectionItem::slug)
                .containsExactly("first", "second", "third");
    }

    // -------- extras --------------------------------------------------------

    @Test
    void item_exposes_ratings_in_extra() {
        BuildResult r = new PopularesBuilder(11).build(new SectionContext(List.of(
                popularGame("a", "A", 100, Map.of(), 0, 0, SAVINGS_80))));
        assertThat(r.items().get(0).extra()).containsEntry("ratings", "100");
    }

    @Test
    void item_exposes_owned_in_extra() {
        BuildResult r = new PopularesBuilder(11).build(new SectionContext(List.of(
                popularGame("a", "A", 0, Map.of("owned", 500), 0, 0, SAVINGS_80))));
        assertThat(r.items().get(0).extra()).containsEntry("owned", "500");
    }

    @Test
    void item_exposes_beaten_in_extra() {
        BuildResult r = new PopularesBuilder(11).build(new SectionContext(List.of(
                popularGame("a", "A", 0, Map.of("beaten", 250), 0, 0, SAVINGS_80))));
        assertThat(r.items().get(0).extra()).containsEntry("beaten", "250");
    }

    @Test
    void item_exposes_additions_in_extra() {
        BuildResult r = new PopularesBuilder(11).build(new SectionContext(List.of(
                popularGame("a", "A", 0, Map.of(), 5, 0, SAVINGS_80))));
        assertThat(r.items().get(0).extra()).containsEntry("additions", "5");
    }

    @Test
    void item_exposes_savings_pct_in_extra() {
        BuildResult r = new PopularesBuilder(11).build(new SectionContext(List.of(
                popularGame("a", "A", 100, Map.of(), 0, 0, SAVINGS_80))));
        assertThat(r.items().get(0).extra()).containsEntry("savingsPct", "80");
    }

    @Test
    void item_extra_uses_zero_for_missing_engagement_fields() {
        BuildResult r = new PopularesBuilder(11).build(new SectionContext(List.of(
                popularGame("a", "A", null, null, null, 5, SAVINGS_80))));
        assertThat(r.items().get(0).extra())
                .containsEntry("ratings", "0")
                .containsEntry("owned", "0")
                .containsEntry("beaten", "0")
                .containsEntry("additions", "0");
    }

    // -------- helpers --------------------------------------------------------

    private static GameView popularGame(String slug, String title,
            Integer ratingsCount, Map<String, Integer> addedByStatus,
            Integer additionsCount, Integer suggestionsCount,
            BigDecimal savings) {
        return popularGame(slug, title, ratingsCount, addedByStatus,
                additionsCount, suggestionsCount, savings, true);
    }

    private static GameView popularGame(String slug, String title,
            Integer ratingsCount, Map<String, Integer> addedByStatus,
            Integer additionsCount, Integer suggestionsCount,
            BigDecimal savings, boolean cheapSynced) {
        Offer offer = new Offer("1", "Steam", null,
                BigDecimal.TEN, RETAIL, savings, null, null);
        return new GameView(slug, title,
                new CheapsharkView(cheapSynced, offer, savings, null, List.of()),
                new RawgView(null, null, null, ratingsCount, additionsCount,
                        addedByStatus, null, suggestionsCount));
    }

    private static GameView gameNoBestDeal(String slug, String title) {
        return new GameView(slug, title,
                new CheapsharkView(true, null, null, null, List.of()),
                new RawgView(null, null, null, 100, 0,
                        Map.of("owned", 50), null, 0));
    }

    private static CheapsharkView cheapshark(BigDecimal savings) {
        Offer offer = new Offer("1", "Steam", null, BigDecimal.TEN, RETAIL, savings, null, null);
        return new CheapsharkView(true, offer, savings, null, List.of());
    }
}
