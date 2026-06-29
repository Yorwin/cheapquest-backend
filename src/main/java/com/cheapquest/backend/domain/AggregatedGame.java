package com.cheapquest.backend.domain;

import com.cheapquest.backend.domain.rawg.RawgDetails;
import java.time.Instant;
import java.util.Objects;

/**
 * Aggregate result of looking up a game across the configured sources.
 * Each source may be present or {@code null} independently so the caller can
 * disable a source by simply not invoking it.
 */
public record AggregatedGame(
        String cheapSharkTitle,
        String canonicalName,
        String rawgSlug,
        GameDeals cheapShark,
        RawgDetails rawg,
        Instant fetchedAt) {

    public AggregatedGame {
        Objects.requireNonNull(cheapSharkTitle, "cheapSharkTitle");
        Objects.requireNonNull(canonicalName, "canonicalName");
        Objects.requireNonNull(fetchedAt, "fetchedAt");
    }
}
