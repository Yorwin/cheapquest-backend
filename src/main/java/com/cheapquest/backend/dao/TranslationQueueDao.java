package com.cheapquest.backend.dao;

import com.cheapquest.backend.dto.firebase.TranslationFailedDoc;
import com.cheapquest.backend.dto.firebase.TranslationPendingDoc;
import java.time.Instant;
import java.util.List;

/**
 * Persistence boundary for the translation queue
 * ({@code translations-pending} collection) and its DLQ
 * ({@code translations-failed} collection). Mirrors the existing
 * Firestore-backed surface for these operations;
 * implementations may use Firestore, SQL, or any other store as
 * long as the CRUD contract is preserved.
 *
 * <p>Document id is {@code "{slug}_{locale}"} (e.g.
 * {@code portal_es}) so two concurrent enqueues from two
 * hydration runs land on the same doc and the second one is
 * rejected with {@code ALREADY_EXISTS}. The {@code moveToFailed}
 * method is a single logical operation (write to
 * {@code translations-failed} + delete from
 * {@code translations-pending}) but is not required to be
 * atomic at the storage level.
 */
public interface TranslationQueueDao {

    /**
     * Idempotent enqueue: if a pending entry already exists for
     * the (slug, locale), the call is a no-op.
     */
    void enqueue(String slug, String locale, Instant sourceFetchedAt);

    /**
     * Read every entry in the {@code translations-pending}
     * collection. The result is materialised into a {@code List}
     * so the caller can iterate it more than once without
     * re-reading.
     */
    List<TranslationPendingDoc> readPending();

    /**
     * Read a single pending entry, or {@code null} if it does not
     * exist. Used by the failure path to preserve the original
     * {@code sourceFetchedAt}.
     */
    TranslationPendingDoc readOne(String slug, String locale);

    /**
     * Increment the per-attempt counter on a translation pending
     * entry. Writes the new doc over the existing one, preserving
     * the original {@code sourceFetchedAt}. Called by the
     * translation pipeline after a non-fatal failure.
     */
    void recordFailure(String slug, String locale, int newAttempts,
            Instant lastAttemptAt, String lastError);

    /**
     * Remove a (slug, locale) from the translation queue. Called
     * after a successful translation.
     */
    void removeFromPending(String slug, String locale);

    /**
     * Move a (slug, locale) to the translation DLQ. Creates the
     * {@code translations-failed} doc and removes the entry from
     * {@code translations-pending} in one call.
     */
    void moveToFailed(TranslationFailedDoc doc);
}
