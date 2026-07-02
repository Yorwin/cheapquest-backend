package com.cheapquest.backend.dto;

import java.util.List;

/**
 * Operational summary of a hydration run, produced by
 * {@code GameHydrationService.hydrateAll()}. Counters split the
 * processed games by the resulting {@code ValidationStatus} so
 * the operator can see at a glance how many docs are complete,
 * partial, empty, skipped or failed.
 *
 * <p>{@code skipped} is a cadence outcome: neither CheapShark
 * nor RAWG was stale for that document, so the lookup was
 * skipped and the doc was not rewritten. {@code dealsRefreshed}
 * and {@code rawgRefreshed} count the per-source refreshes
 * (a doc can contribute to both if both sources were stale).
 * The three add up to: {@code skipped + complete + partial +
 * empty + failed = processed} (each doc has exactly one of
 * those five outcomes), and {@code dealsRefreshed + rawgRefreshed}
 * is the total number of source refreshes performed.
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
        int skipped,
        int failed,
        int dealsRefreshed,
        int rawgRefreshed,
        long durationMs,
        List<String> failures) {
}
