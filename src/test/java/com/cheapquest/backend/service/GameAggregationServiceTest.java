package com.cheapquest.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cheapquest.backend.client.CheapSharkClient;
import com.cheapquest.backend.domain.GameDeals;
import com.cheapquest.backend.dto.cheapshark.CheapSharkGameDetailDto;
import com.cheapquest.backend.dto.cheapshark.CheapSharkGameSummaryDto;
import com.cheapquest.backend.dto.cheapshark.CheapSharkStoreDto;
import com.cheapquest.backend.exception.GameNotFoundException;
import com.cheapquest.backend.mapper.CheapSharkMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GameAggregationServiceTest {

    private CheapSharkClient client;
    private CheapSharkMapper mapper;
    private GameAggregationService service;

    @BeforeEach
    void setUp() {
        client = mock(CheapSharkClient.class);
        mapper = new CheapSharkMapper();
        service = new GameAggregationService(
                client, mapper, List.of(new CheapSharkStoreDto("Steam", 1, null, "1")));
    }

    @Test
    void aggregateByName_returnsGameDealsWhenMatchFound() {
        var summary = new CheapSharkGameSummaryDto(
                "82", "400", "1.99", "d1", "Portal", "PORTAL", "thumb");
        var detail = new CheapSharkGameDetailDto(
                null, null,
                List.of(new com.cheapquest.backend.dto.cheapshark.CheapSharkDealDto(
                        "1", "d1", "1.99", "9.99", "80.0")));
        when(client.findByTitle("Portal")).thenReturn(List.of(summary));
        when(client.getDetails("82")).thenReturn(Optional.of(detail));

        GameDeals result = service.aggregateByName("Portal");

        assertThat(result.gameId()).isEqualTo("82");
        assertThat(result.searchTitle()).isEqualTo("Portal");
        assertThat(result.bestDeal()).isNotNull();
        assertThat(result.bestDeal().storeName()).isEqualTo("Steam");
    }

    @Test
    void aggregateByName_throwsGameNotFoundWhenNoExactMatch() {
        when(client.findByTitle("Portal")).thenReturn(List.of(
                new CheapSharkGameSummaryDto("1", null, null, null, "Portal 2", "PORTAL2", null)));

        assertThatThrownBy(() -> service.aggregateByName("Portal"))
                .isInstanceOf(GameNotFoundException.class)
                .hasMessageContaining("no exact match for \"Portal\"")
                .hasMessageContaining("got 1 candidates");
    }

    @Test
    void aggregateByName_throwsGameNotFoundWhenNoDetail() {
        var summary = new CheapSharkGameSummaryDto(
                "82", null, null, null, "Portal", "PORTAL", null);
        when(client.findByTitle("Portal")).thenReturn(List.of(summary));
        when(client.getDetails("82")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.aggregateByName("Portal"))
                .isInstanceOf(GameNotFoundException.class)
                .hasMessageContaining("no detail for gameId=82");
    }

    @Test
    void aggregateByName_propagatesApiError() {
        when(client.findByTitle(anyString())).thenThrow(
                new com.cheapquest.backend.exception.ApiUnavailableException("HTTP 503", 503, "down"));

        assertThatThrownBy(() -> service.aggregateByName("Portal"))
                .isInstanceOf(com.cheapquest.backend.exception.ApiUnavailableException.class)
                .hasMessageContaining("HTTP 503");
    }

    @Test
    void aggregateByName_setsFetchedAtFromInjectedClock() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-06-30T10:00:00Z"), ZoneOffset.UTC);
        GameAggregationService fixedService = new GameAggregationService(
                client, mapper, List.of(new CheapSharkStoreDto("Steam", 1, null, "1")), fixedClock);

        var summary = new CheapSharkGameSummaryDto(
                "82", "400", "1.99", "d1", "Portal", "PORTAL", "thumb");
        var detail = new CheapSharkGameDetailDto(null, null, List.of());
        when(client.findByTitle("Portal")).thenReturn(List.of(summary));
        when(client.getDetails("82")).thenReturn(Optional.of(detail));

        GameDeals result = fixedService.aggregateByName("Portal");

        assertThat(result.fetchedAt()).isEqualTo(Instant.parse("2026-06-30T10:00:00Z"));
    }

    @Test
    void constructor_rejectsNullClock() {
        assertThatThrownBy(() -> new GameAggregationService(
                client, mapper, List.of(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("clock");
    }
}
