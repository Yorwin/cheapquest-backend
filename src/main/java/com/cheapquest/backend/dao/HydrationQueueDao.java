package com.cheapquest.backend.dao;

import com.cheapquest.backend.dto.firebase.FailedDoc;
import com.cheapquest.backend.dto.firebase.PendingDoc;
import java.time.Instant;
import java.util.List;

/**
 * Persistence boundary for the hydration queue ({@code pending}
 * collection) and its DLQ ({@code failed} collection). Mirrors the
 * Firestore-backed surface for these operations;
 * implementations may use Firestore, SQL, or any other store as
 * long as the CRUD contract is preserved.
 *
 * <p>Document id is the game slug. The {@code moveToFailed} method
 * is a single logical operation (write to {@code failed} + delete
 * from {@code pending}) but is not required to be atomic at the
 * storage level.
 */
public interface HydrationQueueDao {

    /**
     * Idempotent enqueue: if a pending doc already exists for the
     * slug the call is a no-op (a stale or duplicate enqueue from
     * the operator does not reset the attempt counter or wipe a
     * previous {@code lastError}). Use {@link #replacePending} if
     * you need to overwrite.
     */
    void enqueue(String slug);

    /**
     * Read every entry in the {@code pending} collection. The
     * result is materialised into a {@code List} so the caller can
     * iterate it more than once without triggering additional
     * reads.
     */
    List<PendingDoc> readPending();

    /**
     * Overwrite the pending entry for a slug, resetting the
     * attempt counter. Used by the operator to re-enqueue a doc
     * that was previously moved to {@code failed} and has since
     * been fixed.
     */
    void replacePending(PendingDoc pending);

    /**
     * Increment the attempt counter for a pending slug after a
     * failure. Writes back the same doc with the new counter,
     * the new {@code lastAttemptAt}, and the new {@code lastError}.
     */
    void recordFailure(String slug, int newAttempts,
            Instant lastAttemptAt, String lastError);

    /**
     * Remove a slug from the pending queue. Called after a
     * successful hydration so the next cron run does not
     * reprocess it.
     */
    void removeFromPending(String slug);

    /**
     * Read every entry in the {@code failed} DLQ. Materialised
     * into a {@code List} with the same rationale as
     * {@link #readPending()}.
     */
    List<FailedDoc> readFailed();

    /**
     * Move a slug to the DLQ after the configured number of
     * consecutive failures. Creates the {@code failed} doc and
     * removes the slug from {@code pending} in one call so a
     * partial failure (e.g. process killed between the two
     * operations) does not leave the slug bouncing between
     * queues.
     */
    void moveToFailed(FailedDoc doc);
}
