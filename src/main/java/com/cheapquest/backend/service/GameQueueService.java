package com.cheapquest.backend.service;

import com.cheapquest.backend.dao.HydrationQueueDao;
import com.cheapquest.backend.dto.firebase.FailedDoc;
import com.cheapquest.backend.dto.firebase.PendingDoc;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Read-only access to the hydration queues
 * ({@code pending} and {@code failed}). Wraps the two
 * {@link HydrationQueueDao} read methods and projects the
 * Firestore DTOs onto a single, JSON-friendly shape so the
 * HTTP layer does not have to special-case {@link PendingDoc}
 * vs {@link FailedDoc} (the latter carries an extra
 * {@code firstAttemptAt} field that the former does not).
 *
 * <p>Both queries are bounded by Firestore's default
 * 1000-document / 1 MiB page; if the queue grows past that
 * a follow-up read needs pagination, which is not in scope
 * here (queues are normally small).
 */
public final class GameQueueService {

    /** Which queue to read. */
    public enum Status {
        PENDING, FAILED
    }

    private final HydrationQueueDao hydrationQueueDao;

    public GameQueueService(HydrationQueueDao hydrationQueueDao) {
        this.hydrationQueueDao = Objects.requireNonNull(hydrationQueueDao, "hydrationQueueDao");
    }

    /**
     * Read the requested queue. {@link Status#PENDING} maps to
     * {@link HydrationQueueDao#readPending()}; {@link Status#FAILED}
     * maps to {@link HydrationQueueDao#readFailed()}.
     */
    public List<QueueEntry> list(Status status) {
        Objects.requireNonNull(status, "status");
        return switch (status) {
            case PENDING -> hydrationQueueDao.readPending().stream()
                    .map(GameQueueService::fromPending)
                    .toList();
            case FAILED -> hydrationQueueDao.readFailed().stream()
                    .map(GameQueueService::fromFailed)
                    .toList();
        };
    }

    private static QueueEntry fromPending(PendingDoc p) {
        return new QueueEntry(p.slug(), p.attempts(), null,
                p.lastAttemptAt(), p.lastError());
    }

    private static QueueEntry fromFailed(FailedDoc f) {
        return new QueueEntry(f.slug(), f.attempts(), f.firstAttemptAt(),
                f.lastAttemptAt(), f.lastError());
    }

    /**
     * One entry in either queue. {@code firstAttemptAt} is
     * populated only for the {@code failed} queue (the
     * {@code pending} docs do not carry it).
     */
    public record QueueEntry(
            String slug,
            int attempts,
            Instant firstAttemptAt,
            Instant lastAttemptAt,
            String lastError) {
    }
}
