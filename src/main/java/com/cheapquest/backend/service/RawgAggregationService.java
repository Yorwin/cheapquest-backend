package com.cheapquest.backend.service;

import com.cheapquest.backend.client.RawgClient;
import com.cheapquest.backend.domain.AggregatedGame;
import com.cheapquest.backend.domain.rawg.RawgDetails;
import com.cheapquest.backend.dto.rawg.RawgCreatorDto;
import com.cheapquest.backend.dto.rawg.RawgGameDto;
import com.cheapquest.backend.dto.rawg.RawgMovieDto;
import com.cheapquest.backend.dto.rawg.RawgScreenshotDto;
import com.cheapquest.backend.exception.ApiUnavailableException;
import com.cheapquest.backend.exception.GameNotFoundException;
import com.cheapquest.backend.mapper.RawgMapper;
import com.cheapquest.backend.util.StringUtils;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
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
        if (StringUtils.isBlank(name)) {
            throw new GameNotFoundException("empty name");
        }
        Instant start = Instant.now(clock);

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

        Optional<RawgGameDto> detailsByIdOpt = safeGetDetailsById(picked.id());

        RawgGameDto merged = detailsByIdOpt
                .map(byId -> mapper.mergeSearchAndDetails(picked, detail, byId))
                .orElse(detail);

        log.debug("rawg_merge_done slug={} id={} idCallSucceeded={}",
                merged.slug(), merged.id(), detailsByIdOpt.isPresent());

        List<RawgGameDto> additions = safeFetch(
                merged.additionsCount() > 0,
                () -> client.getAdditions(merged.slug()),
                "additions");

        List<RawgCreatorDto> creators = safeFetch(
                merged.creatorsCount() > 0,
                () -> client.getDevelopmentTeam(merged.slug()),
                "development-team");

        // The detail response's moviesCount is sometimes stale
        // (e.g. 0 when /games/{slug}/movies actually has data, or
        // the other way around for very recent edits), so we
        // always call the endpoint and let the mapper project
        // the result. The cost is one extra round-trip per
        // game; the upside is no false-negative when the
        // counter is wrong.
        List<RawgMovieDto> movies = safeFetch(
                true,
                () -> client.getMovies(merged.slug()),
                "movies");

        List<RawgScreenshotDto> screenshots = resolveScreenshots(merged);

        RawgDetails rawg = mapper.toDetails(merged, movies, screenshots, additions, creators,
                Instant.now(clock));

        log.debug("rawg_aggregate_done name=\"{}\" durationMs={} additions={} creators={} movies={} screenshots={}",
                name, Duration.between(start, Instant.now(clock)).toMillis(),
                additions.size(), creators.size(), movies.size(), screenshots.size());

        return new AggregatedGame(
                name,
                rawg.name(),
                rawg.slug(),
                null,
                rawg,
                Instant.now(clock));
    }

    private Optional<RawgGameDto> safeGetDetailsById(int id) {
        try {
            return client.getDetails(String.valueOf(id));
        } catch (ApiUnavailableException e) {
            log.warn("rawg_details_by_id_failed id={} status={}", id, e.status());
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("rawg_details_by_id_failed id={} error={}: {}",
                    id, e.getClass().getSimpleName(), e.getMessage(), e);
            return Optional.empty();
        }
    }

    private List<RawgScreenshotDto> resolveScreenshots(RawgGameDto detail) {
        int shortCount = detail.shortScreenshots() == null ? 0 : detail.shortScreenshots().size();
        if (detail.screenshotsCount() > shortCount) {
            return safeFetch(true, () -> client.getScreenshots(detail.slug()), "screenshots");
        }
        return Optional.ofNullable(detail.shortScreenshots()).orElseGet(List::of);
    }

    private <T> List<T> safeFetch(boolean shouldFetch, Supplier<List<T>> call, String label) {
        if (!shouldFetch) {
            return List.of();
        }
        try {
            return call.get();
        } catch (ApiUnavailableException e) {
            if (e.status() == 404) {
                return List.of();
            }
            log.error("rawg_subfetch_failed label={} status={} - aborting aggregation", label, e.status(), e);
            throw e;
        } catch (RuntimeException e) {
            log.warn("rawg_subfetch_failed label={} error={}: {}",
                    label, e.getClass().getSimpleName(), e.getMessage(), e);
            return List.of();
        }
    }
}
