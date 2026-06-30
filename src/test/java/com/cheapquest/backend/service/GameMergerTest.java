package com.cheapquest.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.cheapquest.backend.domain.AggregatedGame;
import com.cheapquest.backend.domain.GameDeals;
import com.cheapquest.backend.domain.Offer;
import com.cheapquest.backend.domain.rawg.RawgDetails;
import com.cheapquest.backend.fixtures.RawgDetailsFixtures;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class GameMergerTest {

    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant MERGE_T = Instant.parse("2026-01-01T00:05:00Z");

    private final GameMerger merger = new GameMerger(Clock.fixed(MERGE_T, ZoneOffset.UTC));

    @Test
    void rejects_null_clock() {
        assertThatNullPointerException()
                .isThrownBy(() -> new GameMerger(null))
                .withMessageContaining("clock");
    }

    @Test
    void merge_bothSources_returnsAggregatedGameWithBoth() {
        GameDeals deals = fullDeals();
        AggregatedGame rawgAgg = new AggregatedGame("Portal", "Portal", "portal", null, fullRawg(), T);

        AggregatedGame merged = merger.merge(deals, rawgAgg);

        assertThat(merged.cheapShark()).isSameAs(deals);
        assertThat(merged.rawg()).isNotNull();
        assertThat(merged.rawg().slug()).isEqualTo("portal");
        assertThat(merged.fetchedAt()).isEqualTo(T);
    }

    @Test
    void merge_onlyRawg_returnsAggregatedGameWithNullDeals() {
        AggregatedGame rawgAgg = new AggregatedGame("Portal", "Portal", "portal", null, fullRawg(), T);

        AggregatedGame merged = merger.merge(null, rawgAgg);

        assertThat(merged.cheapShark()).isNull();
        assertThat(merged.rawg()).isNotNull();
        assertThat(merged.fetchedAt()).isEqualTo(T);
    }

    @Test
    void merge_onlyDeals_returnsAggregatedGameWithNullRawg() {
        GameDeals deals = fullDeals();

        AggregatedGame merged = merger.merge(deals, null);

        assertThat(merged.cheapShark()).isSameAs(deals);
        assertThat(merged.rawg()).isNull();
        assertThat(merged.cheapSharkTitle()).isEqualTo("Portal");
        assertThat(merged.canonicalName()).isEqualTo("Portal");
        assertThat(merged.rawgSlug()).isNull();
        assertThat(merged.fetchedAt()).isEqualTo(MERGE_T);
    }

    @Test
    void merge_bothNull_throws() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> merger.merge(null, null))
                .withMessageContaining("at least one source");
    }

    @Test
    void merge_preservesCanonicalFieldsFromRawg() {
        GameDeals deals = fullDeals();
        AggregatedGame rawgAgg = new AggregatedGame(
                "searched-as-Portal", "Portal (Canonical)", "portal", null, fullRawg(), T);

        AggregatedGame merged = merger.merge(deals, rawgAgg);

        assertThat(merged.cheapSharkTitle()).isEqualTo("searched-as-Portal");
        assertThat(merged.canonicalName()).isEqualTo("Portal (Canonical)");
        assertThat(merged.rawgSlug()).isEqualTo("portal");
    }

    @Test
    void merge_preservesSearchTitleFromDealsWhenRawgMissing() {
        GameDeals deals = new GameDeals("82", "PortalSearch", "Portal", "PORTAL",
                "https://example.com/thumb.jpg", new BigDecimal("0.99"),
                1, new Offer("1", "Steam", null,
                        new BigDecimal("1.99"), new BigDecimal("9.99"),
                        new BigDecimal("80.080"), "https://example.com/deal"),
                List.of(), T);

        AggregatedGame merged = merger.merge(deals, null);

        assertThat(merged.cheapSharkTitle()).isEqualTo("PortalSearch");
        assertThat(merged.canonicalName()).isEqualTo("Portal");
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
                List.of(), T);
    }
}
