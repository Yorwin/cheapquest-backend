package com.cheapquest.backend.endpoint;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-process {@link RefreshLock} backed by a single
 * {@link AtomicBoolean}. Two threads in the same JVM cannot
 * both win {@link #tryAcquire()}. A multi-JVM deployment would
 * need the Firestore-backed implementation that AGENTS.md §8
 * describes; the in-memory version is the right choice while
 * the backend runs as a single process.
 */
public final class InMemoryRefreshLock implements RefreshLock {

    private final AtomicBoolean held = new AtomicBoolean(false);

    @Override
    public boolean tryAcquire() {
        return held.compareAndSet(false, true);
    }

    @Override
    public void release() {
        held.set(false);
    }

    @Override
    public boolean isHeld() {
        return held.get();
    }
}
