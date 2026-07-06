package com.cheapquest.backend.dto.firebase;

import java.time.Instant;

/**
 * One entry in the {@code translations-pending} top-level
 * collection: a (slug, locale) pair whose translation needs to
 * be (re-)run. The doc ID is {@code "{slug}_{locale}"} so a
 * re-enqueue from a hydration run is idempotent (ALREADY_EXISTS
 * is a no-op, the existing counter and lastError are preserved).
 *
 * <p>{@code sourceFetchedAt} is the {@code rawg.fetchedAt} at the
 * moment the entry was enqueued. The {@code TranslationService}
 * reads the game doc, picks up the source fields, and on success
 * writes the corresponding {@code LocaleBlock} with
 * {@code synced=true, sourceFetchedAt=this sourceFetchedAt, updatedAt=now}.
 *
 * <p>3 consecutive failures move the entry to
 * {@code translations-failed/{slug}_{locale}} (same pattern as
 * the hydration {@code pending} -> {@code failed} flow).
 */
public record TranslationPendingDoc(
        String slug,
        String locale,
        Instant sourceFetchedAt,
        int attempts,
        Instant lastAttemptAt,
        String lastError) {

    public static TranslationPendingDoc firstAttempt(String slug, String locale, Instant sourceFetchedAt) {
        return new TranslationPendingDoc(slug, locale, sourceFetchedAt, 1, Instant.now(), null);
    }
}
