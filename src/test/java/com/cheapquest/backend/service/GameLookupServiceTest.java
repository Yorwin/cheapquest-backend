package com.cheapquest.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cheapquest.backend.domain.AggregatedGame;
import com.cheapquest.backend.domain.GameDeals;
import com.cheapquest.backend.domain.Offer;
import com.cheapquest.backend.domain.rawg.RawgDetails;
import com.cheapquest.backend.exception.GameNotFoundException;
import com.cheapquest.backend.fixtures.RawgDetailsFixtures;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GameLookupServiceTest {

    private static final Instant T = Instant.parse("2026-06-30T10:00:00Z");
    private static final Set<GameLookup.Source> ALL_SOURCES = EnumSet.allOf(GameLookup.Source.class);

    private GameAggregationService csService;
    private RawgAggregationService rawgService;
    private GameLookupService service;

    @BeforeEach
    void setUp() {
        csService = mock(GameAggregationService.class);
        rawgService = mock(RawgAggregationService.class);
        service = new GameLookupService(csService, rawgService);
    }

    @Test
    void constructor_rejectsNullDependencies() {
        assertThatThrownBy(() -> new GameLookupService(null, rawgService))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GameLookupService(csService, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void lookupByTitle_returnsBothResultsWhenBothSucceed() {
        GameDeals deals = sampleDeals();
        AggregatedGame rawgAgg = new AggregatedGame("Portal", "Portal", "portal",
                null, sampleRawg(), T);
        when(csService.aggregateByName("Portal")).thenReturn(deals);
        when(rawgService.aggregate("Portal")).thenReturn(rawgAgg);

        GameLookup.GameLookupResult result = service.lookupByTitle("Portal", ALL_SOURCES);

        assertThat(result.deals()).isSameAs(deals);
        assertThat(result.rawgAgg()).isSameAs(rawgAgg);
        assertThat(result.isEmpty()).isFalse();
    }

    @Test
    void lookupByTitle_returnsOnlyDealsWhenRawgFailsWithGameNotFound() {
        GameDeals deals = sampleDeals();
        when(csService.aggregateByName("Portal")).thenReturn(deals);
        when(rawgService.aggregate("Portal"))
                .thenThrow(new GameNotFoundException("no rawg match"));

        GameLookup.GameLookupResult result = service.lookupByTitle("Portal", ALL_SOURCES);

        assertThat(result.deals()).isSameAs(deals);
        assertThat(result.rawgAgg()).isNull();
        assertThat(result.isEmpty()).isFalse();
    }

    @Test
    void lookupByTitle_returnsOnlyDealsWhenRawgFailsWithOtherException() {
        GameDeals deals = sampleDeals();
        when(csService.aggregateByName("Portal")).thenReturn(deals);
        when(rawgService.aggregate("Portal"))
                .thenThrow(new RuntimeException("503 from rawg"));

        GameLookup.GameLookupResult result = service.lookupByTitle("Portal", ALL_SOURCES);

        assertThat(result.deals()).isSameAs(deals);
        assertThat(result.rawgAgg()).isNull();
    }

    @Test
    void lookupByTitle_returnsOnlyRawgWhenCheapSharkFails() {
        AggregatedGame rawgAgg = new AggregatedGame("Portal", "Portal", "portal",
                null, sampleRawg(), T);
        when(csService.aggregateByName("Portal"))
                .thenThrow(new GameNotFoundException("no cs match"));
        when(rawgService.aggregate("Portal")).thenReturn(rawgAgg);

        GameLookup.GameLookupResult result = service.lookupByTitle("Portal", ALL_SOURCES);

        assertThat(result.deals()).isNull();
        assertThat(result.rawgAgg()).isSameAs(rawgAgg);
    }

    @Test
    void lookupByTitle_returnsEmptyWhenBothFail() {
        when(csService.aggregateByName("Portal"))
                .thenThrow(new GameNotFoundException("no cs"));
        when(rawgService.aggregate("Portal"))
                .thenThrow(new GameNotFoundException("no rawg"));

        GameLookup.GameLookupResult result = service.lookupByTitle("Portal", ALL_SOURCES);

        assertThat(result.isEmpty()).isTrue();
        assertThat(result.deals()).isNull();
        assertThat(result.rawgAgg()).isNull();
    }

    @Test
    void lookupByTitle_returnsEmptyWhenBothFailWithRuntimeException() {
        when(csService.aggregateByName("Portal"))
                .thenThrow(new RuntimeException("cs down"));
        when(rawgService.aggregate("Portal"))
                .thenThrow(new RuntimeException("rawg down"));

        GameLookup.GameLookupResult result = service.lookupByTitle("Portal", ALL_SOURCES);

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void emptyResult_isEmptyAndHasNulls() {
        GameLookup.GameLookupResult empty = GameLookup.GameLookupResult.empty();

        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.deals()).isNull();
        assertThat(empty.rawgAgg()).isNull();
    }

    @Test
    void lookupByTitle_continuesAfterFirstSourceFails() {
        when(csService.aggregateByName("Portal"))
                .thenThrow(new RuntimeException("cs exploded"));
        AggregatedGame rawgAgg = new AggregatedGame("Portal", "Portal", "portal",
                null, sampleRawg(), T);
        when(rawgService.aggregate("Portal")).thenReturn(rawgAgg);

        GameLookup.GameLookupResult result = service.lookupByTitle("Portal", ALL_SOURCES);

        assertThat(result.deals()).isNull();
        assertThat(result.rawgAgg()).isSameAs(rawgAgg);
    }

    @Test
    void lookupByTitle_skipsCheapSharkWhenNotInSources() {
        AggregatedGame rawgAgg = new AggregatedGame("Portal", "Portal", "portal",
                null, sampleRawg(), T);
        when(rawgService.aggregate("Portal")).thenReturn(rawgAgg);

        GameLookup.GameLookupResult result = service.lookupByTitle("Portal",
                EnumSet.of(GameLookup.Source.RAWG));

        assertThat(result.deals()).isNull();
        assertThat(result.rawgAgg()).isSameAs(rawgAgg);
        verify(csService, never()).aggregateByName(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void lookupByTitle_skipsRawgWhenNotInSources() {
        GameDeals deals = sampleDeals();
        when(csService.aggregateByName("Portal")).thenReturn(deals);

        GameLookup.GameLookupResult result = service.lookupByTitle("Portal",
                EnumSet.of(GameLookup.Source.CHEAPSHARK));

        assertThat(result.deals()).isSameAs(deals);
        assertThat(result.rawgAgg()).isNull();
        verify(rawgService, never()).aggregate(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void lookupByTitle_skipsBothWhenSourcesSetIsEmpty() {
        GameLookup.GameLookupResult result = service.lookupByTitle("Portal", EnumSet.noneOf(GameLookup.Source.class));

        assertThat(result.isEmpty()).isTrue();
        verify(csService, never()).aggregateByName(org.mockito.ArgumentMatchers.anyString());
        verify(rawgService, never()).aggregate(org.mockito.ArgumentMatchers.anyString());
    }

    private static GameDeals sampleDeals() {
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

    private static RawgDetails sampleRawg() {
        return RawgDetailsFixtures.full("portal", "Portal")
                .fetchedAt(T).build();
    }
}
