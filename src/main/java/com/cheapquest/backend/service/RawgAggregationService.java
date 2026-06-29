package com.cheapquest.backend.service;

import com.cheapquest.backend.client.RawgClient;
import com.cheapquest.backend.domain.AggregatedGame;
import com.cheapquest.backend.domain.rawg.RawgDetails;
import com.cheapquest.backend.dto.rawg.RawgCreatorDto;
import com.cheapquest.backend.dto.rawg.RawgGameDto;
import com.cheapquest.backend.dto.rawg.RawgMovieDto;
import com.cheapquest.backend.dto.rawg.RawgScreenshotDto;
import com.cheapquest.backend.exception.GameNotFoundException;
import com.cheapquest.backend.mapper.RawgMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RawgAggregationService {

    private static final Logger log = LoggerFactory.getLogger(RawgAggregationService.class);
    private static final int DEFAULT_SEARCH_PAGE_SIZE = 10;

    private final RawgClient client;
    private final RawgMapper mapper;
    private final Clock clock;

    public RawgAggregationService(RawgClient client, RawgMapper mapper) {
        this(client, mapper, Clock.systemUTC());
    }

    public RawgAggregationService(RawgClient client, RawgMapper mapper, Clock clock) {
        this.client = client;
        this.mapper = mapper;
        this.clock = clock;
    }

    public AggregatedGame aggregate(String name) {
        return aggregate(name, DEFAULT_SEARCH_PAGE_SIZE);
    }

    public AggregatedGame aggregate(String name, int searchPageSize) {
        if (name == null || name.isBlank()) {
            throw new GameNotFoundException("empty name");
        }

        List<RawgGameDto> searchResults = client.searchByName(name, searchPageSize);
        log.debug("rawg_search_results target=\"{}\" count={}", name, searchResults.size());

        RawgGameDto picked = mapper.pickExactMatch(searchResults, name)
                .or(() -> mapper.pickClosestByLevenshtein(searchResults, name))
                .orElseThrow(() -> new GameNotFoundException(
                        "no RAWG match for \"" + name + "\" (got " + searchResults.size() + " candidates)"));

        log.info("rawg_picked name=\"{}\" slug={} id={}", name, picked.slug(), picked.id());

        RawgGameDto detail = client.getDetails(picked.slug())
                .orElseThrow(() -> new GameNotFoundException(
                        "no RAWG detail for slug=" + picked.slug()));

        List<RawgGameDto> additions = safeFetch(
                detail.additionsCount() > 0,
                () -> client.getAdditions(detail.slug()),
                "additions");

        List<RawgCreatorDto> creators = safeFetch(
                detail.creatorsCount() > 0,
                () -> client.getDevelopmentTeam(detail.slug()),
                "development-team");

        List<RawgMovieDto> movies = safeFetch(
                detail.moviesCount() > 0,
                () -> client.getMovies(detail.slug()),
                "movies");

        List<RawgScreenshotDto> screenshots = resolveScreenshots(detail);

        RawgDetails rawg = mapper.toDetails(detail, movies, screenshots, additions, creators);

        return new AggregatedGame(
                name,
                rawg.name(),
                rawg.slug(),
                null,
                rawg,
                Instant.now(clock));
    }

    private List<RawgScreenshotDto> resolveScreenshots(RawgGameDto detail) {
        int shortCount = detail.shortScreenshots() == null ? 0 : detail.shortScreenshots().size();
        if (detail.screenshotsCount() > shortCount) {
            return safeFetch(true, () -> client.getScreenshots(detail.slug()), "screenshots");
        }
        return Optional.ofNullable(detail.shortScreenshots()).orElseGet(List::of);
    }

    private <T> List<T> safeFetch(boolean shouldFetch, java.util.function.Supplier<List<T>> call, String label) {
        if (!shouldFetch) {
            return List.of();
        }
        try {
            return call.get();
        } catch (RuntimeException e) {
            log.warn("rawg_subfetch_failed label={} error={}: {}",
                    label, e.getClass().getSimpleName(), e.getMessage());
            return List.of();
        }
    }
}
