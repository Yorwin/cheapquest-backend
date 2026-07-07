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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class VintageBuilderTest {

    private static final Instant T = Instant.parse("2026-07-07T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-07-07");
    private static final Clock CLOCK = Clock.fixed(T, ZoneOffset.UTC);

    private static final Offer BEST_DEAL = new Offer(
            "1", "Steam", null,
            BigDecimal.TEN, BigDecimal.valueOf(20),
            BigDecimal.valueOf(50), null, null);

    // -------- construction / contract ---------------------------------------

    @Test
    void name_is_vintage() {
        assertThat(new VintageBuilder(5, CLOCK).name())
                .isEqualTo(SectionName.VINTAGE);
    }

    @Test
    void rejects_zero_max_items() {
        assertThatThrownBy(() -> new VintageBuilder(0, CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxItems");
    }

    @Test
    void rejects_negative_max_items() {
        assertThatThrownBy(() -> new VintageBuilder(-1, CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxItems");
    }

    @Test
    void rejects_null_clock() {
        assertThatThrownBy(() -> new VintageBuilder(5, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("clock");
    }

    @Test
    void build_rejects_null_context() {
        assertThatThrownBy(() -> new VintageBuilder(5, CLOCK).build(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ctx");
    }

    // -------- build ---------------------------------------------------------

    @Test
    void empty_catalog_yields_empty_result() {
        BuildResult r = new VintageBuilder(5, CLOCK).build(SectionContext.empty());
        assertThat(r.items()).isEmpty();
        assertThat(r.totalCandidates()).isZero();
    }

    @Test
    void games_with_null_rawg_view_are_filtered_out() {
        BuildResult r = new VintageBuilder(5, CLOCK).build(new SectionContext(List.of(
                new GameView("a", "A", null, null, null),
                vintageGame("b", "B", "2014-01-01", 90, null))));
        assertThat(r.totalCandidates()).isEqualTo(1);
        assertThat(r.items().get(0).slug()).isEqualTo("b");
    }

    @Test
    void games_with_null_or_blank_released_are_filtered_out() {
        BuildResult r = new VintageBuilder(5, CLOCK).build(new SectionContext(List.of(
                vintageGame("a", "A", null, 90, null),
                vintageGame("b", "B", "", 90, null),
                vintageGame("c", "C", "   ", 90, null),
                vintageGame("d", "D", "2014-01-01", 90, null))));
        assertThat(r.totalCandidates()).isEqualTo(1);
        assertThat(r.items().get(0).slug()).isEqualTo("d");
    }

    @Test
    void games_with_unparseable_released_are_filtered_out() {
        BuildResult r = new VintageBuilder(5, CLOCK).build(new SectionContext(List.of(
                vintageGame("a", "A", "not-a-date", 90, null),
                vintageGame("b", "B", "2014-99-99", 90, null),
                vintageGame("c", "C", "2014-01-01", 90, null))));
        assertThat(r.totalCandidates()).isEqualTo(1);
        assertThat(r.items().get(0).slug()).isEqualTo("c");
    }

    @Test
    void games_younger_than_min_age_are_filtered_out() {
        BuildResult r = new VintageBuilder(5, CLOCK).build(new SectionContext(List.of(
                vintageGame("recent", "R", "2020-01-01", 90, null),
                vintageGame("borderline", "BL", "2018-07-08", 90, null),
                vintageGame("exact", "E", "2018-07-07", 90, null),
                vintageGame("old", "O", "2010-01-01", 90, null))));
        assertThat(r.totalCandidates()).isEqualTo(2);
        assertThat(r.items()).extracting(SectionItem::slug)
                .containsExactly("old", "exact");
    }

    @Test
    void games_with_no_quality_signal_are_filtered_out() {
        BuildResult r = new VintageBuilder(5, CLOCK).build(new SectionContext(List.of(
                vintageGame("a", "A", "2014-01-01", null, null),
                vintageGame("b", "B", "2014-01-01", 90, null))));
        assertThat(r.totalCandidates()).isEqualTo(1);
        assertThat(r.items().get(0).slug()).isEqualTo("b");
    }

    @Test
    void games_with_null_cheapshark_view_are_filtered_out() {
        BuildResult r = new VintageBuilder(5, CLOCK).build(new SectionContext(List.of(
                new GameView("a", "A", null,
                        new RawgView("2014-01-01", 90, null,
                                null, null, null, null, null), null),
                vintageGame("b", "B", "2014-01-01", 90, null))));
        assertThat(r.totalCandidates()).isEqualTo(1);
        assertThat(r.items().get(0).slug()).isEqualTo("b");
    }

    @Test
    void unsynced_cheapshark_blocks_are_filtered_out() {
        BuildResult r = new VintageBuilder(5, CLOCK).build(new SectionContext(List.of(
                vintageGame("a", "A", "2014-01-01", 90, null, false, true),
                vintageGame("b", "B", "2014-01-01", 90, null))));
        assertThat(r.totalCandidates()).isEqualTo(1);
        assertThat(r.items().get(0).slug()).isEqualTo("b");
    }

    @Test
    void games_with_null_best_deal_are_filtered_out() {
        BuildResult r = new VintageBuilder(5, CLOCK).build(new SectionContext(List.of(
                vintageGameNullBestDeal("a", "A"),
                vintageGame("b", "B", "2014-01-01", 90, null))));
        assertThat(r.totalCandidates()).isEqualTo(1);
        assertThat(r.items().get(0).slug()).isEqualTo("b");
    }

    // -------- scoring -------------------------------------------------------

    @Test
    void metacritic_takes_precedence_over_rating() {
        BuildResult r = new VintageBuilder(5, CLOCK).build(new SectionContext(List.of(
                vintageGame("a", "A", "2014-01-01", 80, 4.0))));
        SectionItem item = r.items().get(0);
        assertThat(item.score()).isEqualByComparingTo("80");
    }

    @Test
    void rating_only_score_is_rescaled() {
        BuildResult r = new VintageBuilder(5, CLOCK).build(new SectionContext(List.of(
                vintageGame("a", "A", "2014-01-01", null, 4.5))));
        assertThat(r.items().get(0).score()).isEqualByComparingTo("90");
    }

    // -------- ordering ------------------------------------------------------

    @Test
    void eligible_games_are_sorted_by_score_descending() {
        BuildResult r = new VintageBuilder(5, CLOCK).build(new SectionContext(List.of(
                vintageGame("a", "A", "2014-01-01", 70, null),
                vintageGame("b", "B", "2014-01-01", 95, null),
                vintageGame("c", "C", "2014-01-01", 82, null))));
        assertThat(r.totalCandidates()).isEqualTo(3);
        assertThat(r.items()).extracting(SectionItem::slug)
                .containsExactly("b", "c", "a");
    }

    @Test
    void ties_are_broken_by_released_ascending() {
        BuildResult r = new VintageBuilder(5, CLOCK).build(new SectionContext(List.of(
                vintageGame("newest", "N", "2017-01-01", 90, null),
                vintageGame("middle", "M", "2014-06-15", 90, null),
                vintageGame("oldest", "O", "2010-01-01", 90, null))));
        assertThat(r.items()).extracting(SectionItem::slug)
                .containsExactly("oldest", "middle", "newest");
    }

    @Test
    void top_n_is_capped_at_max_items() {
        List<GameView> six = List.of(
                vintageGame("a", "A", "2014-01-01", 60, null),
                vintageGame("b", "B", "2014-01-01", 70, null),
                vintageGame("c", "C", "2014-01-01", 80, null),
                vintageGame("d", "D", "2014-01-01", 90, null),
                vintageGame("e", "E", "2014-01-01", 95, null),
                vintageGame("f", "F", "2014-01-01", 99, null));
        BuildResult r = new VintageBuilder(5, CLOCK).build(new SectionContext(six));
        assertThat(r.totalCandidates()).isEqualTo(6);
        assertThat(r.items()).extracting(SectionItem::slug)
                .containsExactly("f", "e", "d", "c", "b");
    }

    // -------- extras --------------------------------------------------------

    @Test
    void item_exposes_year_in_extra() {
        BuildResult r = new VintageBuilder(5, CLOCK).build(new SectionContext(List.of(
                vintageGame("a", "A", "2014-06-15", 90, null))));
        assertThat(r.items().get(0).extra()).containsEntry("year", "2014");
    }

    @Test
    void item_exposes_metacritic_in_extra_when_present() {
        BuildResult r = new VintageBuilder(5, CLOCK).build(new SectionContext(List.of(
                vintageGame("a", "A", "2014-01-01", 92, 4.5))));
        assertThat(r.items().get(0).extra())
                .containsEntry("year", "2014")
                .containsEntry("metacritic", "92")
                .doesNotContainKey("rating");
    }

    @Test
    void item_exposes_rating_in_extra_when_metacritic_absent() {
        BuildResult r = new VintageBuilder(5, CLOCK).build(new SectionContext(List.of(
                vintageGame("a", "A", "2014-01-01", null, 4.6))));
        assertThat(r.items().get(0).extra())
                .containsEntry("year", "2014")
                .containsEntry("rating", "4.6")
                .doesNotContainKey("metacritic");
    }

    // -------- helpers --------------------------------------------------------

    private static GameView vintageGame(String slug, String title, String released,
            Integer metacritic, Double rating) {
        return vintageGame(slug, title, released, metacritic, rating, true, true);
    }

    private static GameView vintageGame(String slug, String title, String released,
            Integer metacritic, Double rating,
            boolean cheapSynced, boolean withBestDeal) {
        Offer best = withBestDeal ? BEST_DEAL : null;
        return new GameView(slug, title,
                new CheapsharkView(cheapSynced, best, null, null, List.of()),
                new RawgView(released, metacritic, rating,
                        null, null, null, null, null), null);
    }

    private static GameView vintageGameNullBestDeal(String slug, String title) {
        return new GameView(slug, title,
                new CheapsharkView(true, null, null, null, List.of()),
                new RawgView("2014-01-01", 90, null,
                        null, null, null, null, null), null);
    }
}
