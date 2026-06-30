package com.cheapquest.backend.domain.validation;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * Result of inspecting an aggregated game for missing or empty fields.
 * The shape matches AGENTS.md §3 so it can be embedded as-is in the
 * future {@code Game} record.
 */
public record ValidationReport(
        ValidationStatus status,
        Set<GameField> missingFields,
        Instant lastFullFetchAt,
        Instant lastPartialFetchAt) {

    public ValidationReport {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(lastFullFetchAt, "lastFullFetchAt");
        missingFields = missingFields == null ? Set.of() : Set.copyOf(missingFields);
    }

    public static ValidationReport complete(Set<GameField> missingFields, Instant lastFullFetchAt) {
        return new ValidationReport(ValidationStatus.COMPLETE, missingFields, lastFullFetchAt, null);
    }

    public static ValidationReport partial(Set<GameField> missingFields, Instant lastFullFetchAt) {
        return new ValidationReport(ValidationStatus.PARTIAL, missingFields, lastFullFetchAt, null);
    }

    public static ValidationReport empty(Instant lastFullFetchAt) {
        return new ValidationReport(ValidationStatus.EMPTY, Set.of(), lastFullFetchAt, null);
    }
}
