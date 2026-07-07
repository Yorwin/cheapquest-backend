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
import org.junit.jupiter.api.Test;

class BajosHistoricosBuilderTest {

    private static final BigDecimal LOW = new BigDecimal("9.99");
    private static final BigDecimal ABOVE_LOW = new BigDecimal("19.99");
    private static final BigDecimal RETAIL = new BigDecimal("39.99");

    // -------- construction / contract ---------------------------------------

    @Test
    void name_is_bajos_historicos() {
        assertThat(new BajosHistoricosBuilder(5).name())
                .isEqualTo(SectionName.BAJOS_HISTORICOS);
    }

    @Test
    void rejects_zero_max_items() {
        assertThatThrownBy(() -> new BajosHistoricosBuilder(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxItems");
    }

    @Test
    void rejects_negative_max_items() {
        assertThatThrownBy(() -> new BajosHistoricosBuilder(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxItems");
    }

    @Test
    void build_rejects_null_context() {
        assertThatThrownBy(() -> new BajosHistoricosBuilder(5).build(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ctx");
    }

    // -------- build / empty -------------------------------------------------

    @Test
    void empty_catalog_yields_empty_result() {
        BuildResult r = new BajosHistoricosBuilder(5).build(SectionContext.empty());
        assertThat(r.items()).isEmpty();
        assertThat(r.totalCandidates()).isZero();
    }

    // -------- filter: cheapshark -------------------------------------------

    @Test
    void games_with_null_cheapshark_view_are_filtered_out() {
        BuildResult r = new BajosHistoricosBuilder(5).build(new SectionContext(List.of(
                new GameView("a", "A", null, null),
                gameAtLow("b", "B", LOW, LOW, 100))));
        assertThat(r.totalCandidates()).isEqualTo(1);
        assertThat(r.items().get(0).slug()).isEqualTo("b");
    }

    @Test
    void unsynced_cheapshark_blocks_are_filtered_out() {
        BuildResult r = new BajosHistoricosBuilder(5).build(new SectionContext(List.of(
                gameUnsynced("a", "A"),
                gameAtLow("b", "B", LOW, LOW, 100))));
        assertThat(r.totalCandidates()).isEqualTo(1);
        assertThat(r.items().get(0).slug()).isEqualTo("b");
    }

    @Test
    void games_with_null_best_deal_are_filtered_out() {
        BuildResult r = new BajosHistoricosBuilder(5).build(new SectionContext(List.of(
                gameNoBestDeal("a", "A"),
                gameAtLow("b", "B", LOW, LOW, 100))));
        assertThat(r.totalCandidates()).isEqualTo(1);
        assertThat(r.items().get(0).slug()).isEqualTo("b");
    }

    // -------- filter: cheapestEver / price sanity ---------------------------

    @Test
    void games_with_null_cheapest_ever_are_filtered_out() {
        BuildResult r = new BajosHistoricosBuilder(5).build(new SectionContext(List.of(
                gameNoCheapestEver("a", "A"),
                gameAtLow("b", "B", LOW, LOW, 100))));
        assertThat(r.totalCandidates()).isEqualTo(1);
        assertThat(r.items().get(0).slug()).isEqualTo("b");
    }

    @Test
    void games_with_zero_cheapest_ever_are_filtered_out() {
        BuildResult r = new BajosHistoricosBuilder(5).build(new SectionContext(List.of(
                gameAtLowRaw("a", "A", BigDecimal.ZERO, LOW, 100),
                gameAtLow("b", "B", LOW, LOW, 100))));
        assertThat(r.totalCandidates()).isEqualTo(1);
        assertThat(r.items().get(0).slug()).isEqualTo("b");
    }

    @Test
    void games_with_zero_best_price_are_filtered_out() {
        BuildResult r = new BajosHistoricosBuilder(5).build(new SectionContext(List.of(
                gameAtLowRaw("a", "A", LOW, BigDecimal.ZERO, 100),
                gameAtLow("b", "B", LOW, LOW, 100))));
        assertThat(r.totalCandidates()).isEqualTo(1);
        assertThat(r.items().get(0).slug()).isEqualTo("b");
    }

    @Test
    void games_with_price_above_low_are_filtered_out() {
        BuildResult r = new BajosHistoricosBuilder(5).build(new SectionContext(List.of(
                gameAtLowRaw("a", "A", LOW, ABOVE_LOW, 100),
                gameAtLow("b", "B", LOW, LOW, 100))));
        assertThat(r.totalCandidates()).isEqualTo(1);
        assertThat(r.items().get(0).slug()).isEqualTo("b");
    }

    @Test
    void games_with_price_strictly_below_low_are_accepted() {
        BuildResult r = new BajosHistoricosBuilder(5).build(new SectionContext(List.of(
                gameAtLowRaw("a", "A", LOW, new BigDecimal("4.99"), 100))));
        assertThat(r.totalCandidates()).isEqualTo(1);
        assertThat(r.items().get(0).slug()).isEqualTo("a");
    }

    // -------- filter: rawg --------------------------------------------------

    @Test
    void games_with_null_rawg_view_are_filtered_out() {
        BuildResult r = new BajosHistoricosBuilder(5).build(new SectionContext(List.of(
                new GameView("a", "A",
                        new CheapsharkView(true, offerAt(LOW), LOW, null, List.of()),
                        null),
                gameAtLow("b", "B", LOW, LOW, 100))));
        assertThat(r.totalCandidates()).isEqualTo(1);
        assertThat(r.items().get(0).slug()).isEqualTo("b");
    }

    @Test
    void games_with_null_ratings_count_are_filtered_out() {
        BuildResult r = new BajosHistoricosBuilder(5).build(new SectionContext(List.of(
                gameNoRatingsCount("a", "A"),
                gameAtLow("b", "B", LOW, LOW, 100))));
        assertThat(r.totalCandidates()).isEqualTo(1);
        assertThat(r.items().get(0).slug()).isEqualTo("b");
    }

    // -------- score / sort / limit ------------------------------------------

    @Test
    void zero_ratings_count_is_accepted_with_zero_score() {
        BuildResult r = new BajosHistoricosBuilder(5).build(new SectionContext(List.of(
                gameAtLow("a", "A", LOW, LOW, 0))));
        assertThat(r.totalCandidates()).isEqualTo(1);
        assertThat(r.items().get(0).score()).isEqualByComparingTo("0");
    }

    @Test
    void eligible_games_are_sorted_by_score_descending() {
        BuildResult r = new BajosHistoricosBuilder(5).build(new SectionContext(List.of(
                gameAtLow("a", "A", LOW, LOW, 100),
                gameAtLow("b", "B", LOW, LOW, 500),
                gameAtLow("c", "C", LOW, LOW, 250))));
        assertThat(r.totalCandidates()).isEqualTo(3);
        assertThat(r.items()).extracting(SectionItem::slug)
                .containsExactly("b", "c", "a");
    }

    @Test
    void top_n_is_capped_at_max_items() {
        List<GameView> six = List.of(
                gameAtLow("a", "A", LOW, LOW, 100),
                gameAtLow("b", "B", LOW, LOW, 200),
                gameAtLow("c", "C", LOW, LOW, 300),
                gameAtLow("d", "D", LOW, LOW, 400),
                gameAtLow("e", "E", LOW, LOW, 500),
                gameAtLow("f", "F", LOW, LOW, 600));
        BuildResult r = new BajosHistoricosBuilder(5).build(new SectionContext(six));
        assertThat(r.totalCandidates()).isEqualTo(6);
        assertThat(r.items()).extracting(SectionItem::slug)
                .containsExactly("f", "e", "d", "c", "b");
    }

    @Test
    void ties_keep_catalog_order() {
        List<GameView> tied = List.of(
                gameAtLow("first", "F", LOW, LOW, 100),
                gameAtLow("second", "S", LOW, LOW, 100),
                gameAtLow("third", "T", LOW, LOW, 100));
        BuildResult r = new BajosHistoricosBuilder(5).build(new SectionContext(tied));
        assertThat(r.items()).extracting(SectionItem::slug)
                .containsExactly("first", "second", "third");
    }

    // -------- extras --------------------------------------------------------

    @Test
    void item_exposes_cheapest_ever_in_extra() {
        BuildResult r = new BajosHistoricosBuilder(5).build(new SectionContext(List.of(
                gameAtLow("a", "A", LOW, LOW, 100))));
        assertThat(r.items().get(0).extra()).containsEntry("cheapestEver", "9.99");
    }

    @Test
    void item_exposes_current_price_in_extra() {
        BuildResult r = new BajosHistoricosBuilder(5).build(new SectionContext(List.of(
                gameAtLow("a", "A", LOW, LOW, 100))));
        assertThat(r.items().get(0).extra()).containsEntry("currentPrice", "9.99");
    }

    @Test
    void item_exposes_markup_pct_as_zero_when_at_low() {
        BuildResult r = new BajosHistoricosBuilder(5).build(new SectionContext(List.of(
                gameAtLow("a", "A", LOW, LOW, 100),
                gameAtLowRaw("b", "B", LOW, new BigDecimal("4.99"), 100))));
        assertThat(r.items().get(0).extra()).containsEntry("markupPct", "0.00");
        assertThat(r.items().get(1).extra()).containsEntry("markupPct", "0.00");
    }

    // -------- helpers --------------------------------------------------------

    private static GameView gameAtLow(String slug, String title,
            BigDecimal cheapestEver, BigDecimal bestPrice, int ratingsCount) {
        return gameAtLowRaw(slug, title, cheapestEver, bestPrice, ratingsCount);
    }

    private static GameView gameAtLowRaw(String slug, String title,
            BigDecimal cheapestEver, BigDecimal bestPrice, int ratingsCount) {
        return new GameView(slug, title,
                new CheapsharkView(true, offerAt(bestPrice), cheapestEver, null, List.of()),
                new RawgView(null, null, null, ratingsCount, null, null, null, null));
    }

    private static GameView gameUnsynced(String slug, String title) {
        return new GameView(slug, title,
                new CheapsharkView(false, offerAt(LOW), LOW, null, List.of()),
                rawgWith(100));
    }

    private static GameView gameNoBestDeal(String slug, String title) {
        return new GameView(slug, title,
                new CheapsharkView(true, null, LOW, null, List.of()),
                rawgWith(100));
    }

    private static GameView gameNoCheapestEver(String slug, String title) {
        return new GameView(slug, title,
                new CheapsharkView(true, offerAt(LOW), null, null, List.of()),
                rawgWith(100));
    }

    private static GameView gameNoRatingsCount(String slug, String title) {
        return new GameView(slug, title,
                new CheapsharkView(true, offerAt(LOW), LOW, null, List.of()),
                new RawgView(null, null, null, null, null, null, null, null));
    }

    private static Offer offerAt(BigDecimal price) {
        return new Offer("1", "Steam", null, price, RETAIL, new BigDecimal("50"), null, null);
    }

    private static RawgView rawgWith(int ratingsCount) {
        return new RawgView(null, null, null, ratingsCount, null, null, null, null);
    }
}
