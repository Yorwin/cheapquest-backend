package com.cheapquest.backend.endpoint;

/**
 * Single-flight guard for the refresh pipeline. The admin
 * endpoint acquires this before doing any work so two concurrent
 * crons (or a cron and a manual trigger) cannot start two
 * refreshes at the same time and double the Firestore cost.
 *
 * <p>An in-memory implementation is sufficient for the current
 * single-JVM deployment. The Firestore-backed lock that
 * AGENTS.md §8 describes (a {@code set} on {@code /admin/lock}
 * with TTL) would let two JVMs coordinate; the contract here is
 * the same so a future Firestore implementation can drop in
 * without changing the endpoint.
 */
public interface RefreshLock {

    /**
     * Try to take the lock. Returns {@code true} if the caller
     * now holds it, {@code false} if someone else does. The
     * check is atomic: two concurrent callers cannot both win.
     */
    boolean tryAcquire();

    /**
     * Release the lock. Must be called from the same code path
     * that won the {@link #tryAcquire()}, typically in a
     * {@code finally} block. Calling on an unheld lock is a
     * no-op (defensive: a second {@code release} must not throw).
     */
    void release();

    /**
     * Test-only inspection: is the lock currently held by anyone?
     */
    boolean isHeld();
}
