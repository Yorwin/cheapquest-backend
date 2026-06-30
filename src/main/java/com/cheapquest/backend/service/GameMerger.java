package com.cheapquest.backend.service;

import com.cheapquest.backend.domain.AggregatedGame;
import com.cheapquest.backend.domain.GameDeals;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Pure-function merger that combines a CheapShark result and a RAWG
 * result into a single {@link AggregatedGame} so downstream services
 * (validation, future translation, future write) can see both sources
 * at once. This is the seed of the orchestration that AGENTS.md §5
 * describes; the smoke test in {@code App.main} uses it directly until
 * a real orchestrator exists.
 */
public final class GameMerger {

    private final Clock clock;

    public GameMerger() {
        this(Clock.systemUTC());
    }

    public GameMerger(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public AggregatedGame merge(GameDeals deals, AggregatedGame rawgAgg) {
        if (deals == null && rawgAgg == null) {
            throw new IllegalArgumentException("at least one source must be non-null");
        }
        if (rawgAgg != null) {
            return new AggregatedGame(
                    rawgAgg.cheapSharkTitle(),
                    rawgAgg.canonicalName(),
                    rawgAgg.rawgSlug(),
                    deals,
                    rawgAgg.rawg(),
                    rawgAgg.fetchedAt());
        }
        return new AggregatedGame(
                deals.searchTitle(),
                deals.name(),
                null,
                deals,
                null,
                Instant.now(clock));
    }
}
