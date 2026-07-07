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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NuevasOfertasBuilderTest {

    private static final Instant NOW = Instant.parse("2026-07-07T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final BigDecimal SAVINGS_50 = new BigDecimal("50");
    private static final BigDecimal SAVINGS_80 = new BigDecimal("80");
    private static final BigDecimal RETAIL = new BigDecimal("39.99");

    // -------- construction / contract ---------------------------------------

    @Test
    void name_is_nuevas_ofertas() {
        assertThat(new NuevasOfertasBuilder(8, 2, CLOCK).name())
                .isEqualTo(SectionName.NUEVAS_OFERTAS);
    }

    @Test
    void rejects_zero_max_items() {
        assertThatThrownBy(() -> new NuevasOfertasBuilder(0, 2, CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxItems");
    }

    @Test
    void rejects_negative_max_items() {
        assertThatThrownBy(() -> new NuevasOfertasBuilder(-1, 2, CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxItems");
    }

    @Test
    void rejects_zero_window_days() {
        assertThatThrownBy(() -> new NuevasOfertasBuilder(8, 0, CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("windowDays");
    }

    @Test
    void rejects_negative_window_days() {
        assertThatThrownBy(() -> new NuevasOfertasBuilder(8, -1, CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("windowDays");
    }

    @Test
    void rejects_null_clock() {
        assertThatThrownBy(() -> new NuevasOfertasBuilder(8, 2, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("clock");
    }

    @Test
    void build_rejects_null_context() {
        assertThatThrownBy(() -> new NuevasOfertasBuilder(8, 2, CLOCK).build(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ctx");
    }

    // -------- build / empty -------------------------------------------------

    @Test
    void empty_catalog_yields_empty_result() {
        BuildResult r = new NuevasOfertasBuilder(8, 2, CLOCK).build(SectionContext.empty());
        assertThat(r.items()).isEmpty();
        assertThat(r.totalCandidates()).isZero();
    }

    // -------- filter: cheapshark -------------------------------------------

    @Test
    void games_with_null_cheapshark_view_are_filtered_out() {
        BuildResult r = new NuevasOfertasBuilder(8, 2, CLOCK).build(new SectionContext(List.of(
                new GameView("a", "A", null, null),
                newOffer("b", "B", NOW, 100, Map.of("owned", 50), 0, 0, SAVINGS_80))));
        assertThat(r.totalCandidates()).isEqualTo(1);
        assertThat(r.items().get(0).slug()).isEqualTo("b");
    }

    @Test
    void unsynced_cheapshark_blocks_are_filtered_out() {
        BuildResult r = new NuevasOfertasBuilder(8, 2, CLOCK).build(new SectionContext(List.of(
                newOffer("a", "A", NOW, 100, Map.of("owned", 50), 0, 0, SAVINGS_80, false),
                newOffer("b", "B", NOW, 100, Map.of("owned", 50), 0, 0, SAVINGS_80))));
        assertThat(r.totalCandidates()).isEqualTo(1);
        assertThat(r.items().get(0).slug()).isEqualTo("b");
    }

    @Test
    void games_with_null_best_deal_are_filtered_out() {
        BuildResult r = new NuevasOfertasBuilder(8, 2, CLOCK).build(new SectionContext(List.of(
                gameNoBestDeal("a", "A"),
                newOffer("b", "B", NOW, 100, Map.of("owned", 50), 0, 0, SAVINGS_80))));
        assertThat(r.totalCandidates()).isEqualTo(1);
        assertThat(r.items().get(0).slug()).isEqualTo("b");
    }

    // -------- filter: firstSeenAt window -----------------------------------

    @Test
    void games_with_null_first_seen_at_are_filtered_out() {
        BuildResult r = new NuevasOfertasBuilder(8, 2, CLOCK).build(new SectionContext(List.of(
                newOfferNoFirstSeen("a", "A"),
                newOffer("b", "B", NOW, 100, Map.of("owned", 50), 0, 0, SAVINGS_80))));
        assertThat(r.totalCandidates()).isEqualTo(1);
        assertThat(r.items().get(0).slug()).isEqualTo("b");
    }

    @Test
    void games_with_first_seen_at_before_window_are_filtered_out() {
        BuildResult r = new NuevasOfertasBuilder(8, 2, CLOCK).build(new SectionContext(List.of(
                newOffer("a", "A", NOW.minusSeconds(3 * 86400), 100, Map.of("owned", 50), 0, 0, SAVINGS_80),
                newOffer("b", "B", NOW, 100, Map.of("owned", 50), 0, 0, SAVINGS_80))));
        assertThat(r.totalCandidates()).isEqualTo(1);
        assertThat(r.items().get(0).slug()).isEqualTo("b");
    }

    @Test
    void games_with_first_seen_at_exactly_at_cutoff_are_accepted() {
        Instant cutoff = NOW.minusSeconds(2 * 86400);
        BuildResult r = new NuevasOfertasBuilder(8, 2, CLOCK).build(new SectionContext(List.of(
                newOffer("a", "A", cutoff, 100, Map.of("owned", 50), 0, 0, SAVINGS_80))));
        assertThat(r.totalCandidates()).isEqualTo(1);
        assertThat(r.items().get(0).slug()).isEqualTo("a");
    }

    // -------- filter: rawg / engagement -------------------------------------

    @Test
    void games_with_null_rawg_view_are_filtered_out() {
        BuildResult r = new NuevasOfertasBuilder(8, 2, CLOCK).build(new SectionContext(List.of(
                new GameView("a", "A", cheapshark(NOW, SAVINGS_80), null),
                newOffer("b", "B", NOW, 100, Map.of("owned", 50), 0, 0, SAVINGS_80))));
        assertThat(r.totalCandidates()).isEqualTo(1);
        assertThat(r.items().get(0).slug()).isEqualTo("b");
    }

    @Test
    void games_with_no_engagement_signals_are_filtered_out() {
        BuildResult r = new NuevasOfertasBuilder(8, 2, CLOCK).build(new SectionContext(List.of(
                newOffer("a", "A", NOW, null, null, null, null, SAVINGS_80),
                newOffer("b", "B", NOW, 100, Map.of("owned", 50), 0, 0, SAVINGS_80))));
        assertThat(r.totalCandidates()).isEqualTo(1);
        assertThat(r.items().get(0).slug()).isEqualTo("b");
    }

    // -------- score: multiplicative -----------------------------------------

    @Test
    void score_is_pop_times_savings() {
        BuildResult r = new NuevasOfertasBuilder(8, 2, CLOCK).build(new SectionContext(List.of(
                newOffer("a", "A", NOW, 100, Map.of("owned", 50), 0, 0, SAVINGS_80))));
        assertThat(r.items().get(0).score()).isEqualByComparingTo("20000");
    }

    @Test
    void score_uses_only_owned_beaten_playing_from_addedByStatus() {
        BuildResult r = new NuevasOfertasBuilder(8, 2, CLOCK).build(new SectionContext(List.of(
                newOffer("a", "A", NOW, 100,
                        Map.of("owned", 1000, "beaten", 500, "playing", 100,
                                "yet", 999, "dropped", 999),
                        0, 0, SAVINGS_50))));
        assertThat(r.items().get(0).score()).isEqualByComparingTo("90000");
    }

    @Test
    void ratings_count_is_weighted_double() {
        BuildResult r = new NuevasOfertasBuilder(8, 2, CLOCK).build(new SectionContext(List.of(
                newOffer("a", "A", NOW, 100, Map.of(), 0, 0, SAVINGS_50),
                newOffer("b", "B", NOW, 50, Map.of(), 0, 0, SAVINGS_50))));
        assertThat(r.items().get(0).slug()).isEqualTo("a");
        assertThat(r.items().get(1).slug()).isEqualTo("b");
    }

    @Test
    void mid_popular_with_huge_deal_can_beat_mega_popular_with_just_above_threshold_deal() {
        BuildResult r = new NuevasOfertasBuilder(8, 2, CLOCK).build(new SectionContext(List.of(
                newOffer("mega", "Mega", NOW, 150, Map.of(), 0, 0, SAVINGS_50),
                newOffer("mid", "Mid", NOW, 100, Map.of(), 0, 0, SAVINGS_80))));
        assertThat(r.items().get(0).slug()).isEqualTo("mid");
        assertThat(r.items().get(1).slug()).isEqualTo("mega");
    }

    // -------- ordering / limit ----------------------------------------------

    @Test
    void eligible_games_are_sorted_by_score_descending() {
        BuildResult r = new NuevasOfertasBuilder(8, 2, CLOCK).build(new SectionContext(List.of(
                newOffer("a", "A", NOW, 50, Map.of("owned", 50), 0, 0, SAVINGS_50),
                newOffer("b", "B", NOW, 200, Map.of("owned", 50), 0, 0, SAVINGS_50),
                newOffer("c", "C", NOW, 100, Map.of("owned", 50), 0, 0, SAVINGS_80))));
        assertThat(r.totalCandidates()).isEqualTo(3);
        assertThat(r.items()).extracting(SectionItem::slug)
                .containsExactly("b", "c", "a");
    }

    @Test
    void top_n_is_capped_at_max_items() {
        List<GameView> twelve = List.of(
                newOffer("a", "A", NOW, 10, Map.of(), 0, 0, SAVINGS_50),
                newOffer("b", "B", NOW, 20, Map.of(), 0, 0, SAVINGS_50),
                newOffer("c", "C", NOW, 30, Map.of(), 0, 0, SAVINGS_50),
                newOffer("d", "D", NOW, 40, Map.of(), 0, 0, SAVINGS_50),
                newOffer("e", "E", NOW, 50, Map.of(), 0, 0, SAVINGS_50),
                newOffer("f", "F", NOW, 60, Map.of(), 0, 0, SAVINGS_50),
                newOffer("g", "G", NOW, 70, Map.of(), 0, 0, SAVINGS_50),
                newOffer("h", "H", NOW, 80, Map.of(), 0, 0, SAVINGS_50),
                newOffer("i", "I", NOW, 90, Map.of(), 0, 0, SAVINGS_50),
                newOffer("j", "J", NOW, 100, Map.of(), 0, 0, SAVINGS_50),
                newOffer("k", "K", NOW, 110, Map.of(), 0, 0, SAVINGS_50),
                newOffer("l", "L", NOW, 120, Map.of(), 0, 0, SAVINGS_50));
        BuildResult r = new NuevasOfertasBuilder(5, 2, CLOCK).build(new SectionContext(twelve));
        assertThat(r.totalCandidates()).isEqualTo(12);
        assertThat(r.items()).extracting(SectionItem::slug)
                .containsExactly("l", "k", "j", "i", "h");
    }

    @Test
    void ties_keep_catalog_order() {
        BuildResult r = new NuevasOfertasBuilder(8, 2, CLOCK).build(new SectionContext(List.of(
                newOffer("first", "F", NOW, 100, Map.of(), 0, 0, SAVINGS_50),
                newOffer("second", "S", NOW, 100, Map.of(), 0, 0, SAVINGS_50),
                newOffer("third", "T", NOW, 100, Map.of(), 0, 0, SAVINGS_50))));
        assertThat(r.items()).extracting(SectionItem::slug)
                .containsExactly("first", "second", "third");
    }

    // -------- extras --------------------------------------------------------

    @Test
    void item_exposes_first_seen_at_in_extra() {
        BuildResult r = new NuevasOfertasBuilder(8, 2, CLOCK).build(new SectionContext(List.of(
                newOffer("a", "A", NOW, 100, Map.of(), 0, 0, SAVINGS_80))));
        assertThat(r.items().get(0).extra()).containsEntry("firstSeenAt", NOW.toString());
    }

    @Test
    void item_exposes_savings_pct_in_extra() {
        BuildResult r = new NuevasOfertasBuilder(8, 2, CLOCK).build(new SectionContext(List.of(
                newOffer("a", "A", NOW, 100, Map.of(), 0, 0, SAVINGS_80))));
        assertThat(r.items().get(0).extra()).containsEntry("savingsPct", "80");
    }

    @Test
    void item_extra_uses_zero_for_missing_engagement_fields() {
        BuildResult r = new NuevasOfertasBuilder(8, 2, CLOCK).build(new SectionContext(List.of(
                newOffer("a", "A", NOW, null, null, null, 5, SAVINGS_80))));
        assertThat(r.items().get(0).extra())
                .containsEntry("ratings", "0")
                .containsEntry("owned", "0")
                .containsEntry("beaten", "0")
                .containsEntry("additions", "0");
    }

    // -------- helpers --------------------------------------------------------

    private static GameView newOffer(String slug, String title, Instant firstSeenAt,
            Integer ratingsCount, Map<String, Integer> addedByStatus,
            Integer additionsCount, Integer suggestionsCount, BigDecimal savings) {
        return newOffer(slug, title, firstSeenAt, ratingsCount, addedByStatus,
                additionsCount, suggestionsCount, savings, true);
    }

    private static GameView newOffer(String slug, String title, Instant firstSeenAt,
            Integer ratingsCount, Map<String, Integer> addedByStatus,
            Integer additionsCount, Integer suggestionsCount, BigDecimal savings,
            boolean cheapSynced) {
        Offer offer = new Offer("1", "Steam", null,
                BigDecimal.TEN, RETAIL, savings, null, firstSeenAt);
        return new GameView(slug, title,
                new CheapsharkView(cheapSynced, offer, savings, null, List.of()),
                new RawgView(null, null, null, ratingsCount, additionsCount,
                        addedByStatus, null, suggestionsCount));
    }

    private static GameView newOfferNoFirstSeen(String slug, String title) {
        Offer offer = new Offer("1", "Steam", null,
                BigDecimal.TEN, RETAIL, SAVINGS_80, null, null);
        return new GameView(slug, title,
                new CheapsharkView(true, offer, SAVINGS_80, null, List.of()),
                new RawgView(null, null, null, 100, 0, Map.of("owned", 50), null, 0));
    }

    private static GameView gameNoBestDeal(String slug, String title) {
        return new GameView(slug, title,
                new CheapsharkView(true, null, null, null, List.of()),
                new RawgView(null, null, null, 100, 0, Map.of("owned", 50), null, 0));
    }

    private static CheapsharkView cheapshark(Instant firstSeenAt, BigDecimal savings) {
        Offer offer = new Offer("1", "Steam", null,
                BigDecimal.TEN, RETAIL, savings, null, firstSeenAt);
        return new CheapsharkView(true, offer, savings, null, List.of());
    }
}
