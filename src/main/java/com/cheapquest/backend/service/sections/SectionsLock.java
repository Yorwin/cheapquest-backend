package com.cheapquest.backend.service.sections;

/**
 * Single-flight guard for the daily section recompute. The
 * admin endpoint and the external cron both call into
 * {@code SectionsService.recomputeAll}, so the same lock is
 * needed to prevent two concurrent runs from double-writing
 * the snapshot collections.
 *
 * <p>The contract mirrors {@code RefreshLock}: a successful
 * {@link #tryAcquire()} is exclusive until {@link #release()}
 * is called, and {@link #release()} on an unheld lock is a
 * no-op (defensive: a second release must not throw).
 *
 * <p>An in-memory implementation is sufficient for the
 * current single-JVM deployment. The Firestore-backed
 * alternative (a {@code set} on {@code /admin/sections-lock}
 * with TTL) would let two JVMs coordinate; the contract here
 * is the same so a future Firestore implementation can drop
 * in without changing the service or the endpoint.
 */
public interface SectionsLock {

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
