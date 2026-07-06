package com.cheapquest.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

import com.cheapquest.backend.client.RawgClient;
import com.cheapquest.backend.domain.AggregatedGame;
import com.cheapquest.backend.domain.rawg.RawgDetails;
import com.cheapquest.backend.dto.rawg.RawgCreatorDto;
import com.cheapquest.backend.dto.rawg.RawgGameDto;
import com.cheapquest.backend.dto.rawg.RawgScreenshotDto;
import com.cheapquest.backend.exception.ApiUnavailableException;
import com.cheapquest.backend.exception.GameNotFoundException;
import com.cheapquest.backend.fixtures.RawgDtoFixtures;
import com.cheapquest.backend.mapper.RawgMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RawgAggregationServiceTest {

    private RawgClient client;
    private RawgMapper mapper;
    private Clock fixedClock;
    private RawgAggregationService service;

    @BeforeEach
    void setUp() {
        client = mock(RawgClient.class);
        mapper = mock(RawgMapper.class);
        fixedClock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        service = new RawgAggregationService(client, mapper, fixedClock);
    }

    @Test
    void aggregate_returnsAggregatedGameWithRawgDetails() {
        var farCry = RawgDtoFixtures.minimalGame("far-cry", "Far Cry");
        var detail = RawgDtoFixtures.detailWithCounts("far-cry", "Far Cry", 0, 0, 0, 0);
        var rawg = new RawgDetails(
                "far-cry", "Far Cry", "Far Cry", "2004-03-22",
                "desc", "desc", "https://x.jpg", null, null,
                3.9, 4, 89, 0, 0, 0, 0,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(),
                false, null, null, List.of(), 0, 0, 0, null, 0, 0, 0, 0, 0,
                null, List.of(), null, List.of(), List.of(),
                java.util.Map.of(), java.util.Map.of(), 0,
                Instant.parse("2026-01-01T00:00:00Z"));

        when(client.searchByName("Far Cry", 10)).thenReturn(List.of(farCry));
        when(mapper.pickExactMatch(List.of(farCry), "Far Cry")).thenReturn(Optional.of(farCry));
        lenient().when(mapper.pickClosestByLevenshtein(List.of(farCry), "Far Cry")).thenReturn(Optional.empty());
        when(client.getDetails("far-cry")).thenReturn(Optional.of(detail));
        when(mapper.toDetails(eq(detail), anyList(), anyList(), anyList(), anyList(), any())).thenReturn(rawg);

        AggregatedGame result = service.aggregate("Far Cry");

        assertThat(result.cheapSharkTitle()).isEqualTo("Far Cry");
        assertThat(result.canonicalName()).isEqualTo("Far Cry");
        assertThat(result.rawgSlug()).isEqualTo("far-cry");
        assertThat(result.cheapShark()).isNull();
        assertThat(result.rawg()).isSameAs(rawg);
        assertThat(result.fetchedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void aggregate_threadsFixedClockInstantIntoRawgDetails() {
        var farCry = RawgDtoFixtures.minimalGame("far-cry", "Far Cry");
        var detail = RawgDtoFixtures.detailWithCounts("far-cry", "Far Cry", 0, 0, 0, 0);
        var rawg = stubDetails("far-cry", "Far Cry");

        when(client.searchByName("Far Cry", 10)).thenReturn(List.of(farCry));
        when(mapper.pickExactMatch(List.of(farCry), "Far Cry")).thenReturn(Optional.of(farCry));
        lenient().when(mapper.pickClosestByLevenshtein(List.of(farCry), "Far Cry")).thenReturn(Optional.empty());
        when(client.getDetails("far-cry")).thenReturn(Optional.of(detail));
        when(mapper.toDetails(eq(detail), anyList(), anyList(), anyList(), anyList(),
                eq(Instant.parse("2026-01-01T00:00:00Z")))).thenReturn(rawg);

        service.aggregate("Far Cry");

        verify(mapper).toDetails(eq(detail), anyList(), anyList(), anyList(), anyList(),
                eq(Instant.parse("2026-01-01T00:00:00Z")));
    }

    @Test
    void aggregate_fallsBackToLevenshteinWhenNoExactMatch() {
        var farCry = RawgDtoFixtures.minimalGame("far-cry", "Far Cry");
        var detail = RawgDtoFixtures.detailWithCounts("far-cry", "Far Cry", 0, 0, 0, 0);
        var rawg = stubDetails("far-cry", "Far Cry");

        when(client.searchByName("Farcry", 10)).thenReturn(List.of(farCry));
        when(mapper.pickExactMatch(List.of(farCry), "Farcry")).thenReturn(Optional.empty());
        when(mapper.pickClosestByLevenshtein(List.of(farCry), "Farcry")).thenReturn(Optional.of(farCry));
        when(client.getDetails("far-cry")).thenReturn(Optional.of(detail));
        when(mapper.toDetails(eq(detail), anyList(), anyList(), anyList(), anyList(), any())).thenReturn(rawg);

        AggregatedGame result = service.aggregate("Farcry");

        assertThat(result.rawgSlug()).isEqualTo("far-cry");
        verify(mapper).pickClosestByLevenshtein(List.of(farCry), "Farcry");
    }

    @Test
    void aggregate_throwsWhenNoMatch() {
        when(client.searchByName("Nonexistent", 10)).thenReturn(List.of());
        when(mapper.pickExactMatch(List.of(), "Nonexistent")).thenReturn(Optional.empty());
        when(mapper.pickClosestByLevenshtein(List.of(), "Nonexistent")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.aggregate("Nonexistent"))
                .isInstanceOf(GameNotFoundException.class)
                .hasMessageContaining("Nonexistent")
                .hasMessageContaining("got 0 candidates");
    }

    @Test
    void aggregate_throwsWhenDetailEmpty() {
        var farCry = RawgDtoFixtures.minimalGame("far-cry", "Far Cry");

        when(client.searchByName("Far Cry", 10)).thenReturn(List.of(farCry));
        when(mapper.pickExactMatch(List.of(farCry), "Far Cry")).thenReturn(Optional.of(farCry));
        lenient().when(mapper.pickClosestByLevenshtein(any(), anyString())).thenReturn(Optional.empty());
        when(client.getDetails("far-cry")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.aggregate("Far Cry"))
                .isInstanceOf(GameNotFoundException.class)
                .hasMessageContaining("no RAWG detail")
                .hasMessageContaining("far-cry");
    }

    @Test
    void aggregate_callsAdditionsWhenCountGreaterThanZero() {
        var portal = RawgDtoFixtures.minimalGame("portal", "Portal");
        var detail = RawgDtoFixtures.detailWithCounts("portal", "Portal", 4, 0, 0, 0);
        var rtx = RawgDtoFixtures.minimalGame("portal-with-rtx", "Portal with RTX");
        var rawg = stubDetails("portal", "Portal");

        when(client.searchByName("Portal", 10)).thenReturn(List.of(portal));
        when(mapper.pickExactMatch(List.of(portal), "Portal")).thenReturn(Optional.of(portal));
        lenient().when(mapper.pickClosestByLevenshtein(any(), anyString())).thenReturn(Optional.empty());
        when(client.getDetails("portal")).thenReturn(Optional.of(detail));
        when(client.getAdditions("portal")).thenReturn(List.of(rtx));
        when(mapper.toDetails(eq(detail), anyList(), anyList(), anyList(), anyList(), any())).thenReturn(rawg);

        service.aggregate("Portal");

        verify(client, times(1)).getAdditions("portal");
    }

    @Test
    void aggregate_skipsAdditionsWhenCountIsZero() {
        var portal = RawgDtoFixtures.minimalGame("portal", "Portal");
        var detail = RawgDtoFixtures.detailWithCounts("portal", "Portal", 0, 0, 0, 0);
        var rawg = stubDetails("portal", "Portal");

        when(client.searchByName("Portal", 10)).thenReturn(List.of(portal));
        when(mapper.pickExactMatch(List.of(portal), "Portal")).thenReturn(Optional.of(portal));
        lenient().when(mapper.pickClosestByLevenshtein(any(), anyString())).thenReturn(Optional.empty());
        when(client.getDetails("portal")).thenReturn(Optional.of(detail));
        when(mapper.toDetails(eq(detail), anyList(), anyList(), anyList(), anyList(), any())).thenReturn(rawg);

        service.aggregate("Portal");

        verify(client, never()).getAdditions(anyString());
    }

    @Test
    void aggregate_callsCreatorsWhenCountGreaterThanZero() {
        var portal = RawgDtoFixtures.minimalGame("portal", "Portal");
        var detail = RawgDtoFixtures.detailWithCounts("portal", "Portal", 0, 30, 0, 0);
        var rawg = stubDetails("portal", "Portal");

        when(client.searchByName("Portal", 10)).thenReturn(List.of(portal));
        when(mapper.pickExactMatch(List.of(portal), "Portal")).thenReturn(Optional.of(portal));
        lenient().when(mapper.pickClosestByLevenshtein(any(), anyString())).thenReturn(Optional.empty());
        when(client.getDetails("portal")).thenReturn(Optional.of(detail));
        when(client.getDevelopmentTeam("portal")).thenReturn(
                List.of(new RawgCreatorDto(1, "Gabe", "gabe", null, null, "Founder", 1)));
        when(mapper.toDetails(eq(detail), anyList(), anyList(), anyList(), anyList(), any())).thenReturn(rawg);

        service.aggregate("Portal");

        verify(client, times(1)).getDevelopmentTeam("portal");
    }

    @Test
    void aggregate_skipsCreatorsWhenCountIsZero() {
        var portal = RawgDtoFixtures.minimalGame("portal", "Portal");
        var detail = RawgDtoFixtures.detailWithCounts("portal", "Portal", 0, 0, 0, 0);
        var rawg = stubDetails("portal", "Portal");

        when(client.searchByName("Portal", 10)).thenReturn(List.of(portal));
        when(mapper.pickExactMatch(List.of(portal), "Portal")).thenReturn(Optional.of(portal));
        lenient().when(mapper.pickClosestByLevenshtein(any(), anyString())).thenReturn(Optional.empty());
        when(client.getDetails("portal")).thenReturn(Optional.of(detail));
        when(mapper.toDetails(eq(detail), anyList(), anyList(), anyList(), anyList(), any())).thenReturn(rawg);

        service.aggregate("Portal");

        verify(client, never()).getDevelopmentTeam(anyString());
    }

    @Test
    void aggregate_alwaysCallsMoviesEvenWhenCountIsZero() {
        // RAWG's detail.moviesCount is sometimes stale (the
        // counter is 0 but /games/{slug}/movies has data, or
        // vice versa for very recent edits). The aggregation
        // always calls the endpoint so the mapper has a chance
        // to pick a trailer from the sub-list; if RAWG returns
        // an empty list we fall through to the clip or null.
        var portal = RawgDtoFixtures.minimalGame("portal", "Portal");
        var detail = RawgDtoFixtures.detailWithCounts("portal", "Portal", 0, 0, 0, 0);
        var rawg = stubDetails("portal", "Portal");

        when(client.searchByName("Portal", 10)).thenReturn(List.of(portal));
        when(mapper.pickExactMatch(List.of(portal), "Portal")).thenReturn(Optional.of(portal));
        lenient().when(mapper.pickClosestByLevenshtein(any(), anyString())).thenReturn(Optional.empty());
        when(client.getDetails("portal")).thenReturn(Optional.of(detail));
        when(client.getMovies("portal")).thenReturn(List.of());
        when(mapper.toDetails(eq(detail), anyList(), anyList(), anyList(), anyList(), any())).thenReturn(rawg);

        service.aggregate("Portal");

        verify(client, times(1)).getMovies("portal");
    }

    @Test
    void aggregate_passesNonEmptyMoviesToMapper() {
        // Happy path: RAWG returns one movie. The service
        // must hand it to the mapper so pickTrailerUrl can
        // project the URL.
        var portal = RawgDtoFixtures.minimalGame("portal", "Portal");
        var detail = RawgDtoFixtures.detailWithCounts("portal", "Portal", 0, 0, 0, 0);
        var rawg = stubDetails("portal", "Portal");
        var movie = mock(com.cheapquest.backend.dto.rawg.RawgMovieDto.class);
        var movieData = mock(com.cheapquest.backend.dto.rawg.RawgMovieDataDto.class);
        when(movieData.max()).thenReturn("https://cdn.example.com/trailer.mp4");
        when(movie.data()).thenReturn(movieData);

        when(client.searchByName("Portal", 10)).thenReturn(List.of(portal));
        when(mapper.pickExactMatch(List.of(portal), "Portal")).thenReturn(Optional.of(portal));
        lenient().when(mapper.pickClosestByLevenshtein(any(), anyString())).thenReturn(Optional.empty());
        when(client.getDetails("portal")).thenReturn(Optional.of(detail));
        when(client.getMovies("portal")).thenReturn(List.of(movie));
        when(mapper.toDetails(eq(detail), anyList(), anyList(), anyList(), anyList(), any())).thenReturn(rawg);

        service.aggregate("Portal");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<com.cheapquest.backend.dto.rawg.RawgMovieDto>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(mapper).toDetails(eq(detail), captor.capture(), anyList(), anyList(), anyList(), any());
        assertThat(captor.getValue()).containsExactly(movie);
    }

    @Test
    void aggregate_continuesWhenSubfetchReturnsEmptyList() {
        var portal = RawgDtoFixtures.minimalGame("portal", "Portal");
        var detail = RawgDtoFixtures.detailWithCounts("portal", "Portal", 0, 0, 0, 0);
        var rawg = stubDetails("portal", "Portal");

        when(client.searchByName("Portal", 10)).thenReturn(List.of(portal));
        when(mapper.pickExactMatch(List.of(portal), "Portal")).thenReturn(Optional.of(portal));
        lenient().when(mapper.pickClosestByLevenshtein(any(), anyString())).thenReturn(Optional.empty());
        when(client.getDetails("portal")).thenReturn(Optional.of(detail));
        when(mapper.toDetails(eq(detail), anyList(), anyList(), anyList(), anyList(), any())).thenReturn(rawg);

        AggregatedGame result = service.aggregate("Portal");

        assertThat(result).isNotNull();
        assertThat(result.rawg()).isSameAs(rawg);
        verify(mapper).toDetails(eq(detail), anyList(), anyList(), anyList(), anyList(), any());
    }

    @Test
    void aggregate_propagatesApiUnavailableWhenSubfetchReturns5xx() {
        var portal = RawgDtoFixtures.minimalGame("portal", "Portal");
        var detail = RawgDtoFixtures.detailWithCounts("portal", "Portal", 5, 0, 0, 0);

        when(client.searchByName("Portal", 10)).thenReturn(List.of(portal));
        when(mapper.pickExactMatch(List.of(portal), "Portal")).thenReturn(Optional.of(portal));
        lenient().when(mapper.pickClosestByLevenshtein(any(), anyString())).thenReturn(Optional.empty());
        when(client.getDetails("portal")).thenReturn(Optional.of(detail));
        when(client.getAdditions("portal"))
                .thenThrow(new ApiUnavailableException("HTTP 503 on /additions", 503, "down"));

        assertThatThrownBy(() -> service.aggregate("Portal"))
                .isInstanceOf(ApiUnavailableException.class)
                .hasMessageContaining("HTTP 503");
    }

    @Test
    void aggregate_continuesWhenSubfetchReturns404() {
        var portal = RawgDtoFixtures.minimalGame("portal", "Portal");
        var detail = RawgDtoFixtures.detailWithCounts("portal", "Portal", 5, 0, 0, 0);
        var rawg = stubDetails("portal", "Portal");

        when(client.searchByName("Portal", 10)).thenReturn(List.of(portal));
        when(mapper.pickExactMatch(List.of(portal), "Portal")).thenReturn(Optional.of(portal));
        lenient().when(mapper.pickClosestByLevenshtein(any(), anyString())).thenReturn(Optional.empty());
        when(client.getDetails("portal")).thenReturn(Optional.of(detail));
        when(client.getAdditions("portal"))
                .thenThrow(new ApiUnavailableException("HTTP 404 on /additions", 404, "none"));
        lenient().when(mapper.toDetails(eq(detail), anyList(), anyList(), anyList(), anyList(), any())).thenReturn(rawg);

        AggregatedGame result = service.aggregate("Portal");

        assertThat(result).isNotNull();
        assertThat(result.rawg()).isSameAs(rawg);
    }

    @Test
    void aggregate_continuesWhenNonApiSubfetchThrows() {
        var portal = RawgDtoFixtures.minimalGame("portal", "Portal");
        var detail = RawgDtoFixtures.detailWithCounts("portal", "Portal", 5, 0, 0, 0);
        var rawg = stubDetails("portal", "Portal");

        when(client.searchByName("Portal", 10)).thenReturn(List.of(portal));
        when(mapper.pickExactMatch(List.of(portal), "Portal")).thenReturn(Optional.of(portal));
        lenient().when(mapper.pickClosestByLevenshtein(any(), anyString())).thenReturn(Optional.empty());
        when(client.getDetails("portal")).thenReturn(Optional.of(detail));
        when(client.getAdditions("portal")).thenThrow(new IllegalStateException("oops"));
        lenient().when(mapper.toDetails(eq(detail), anyList(), anyList(), anyList(), anyList(), any())).thenReturn(rawg);

        AggregatedGame result = service.aggregate("Portal");

        assertThat(result).isNotNull();
        assertThat(result.rawg()).isSameAs(rawg);
    }

    @Test
    void aggregate_fetchesScreenshotsWhenShortListNull() {
        var portal = RawgDtoFixtures.minimalGame("portal", "Portal");
        var detail = mock(RawgGameDto.class);
        when(detail.slug()).thenReturn("portal");
        when(detail.name()).thenReturn("Portal");
        when(detail.additionsCount()).thenReturn(0);
        when(detail.creatorsCount()).thenReturn(0);
        when(detail.moviesCount()).thenReturn(0);
        when(detail.screenshotsCount()).thenReturn(7);
        when(detail.shortScreenshots()).thenReturn(null);
        var rawg = stubDetails("portal", "Portal");

        when(client.searchByName("Portal", 10)).thenReturn(List.of(portal));
        when(mapper.pickExactMatch(List.of(portal), "Portal")).thenReturn(Optional.of(portal));
        lenient().when(mapper.pickClosestByLevenshtein(any(), anyString())).thenReturn(Optional.empty());
        when(client.getDetails("portal")).thenReturn(Optional.of(detail));
        when(client.getScreenshots("portal")).thenReturn(List.of());
        when(mapper.toDetails(eq(detail), anyList(), anyList(), anyList(), anyList(), any())).thenReturn(rawg);

        service.aggregate("Portal");

        verify(client, times(1)).getScreenshots("portal");
    }

    @Test
    void aggregate_throwsOnEmptyName() {
        assertThatThrownBy(() -> service.aggregate(""))
                .isInstanceOf(GameNotFoundException.class)
                .hasMessageContaining("empty name");
        assertThatThrownBy(() -> service.aggregate(null))
                .isInstanceOf(GameNotFoundException.class)
                .hasMessageContaining("empty name");
    }

    @Test
    void aggregate_usesDefaultPageSize10() {
        var portal = RawgDtoFixtures.minimalGame("portal", "Portal");
        var detail = RawgDtoFixtures.detailWithCounts("portal", "Portal", 0, 0, 0, 0);
        var rawg = stubDetails("portal", "Portal");

        when(client.searchByName("Portal", 10)).thenReturn(List.of(portal));
        when(mapper.pickExactMatch(List.of(portal), "Portal")).thenReturn(Optional.of(portal));
        lenient().when(mapper.pickClosestByLevenshtein(any(), anyString())).thenReturn(Optional.empty());
        when(client.getDetails("portal")).thenReturn(Optional.of(detail));
        when(mapper.toDetails(eq(detail), anyList(), anyList(), anyList(), anyList(), any())).thenReturn(rawg);

        service.aggregate("Portal");

        verify(client).searchByName("Portal", 10);
    }

    @Test
    void aggregate_usesCustomPageSize() {
        var portal = RawgDtoFixtures.minimalGame("portal", "Portal");
        var detail = RawgDtoFixtures.detailWithCounts("portal", "Portal", 0, 0, 0, 0);
        var rawg = stubDetails("portal", "Portal");

        when(client.searchByName("Portal", 25)).thenReturn(List.of(portal));
        when(mapper.pickExactMatch(List.of(portal), "Portal")).thenReturn(Optional.of(portal));
        lenient().when(mapper.pickClosestByLevenshtein(any(), anyString())).thenReturn(Optional.empty());
        when(client.getDetails("portal")).thenReturn(Optional.of(detail));
        when(mapper.toDetails(eq(detail), anyList(), anyList(), anyList(), anyList(), any())).thenReturn(rawg);

        service.aggregate("Portal", 25);

        verify(client).searchByName("Portal", 25);
    }

    @Test
    void aggregate_usesShortScreenshotsWhenCountMatches() {
        var portal = RawgDtoFixtures.minimalGame("portal", "Portal");
        var shortShots = List.of(
                new RawgScreenshotDto(1L, "https://x/1.jpg", 0, 0, false),
                new RawgScreenshotDto(2L, "https://x/2.jpg", 0, 0, false));
        var detail = mock(RawgGameDto.class);
        when(detail.slug()).thenReturn("portal");
        when(detail.name()).thenReturn("Portal");
        when(detail.additionsCount()).thenReturn(0);
        when(detail.creatorsCount()).thenReturn(0);
        when(detail.moviesCount()).thenReturn(0);
        when(detail.screenshotsCount()).thenReturn(2);
        when(detail.shortScreenshots()).thenReturn(shortShots);
        var rawg = stubDetails("portal", "Portal");

        when(client.searchByName("Portal", 10)).thenReturn(List.of(portal));
        when(mapper.pickExactMatch(List.of(portal), "Portal")).thenReturn(Optional.of(portal));
        lenient().when(mapper.pickClosestByLevenshtein(any(), anyString())).thenReturn(Optional.empty());
        when(client.getDetails("portal")).thenReturn(Optional.of(detail));
        when(mapper.toDetails(eq(detail), anyList(), anyList(), anyList(), anyList(), any())).thenReturn(rawg);

        service.aggregate("Portal");

        verify(client, never()).getScreenshots(anyString());
    }

    @Test
    void aggregate_fetchesScreenshotsWhenCountExceedsShortList() {
        var portal = RawgDtoFixtures.minimalGame("portal", "Portal");
        var shortShots = List.of(
                new RawgScreenshotDto(1L, "https://x/1.jpg", 0, 0, false));
        var detail = mock(RawgGameDto.class);
        when(detail.slug()).thenReturn("portal");
        when(detail.name()).thenReturn("Portal");
        when(detail.additionsCount()).thenReturn(0);
        when(detail.creatorsCount()).thenReturn(0);
        when(detail.moviesCount()).thenReturn(0);
        when(detail.screenshotsCount()).thenReturn(10);
        when(detail.shortScreenshots()).thenReturn(shortShots);
        var rawg = stubDetails("portal", "Portal");

        when(client.searchByName("Portal", 10)).thenReturn(List.of(portal));
        when(mapper.pickExactMatch(List.of(portal), "Portal")).thenReturn(Optional.of(portal));
        lenient().when(mapper.pickClosestByLevenshtein(any(), anyString())).thenReturn(Optional.empty());
        when(client.getDetails("portal")).thenReturn(Optional.of(detail));
        when(client.getScreenshots("portal")).thenReturn(List.of());
        when(mapper.toDetails(eq(detail), anyList(), anyList(), anyList(), anyList(), any())).thenReturn(rawg);

        service.aggregate("Portal");

        verify(client, times(1)).getScreenshots("portal");
    }

    private static RawgDetails stubDetails(String slug, String name) {
        return new RawgDetails(
                slug, name, name, null,
                null, null, null, null, null,
                null, null, null, 0, 0, 0, 0,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(),
                false, null, null, List.of(), 0, 0, 0, null, 0, 0, 0, 0, 0,
                null, List.of(), null, List.of(), List.of(),
                java.util.Map.of(), java.util.Map.of(), 0,
                Instant.parse("2026-01-01T00:00:00Z"));
    }
}

