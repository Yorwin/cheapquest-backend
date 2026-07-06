package com.cheapquest.backend.service.sections;

import com.cheapquest.backend.domain.sections.SectionName;
import com.cheapquest.backend.domain.sections.SectionSnapshot;
import com.cheapquest.backend.dto.firebase.sections.SectionSnapshotDto;
import com.cheapquest.backend.exception.FirebaseUnavailableException;
import com.cheapquest.backend.mapper.SectionSnapshotMapper;
import com.google.api.core.ApiFuture;
import com.google.api.gax.rpc.ApiException;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldPath;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreException;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.cloud.firestore.WriteBatch;
import io.grpc.Status;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Firestore-backed implementation of {@link SectionStore}.
 * Owns the {@code sections/{YYYY-MM-DD}/items/{slug}} (history)
 * and {@code sections/latest/items/{slug}} (live mirror)
 * subcollections. The {@code write} operation uses a Firestore
 * {@link WriteBatch} so the two destinations are written in a
 * single atomic transaction: a half-failed write does not
 * leave the latest mirror stale relative to history.
 *
 * <p>The {@code read*} methods route through a private retry
 * helper that mirrors the policy of {@code FirebaseClient}:
 * transient gRPC failures (unavailable, deadline exceeded,
 * internal, resource exhausted, aborted) are retried with
 * exponential backoff up to {@code maxRetries} additional
 * attempts; permanent failures propagate immediately wrapped
 * in {@link FirebaseUnavailableException}. The retry helper
 * is duplicated here rather than extracted to a shared
 * utility so this commit does not touch {@code FirebaseClient};
 * a future refactor can fold the two together.
 *
 * <p>The store is stateless and thread-safe. The instance is
 * created once at startup in {@code App.runServe} and shared
 * across the admin endpoint, the public read endpoint and the
 * {@code SectionsService}.
 */
public final class FirestoreSectionStore implements SectionStore {

    private static final Logger log = LoggerFactory.getLogger(FirestoreSectionStore.class);

    /** gRPC codes that indicate a transient backend condition. Retried. */
    private static final List<Status.Code> TRANSIENT_CODES = List.of(
            Status.Code.UNAVAILABLE,
            Status.Code.DEADLINE_EXCEEDED,
            Status.Code.INTERNAL,
            Status.Code.RESOURCE_EXHAUSTED,
            Status.Code.ABORTED);

    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long DEFAULT_BASE_DELAY_MILLIS = 200L;
    private static final long MAX_DELAY_MILLIS = 2_000L;

    /** Sentinel doc id under {@code sections/} that holds the live mirror. */
    static final String LATEST_SENTINEL = "latest";
    /** Subcollection name that holds the per-section snapshots. */
    static final String ITEMS_SUBCOLLECTION = "items";

    private final Firestore firestore;
    private final String sectionsPath;
    private final SectionSnapshotMapper mapper;
    private final int maxRetries;
    private final long baseDelayMillis;

    public FirestoreSectionStore(Firestore firestore, String sectionsPath,
            SectionSnapshotMapper mapper) {
        this(firestore, sectionsPath, mapper,
                DEFAULT_MAX_RETRIES, DEFAULT_BASE_DELAY_MILLIS);
    }

    FirestoreSectionStore(Firestore firestore, String sectionsPath,
            SectionSnapshotMapper mapper, int maxRetries, long baseDelayMillis) {
        this.firestore = Objects.requireNonNull(firestore, "firestore");
        this.sectionsPath = Objects.requireNonNull(sectionsPath, "sectionsPath");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.maxRetries = maxRetries;
        this.baseDelayMillis = baseDelayMillis;
        log.debug("section_store_initialized sectionsPath={} maxRetries={} baseDelayMillis={}",
                sectionsPath, maxRetries, baseDelayMillis);
    }

    @Override
    public void write(SectionSnapshot snapshot) {
        SectionSnapshotDto dto = mapper.toDto(snapshot);
        String dateStr = snapshot.date().toString();
        String slug = snapshot.name().slug();
        DocumentReference historyRef = itemRef(dateStr, slug);
        DocumentReference latestRef = itemRef(LATEST_SENTINEL, slug);
        await("write-section", slug, () -> {
            WriteBatch batch = firestore.batch();
            batch.set(historyRef, dto);
            batch.set(latestRef, dto);
            return batch.commit();
        });
        log.info("section_written name={} date={} itemsKept={}",
                slug, dateStr, dto.items().size());
    }

