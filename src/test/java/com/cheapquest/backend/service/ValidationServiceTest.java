package com.cheapquest.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.cheapquest.backend.domain.AggregatedGame;
import com.cheapquest.backend.domain.GameDeals;
import com.cheapquest.backend.domain.Offer;
import com.cheapquest.backend.domain.rawg.RawgDetails;
import com.cheapquest.backend.domain.validation.GameField;
import com.cheapquest.backend.domain.validation.ValidationReport;
import com.cheapquest.backend.domain.validation.ValidationStatus;
import com.cheapquest.backend.fixtures.RawgDetailsFixtures;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ValidationServiceTest {

    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");

    private final ValidationService service = new ValidationService(Clock.fixed(T, ZoneOffset.UTC));

    @Test
    void rejects_null_clock() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ValidationService(null))
                .withMessageContaining("clock");
    }

    @Test
    void evaluate_rejects_null_game() {
        assertThatNullPointerException()
                .isThrownBy(() -> service.evaluate(null))
                .withMessageContaining("game");
    }

    @Test
    void status_is_EMPTY_when_both_sources_are_null() {
        AggregatedGame game = aggregated(null, null);
        ValidationReport r = service.evaluate(game);
        assertThat(r.status()).isEqualTo(ValidationStatus.EMPTY);
        assertThat(r.missingFields()).isEmpty();
        assertThat(r.lastFullFetchAt()).isEqualTo(T);
        assertThat(r.lastPartialFetchAt()).isNull();
    }

    @Test
    void status_is_COMPLETE_when_all_fields_present() {
        AggregatedGame game = aggregated(fullRawg(), fullDeals());
        ValidationReport r = service.evaluate(game);
        assertThat(r.status()).isEqualTo(ValidationStatus.COMPLETE);
        assertThat(r.missingFields()).isEmpty();
    }

    @Test
    void status_is_PARTIAL_when_any_field_missing() {
        RawgDetails rawg = RawgDetailsFixtures.full("portal", "Portal")
                .description(null).descriptionRaw(null).build();
        AggregatedGame game = aggregated(rawg, fullDeals());
        ValidationReport r = service.evaluate(game);
        assertThat(r.status()).isEqualTo(ValidationStatus.PARTIAL);
        assertThat(r.missingFields()).containsExactly(GameField.DESCRIPTION);
    }

    @Test
    void status_is_COMPLETE_when_cheapShark_is_null_but_rawg_full() {
        AggregatedGame game = aggregated(fullRawg(), null);
        ValidationReport r = service.evaluate(game);
        assertThat(r.status()).isEqualTo(ValidationStatus.PARTIAL);
        assertThat(r.missingFields()).containsExactly(GameField.STORES);
    }

    @Test
    void status_is_PARTIAL_when_rawg_is_null_but_cheapShark_full() {
        AggregatedGame game = aggregated(null, fullDeals());
        ValidationReport r = service.evaluate(game);
        assertThat(r.status()).isEqualTo(ValidationStatus.PARTIAL);
        assertThat(r.missingFields())
                .containsExactlyInAnyOrder(
                        GameField.DESCRIPTION, GameField.HEADER_IMAGE, GameField.TRAILER,
                        GameField.GENRES, GameField.TAGS, GameField.SCREENSHOTS,
                        GameField.RELEASED, GameField.DEVELOPER, GameField.PUBLISHER);
        assertThat(r.missingFields()).doesNotContain(GameField.STORES);
        assertThat(r.missingFields()).doesNotContain(GameField.REVIEWS);
    }

    @Test
    void missing_DESCRIPTION_when_both_description_and_descriptionRaw_are_null() {
        RawgDetails rawg = RawgDetailsFixtures.full("a", "A").description(null).descriptionRaw(null).build();
        AggregatedGame game = aggregated(rawg, fullDeals());
        assertThat(service.evaluate(game).missingFields()).contains(GameField.DESCRIPTION);
    }

    @Test
    void missing_DESCRIPTION_when_both_are_blank() {
        RawgDetails rawg = RawgDetailsFixtures.full("a", "A").description("   ").descriptionRaw("").build();
        AggregatedGame game = aggregated(rawg, fullDeals());
        assertThat(service.evaluate(game).missingFields()).contains(GameField.DESCRIPTION);
    }

    @Test
    void not_missing_DESCRIPTION_when_descriptionRaw_present_even_if_description_blank() {
        RawgDetails rawg = RawgDetailsFixtures.full("a", "A").description(null).descriptionRaw("hello").build();
        AggregatedGame game = aggregated(rawg, fullDeals());
        assertThat(service.evaluate(game).missingFields()).doesNotContain(GameField.DESCRIPTION);
    }

    @Test
    void missing_HEADER_IMAGE_when_null_or_blank() {
        assertMissing(GameField.HEADER_IMAGE, RawgDetailsFixtures.full("a", "A").headerImage(null).build());
        assertMissing(GameField.HEADER_IMAGE, RawgDetailsFixtures.full("a", "A").headerImage("  ").build());
    }

    @Test
    void missing_TRAILER_when_null_or_blank() {
        assertMissing(GameField.TRAILER, RawgDetailsFixtures.full("a", "A").trailerUrl(null).build());
        assertMissing(GameField.TRAILER, RawgDetailsFixtures.full("a", "A").trailerUrl("").build());
    }

    @Test
    void missing_GENRES_when_list_empty() {
        RawgDetails rawg = RawgDetailsFixtures.full("a", "A").genres(List.of()).build();
        assertMissing(GameField.GENRES, rawg);
    }

    @Test
    void missing_TAGS_when_list_empty() {
        RawgDetails rawg = RawgDetailsFixtures.full("a", "A").tags(List.of()).build();
        assertMissing(GameField.TAGS, rawg);
    }

    @Test
    void missing_SCREENSHOTS_when_list_empty() {
        RawgDetails rawg = RawgDetailsFixtures.full("a", "A").screenshots(List.of()).build();
        assertMissing(GameField.SCREENSHOTS, rawg);
    }

    @Test
    void missing_STORES_when_cheapShark_offers_empty() {
        AggregatedGame game = aggregated(fullRawg(), emptyDeals());
        assertThat(service.evaluate(game).missingFields()).contains(GameField.STORES);
    }

    @Test
    void missing_RELEASED_when_null_or_blank() {
        assertMissing(GameField.RELEASED, RawgDetailsFixtures.full("a", "A").released(null).build());
        assertMissing(GameField.RELEASED, RawgDetailsFixtures.full("a", "A").released("").build());
    }

    @Test
    void missing_DEVELOPER_when_list_empty() {
        RawgDetails rawg = RawgDetailsFixtures.full("a", "A").developers(List.of()).build();
        assertMissing(GameField.DEVELOPER, rawg);
    }

    @Test
    void missing_PUBLISHER_when_list_empty() {
        RawgDetails rawg = RawgDetailsFixtures.full("a", "A").publishers(List.of()).build();
        assertMissing(GameField.PUBLISHER, rawg);
    }

    @Test
    void REVIEWS_is_never_in_missingFields() {
        AggregatedGame empty = aggregated(null, null);
        AggregatedGame partial = aggregated(null, null);
        AggregatedGame full = aggregated(fullRawg(), fullDeals());
        assertThat(service.evaluate(empty).missingFields()).doesNotContain(GameField.REVIEWS);
        assertThat(service.evaluate(partial).missingFields()).doesNotContain(GameField.REVIEWS);
        assertThat(service.evaluate(full).missingFields()).doesNotContain(GameField.REVIEWS);
    }

    @Test
    void lastFullFetchAt_is_copied_from_aggregatedGame() {
        Instant stamp = Instant.parse("2025-06-15T12:34:56Z");
        AggregatedGame game = new AggregatedGame("Portal", "Portal", "portal", fullDeals(), fullRawg(), stamp);
        ValidationReport r = service.evaluate(game);
        assertThat(r.lastFullFetchAt()).isEqualTo(stamp);
    }

    @Test
    void lastPartialFetchAt_is_null_in_this_phase() {
        AggregatedGame game = aggregated(fullRawg(), fullDeals());
        assertThat(service.evaluate(game).lastPartialFetchAt()).isNull();
    }

    @Test
    void missingFields_is_unmodifiable() {
        AggregatedGame game = aggregated(null, null);
        ValidationReport r = service.evaluate(game);
        assertThat(r.missingFields()).isUnmodifiable();
    }

    @Test
    void multiple_missing_fields_are_all_listed() {
        RawgDetails rawg = RawgDetailsFixtures.full("a", "A")
                .description(null).descriptionRaw(null)
                .headerImage(null)
                .trailerUrl(null)
                .build();
        AggregatedGame game = aggregated(rawg, emptyDeals());
        assertThat(service.evaluate(game).missingFields())
                .contains(GameField.DESCRIPTION, GameField.HEADER_IMAGE,
                        GameField.TRAILER, GameField.STORES);
    }

    private void assertMissing(GameField field, RawgDetails rawg) {
        AggregatedGame game = aggregated(rawg, fullDeals());
        assertThat(service.evaluate(game).missingFields())
                .as("field=%s", field)
                .contains(field);
    }

    private static AggregatedGame aggregated(RawgDetails rawg, GameDeals deals) {
        return new AggregatedGame(
                "Portal", "Portal", "portal", deals, rawg, T);
    }

    private static RawgDetails fullRawg() {
        return RawgDetailsFixtures.full("portal", "Portal").build();
    }

    private static GameDeals fullDeals() {
        return new GameDeals(
                "82", "Portal", "Portal", "PORTAL",
                "https://example.com/thumb.jpg",
                new BigDecimal("0.99"),
                1,
                new Offer("1", "Steam", null,
                        new BigDecimal("1.99"), new BigDecimal("9.99"),
                        new BigDecimal("80.080"), "https://example.com/deal"),
                List.of(),
                T);
    }

    private static GameDeals emptyDeals() {
        return new GameDeals("82", "Portal", "Portal", "PORTAL",
                "https://example.com/thumb.jpg", null, 0, null, List.of(), T);
    }
}
