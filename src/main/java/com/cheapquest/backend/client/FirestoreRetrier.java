package com.cheapquest.backend.client;

import com.cheapquest.backend.exception.FirebaseUnavailableException;
import com.google.api.core.ApiFuture;
import com.google.api.gax.rpc.ApiException;
import com.google.cloud.firestore.FirestoreException;
import io.grpc.Status;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single source of truth for the Firestore retry+backoff policy used by
 * the backend's storage layer. Resolves an {@link ApiFuture}, translating
 * every SDK failure (interruption, execution failure, runtime error)
 * into a single {@link FirebaseUnavailableException} tagged with the
 * operation and the subject. The cause of any wrapped exception is the
 * original throwable, so callers can still pattern-match on it.
 *
 * <p>Transient gRPC failures (unavailable, deadline exceeded, internal,
 * resource exhausted, aborted) are retried with exponential backoff up
 * to {@code maxAttempts} additional attempts. Permanent failures
 * (not found, permission denied, already exists, invalid argument)
 * propagate immediately so callers do not wait for an impossible retry.
 * The first {@link InterruptedException} aborts the loop and re-raises
 * as a {@link FirebaseUnavailableException} with the interrupt flag
 * set on the current thread.
 *
 * <p>The future is supplied lazily so that synchronous SDK failures
 * (e.g. a {@code get()} that throws before returning a future) are
 * also wrapped.
 *
 * <p>This class is stateless after construction and thread-safe.
 * Replaces the two duplicated {@code await} helpers that previously
 * lived in {@code FirestoreSectionStore}.
 */
public final class FirestoreRetrier {

    private static final Logger log = LoggerFactory.getLogger(FirestoreRetrier.class);

    /** gRPC codes that indicate a transient backend condition. Retried. */
    private static final List<Status.Code> TRANSIENT_CODES = List.of(
            Status.Code.UNAVAILABLE,
            Status.Code.DEADLINE_EXCEEDED,
            Status.Code.INTERNAL,
            Status.Code.RESOURCE_EXHAUSTED,
            Status.Code.ABORTED);

    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final long DEFAULT_BASE_DELAY_MILLIS = 200L;
    private static final long MAX_DELAY_MILLIS = 2_000L;

    private final int maxAttempts;
    private final long baseDelayMillis;
    private final Clock clock;

    public FirestoreRetrier() {
        this(DEFAULT_MAX_ATTEMPTS, DEFAULT_BASE_DELAY_MILLIS, Clock.systemUTC());
    }

    public FirestoreRetrier(int maxAttempts, long baseDelayMillis) {
        this(maxAttempts, baseDelayMillis, Clock.systemUTC());
    }

    public FirestoreRetrier(int maxAttempts, long baseDelayMillis, Clock clock) {
        this.maxAttempts = maxAttempts;
        this.baseDelayMillis = baseDelayMillis;
        this.clock = clock;
    }

    /**
     * Resolves the supplied {@link ApiFuture} with retries on transient
     * gRPC failures. On exhaustion or non-transient failure, throws a
     * {@link FirebaseUnavailableException} whose cause is the original
     * throwable.
     */
    public <T> T await(String op, String subject, Supplier<ApiFuture<T>> futureSupplier) {
        for (int attempt = 0; attempt <= maxAttempts; attempt++) {
            try {
                return futureSupplier.get().get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FirebaseUnavailableException("interrupted " + op + " " + subject, e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (attempt < maxAttempts && isTransient(cause)) {
                    long delay = computeBackoffMillis(attempt);
                    log.warn("firestore_retry op={} subject={} attempt={} reason={} delayMs={}",
                            op, subject, attempt + 1, statusOf(cause), delay, cause);
                    sleep(delay);
                    continue;
                }
                throw new FirebaseUnavailableException("failed " + op + " " + subject, cause);
            } catch (RuntimeException e) {
                if (attempt < maxAttempts && isTransient(e)) {
                    long delay = computeBackoffMillis(attempt);
                    log.warn("firestore_retry op={} subject={} attempt={} reason={} delayMs={}",
                            op, subject, attempt + 1, statusOf(e), delay, e);
                    sleep(delay);
                    continue;
                }
                throw new FirebaseUnavailableException("failed " + op + " " + subject, e);
            }
        }
        throw new IllegalStateException("unreachable: retry loop must return or throw");
    }

    /**
     * Returns {@code true} when the throwable carries a gRPC code that
     * this retrier considers transient. Handles both the Firestore
     * SDK's {@link FirestoreException} (which exposes an
     * {@link io.grpc.Status}) and the lower-level
     * {@link ApiException} (which exposes a
     * {@link com.google.api.gax.rpc.StatusCode}).
     */
    public static boolean isTransient(Throwable t) {
        if (t == null) {
            return false;
        }
        if (t instanceof FirestoreException fe && fe.getStatus() != null) {
            return TRANSIENT_CODES.contains(fe.getStatus().getCode());
        }
        if (t instanceof ApiException api
                && api.getStatusCode() != null
                && api.getStatusCode().getCode() != null) {
            return TRANSIENT_CODES.contains(
                    com.google.api.gax.rpc.StatusCode.Code.valueOf(
                            api.getStatusCode().getCode().name()));
        }
        return false;
    }

    /**
     * Returns {@code true} when the throwable carries the given gRPC
     * status code. Handles both {@link FirestoreException} (which
     * exposes an {@link io.grpc.Status}) and {@link ApiException}
     * (which exposes a {@link com.google.api.gax.rpc.StatusCode}).
     * Used by storage adapters to translate specific SDK errors into
     * domain-meaningful outcomes (e.g. {@code NOT_FOUND} →
     * {@code DocumentNotFoundException}, {@code ALREADY_EXISTS} →
     * idempotent no-op).
     */
    public static boolean hasStatus(Throwable t, Status.Code code) {
        if (t == null) {
            return false;
        }
        if (t instanceof FirestoreException fe && fe.getStatus() != null) {
            return fe.getStatus().getCode() == code;
        }
        if (t instanceof ApiException api
                && api.getStatusCode() != null
                && api.getStatusCode().getCode() != null) {
            return api.getStatusCode().getCode().name().equals(code.name());
        }
        return false;
    }

    /** Renders a human-readable status for log output. */
    public static String statusOf(Throwable t) {
        if (t instanceof FirestoreException fe && fe.getStatus() != null) {
            return fe.getStatus().getCode().name();
        }
        if (t instanceof ApiException api
                && api.getStatusCode() != null
                && api.getStatusCode().getCode() != null) {
            return api.getStatusCode().getCode().name();
        }
        return t.getClass().getSimpleName();
    }

    /**
     * Exponential backoff with full jitter: {@code base * 2^attempt},
     * capped at {@link #MAX_DELAY_MILLIS}, then uniformly randomised
     * in {@code [capped/2, capped)} to avoid the thundering-herd
     * effect when many callers retry in lockstep.
     */
    long computeBackoffMillis(int attempt) {
        long exp = baseDelayMillis * (1L << Math.min(attempt, 16));
        long capped = Math.min(exp, MAX_DELAY_MILLIS);
        long half = capped / 2;
        return half + ThreadLocalRandom.current().nextLong(Math.max(1, half));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FirebaseUnavailableException("interrupted during retry backoff", e);
        }
    }
}