    @Override
    public Optional<SectionSnapshot> read(SectionName name, LocalDate date) {
        return readItem(date.toString(), name.slug());
    }

    @Override
    public Optional<SectionSnapshot> readLatest(SectionName name) {
        return readItem(LATEST_SENTINEL, name.slug());
    }

    @Override
    public Map<SectionName, SectionSnapshot> readAllLatest() {
        CollectionReference items = firestore.collection(sectionsPath)
                .document(LATEST_SENTINEL)
                .collection(ITEMS_SUBCOLLECTION);
        QuerySnapshot snapshot = await("reading-section-latest", LATEST_SENTINEL,
                () -> orderedGet(items));
        Map<SectionName, SectionSnapshot> out = new EnumMap<>(SectionName.class);
        for (QueryDocumentSnapshot d : snapshot.getDocuments()) {
            SectionSnapshotDto dto = d.toObject(SectionSnapshotDto.class);
            if (dto == null) {
                continue;
            }
            SectionSnapshot snap = mapper.fromDto(dto);
            out.put(snap.name(), snap);
        }
        log.debug("section_latest_loaded count={}", out.size());
        return out;
    }

    private Optional<SectionSnapshot> readItem(String dateKey, String slug) {
        DocumentReference ref = itemRef(dateKey, slug);
        DocumentSnapshot snap = await("reading-section", dateKey + "/" + slug,
                ref::get);
        if (!snap.exists()) {
            return Optional.empty();
        }
        SectionSnapshotDto dto = snap.toObject(SectionSnapshotDto.class);
        if (dto == null) {
            return Optional.empty();
        }
        return Optional.of(mapper.fromDto(dto));
    }

    private DocumentReference itemRef(String dateKey, String slug) {
        return firestore.collection(sectionsPath)
                .document(dateKey)
                .collection(ITEMS_SUBCOLLECTION)
                .document(slug);
    }

    /**
     * Resolves an {@link ApiFuture}, translating every SDK failure
     * into a single {@link FirebaseUnavailableException} tagged
     * with the operation and the subject. Transient gRPC failures
     * (unavailable, deadline exceeded, internal, resource
     * exhausted, aborted) are retried with exponential backoff
     * up to {@code maxRetries} additional attempts; permanent
     * failures propagate immediately. The first
     * {@link InterruptedException} aborts the loop and re-raises
     * as a {@link FirebaseUnavailableException} with the interrupt
     * flag set on the current thread.
     *
     * <p>This is the same shape as {@code FirebaseClient.await};
     * see that method for the full rationale.
     *
     * <p>TODO: extract into a shared {@code FirestoreRetrier}
     * utility that both this and {@code FirebaseClient} use. The
     * third user (the future {@code FirestoreDealsSeenStore}
     * needed by the "nuevas ofertas" section) is the natural
     * trigger for that refactor; until then, the duplication is
     * small and self-contained.
     */
    private <T> T await(String op, String subject, Supplier<ApiFuture<T>> futureSupplier) {
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return futureSupplier.get().get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FirebaseUnavailableException("interrupted " + op + " " + subject, e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (attempt < maxRetries && isTransient(cause)) {
                    long delay = computeBackoffMillis(attempt);
                    log.warn("section_store_retry op={} subject={} attempt={} reason={} delayMs={}",
                            op, subject, attempt + 1, statusOf(cause), delay, cause);
                    sleep(delay);
                    continue;
                }
                throw new FirebaseUnavailableException("failed " + op + " " + subject, cause);
            } catch (RuntimeException e) {
                if (attempt < maxRetries && isTransient(e)) {
                    long delay = computeBackoffMillis(attempt);
                    log.warn("section_store_retry op={} subject={} attempt={} reason={} delayMs={}",
                            op, subject, attempt + 1, statusOf(e), delay, e);
                    sleep(delay);
                    continue;
                }
                throw new FirebaseUnavailableException("failed " + op + " " + subject, e);
            }
        }
        throw new IllegalStateException("unreachable: retry loop must return or throw");
    }

    private static boolean isTransient(Throwable t) {
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

    private static String statusOf(Throwable t) {
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

    private long computeBackoffMillis(int attempt) {
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

    private static ApiFuture<QuerySnapshot> orderedGet(CollectionReference col) {
        return col.orderBy(FieldPath.documentId()).get();
    }
}
