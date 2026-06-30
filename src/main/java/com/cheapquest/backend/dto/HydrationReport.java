package com.cheapquest.backend.dto;

import java.util.List;

/**
 * Operational summary of a hydration run, produced by
 * {@code GameHydrationService.hydrateAll()}. Counters split the
 * processed games by the resulting {@code ValidationStatus} so
 * the operator can see at a glance how many docs are complete,
 * partial or empty.
 *
 * <p>{@code failures} lists the slugs whose Firebase update
 * itself failed (network, auth, etc.) and is distinct from
 * {@code empty}, which is a successful update of an EMPTY
 * report.
 */
public record HydrationReport(
        int processed,
        int complete,
        int partial,
        int empty,
        int failed,
        long durationMs,
        List<String> failures) {
}
