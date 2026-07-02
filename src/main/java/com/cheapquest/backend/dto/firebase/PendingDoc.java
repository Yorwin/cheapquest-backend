package com.cheapquest.backend.dto.firebase;

import java.time.Instant;

/**
 * One entry in the {@code pending} top-level collection: a slug
 * the cron is expected to hydrate on the next run, plus the
 * per-doc attempt counter that drives the 3-strike move to the
 * {@code failed} collection (see AGENTS.md §7).
 *
 * <p>{@code pending} is a top-level collection, not a subcollection
 * of {@code games}, even though AGENTS.md §4 documents the path
 * as {@code /games/pending}. Storing it at the top level avoids
 * the collection-group query that the subcollection layout
 * would require and keeps the {@code slug} as the document ID
 * for natural upsert semantics.
 */
public record PendingDoc(
        String slug,
        int attempts,
        Instant lastAttemptAt,
        String lastError) {

    public static PendingDoc firstAttempt(String slug) {
        return new PendingDoc(slug, 1, Instant.now(), null);
    }
}
