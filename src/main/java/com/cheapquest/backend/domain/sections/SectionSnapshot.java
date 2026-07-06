package com.cheapquest.backend.domain.sections;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * The recomputed result for one section on one calendar day
 * (UTC). The same shape is persisted to
 * {@code sections/{date}/{name}} for history and mirrored to
 * {@code sections/latest/{name}} for cheap reads; the two
 * documents carry the same payload.
 *
 * <p>{@code totalCandidates} is the number of games the
 * builder inspected before filtering and limiting, so a
 * consumer can tell "the section is short because the catalog
 * is small" apart from "the section is short because most
 * games were filtered out". {@code itemsKept} is always
 * {@code items().size()}.
 */
public record SectionSnapshot(
        SectionName name,
        LocalDate date,
        Instant computedAt,
        int totalCandidates,
        int itemsKept,
        List<SectionItem> items) {

    public SectionSnapshot {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(computedAt, "computedAt");
        items = items == null ? List.of() : List.copyOf(items);
    }
}
