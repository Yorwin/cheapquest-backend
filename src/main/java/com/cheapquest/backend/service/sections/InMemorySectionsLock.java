package com.cheapquest.backend.service.sections;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-process {@link SectionsLock} backed by a single
 * {@link AtomicBoolean}. Two threads in the same JVM cannot
 * both win {@link #tryAcquire()}. A multi-JVM deployment would
 * need a Firestore-backed implementation (a {@code set} on
 * {@code /admin/sections-lock} with TTL); the in-memory version
 * is the right choice while the backend runs as a single
 * process.
 */
public final class InMemorySectionsLock implements SectionsLock {

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
