package com.cheapquest.backend.endpoint;

import com.cheapquest.backend.dto.HydrationReport;
import com.cheapquest.backend.exception.ConflictException;
import com.cheapquest.backend.service.GameHydrationService;
import java.time.Clock;
import java.util.Objects;

/**
 * Wraps {@link GameHydrationService#hydrateAll(boolean)} with the
 * single-flight {@link RefreshLock} so the admin endpoint can
 * run a full refresh and return a structured outcome. The lock
 * is released in {@code finally} so an exception during the
 * pipeline (e.g. Firestore down) does not leave the endpoint
 * permanently blocked; the next caller can try again.
 */
public final class RefreshService {

    private final RefreshLock lock;
    private final GameHydrationService hydration;
    private final Clock clock;

    public RefreshService(RefreshLock lock, GameHydrationService hydration, Clock clock) {
        this.lock = Objects.requireNonNull(lock, "lock");
        this.hydration = Objects.requireNonNull(hydration, "hydration");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Run a single refresh. Acquires the lock first; if the lock
     * is held by another caller, throws
     * {@link ConflictException} (mapped to HTTP 409) without
     * doing any work. On success or failure of the underlying
     * hydration, releases the lock before returning or
     * rethrowing.
     */
    public Outcome refresh(boolean force) {
        if (!lock.tryAcquire()) {
            throw new ConflictException("refresh already in progress");
        }
        long start = clock.millis();
        try {
            HydrationReport report = hydration.hydrateAll(force);
            return new Outcome(
                    "completed",
                    report.processed(),
                    report.failed(),
                    clock.millis() - start);
        } finally {
            lock.release();
        }
    }

    public record Outcome(String status, int processed, int failed, long durationMs) {
    }
}
