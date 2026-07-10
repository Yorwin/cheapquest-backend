package com.cheapquest.backend.service.sections;

import com.cheapquest.backend.client.FirestoreRetrier;
import com.cheapquest.backend.domain.sections.SectionName;
import com.cheapquest.backend.domain.sections.SectionSnapshot;
import com.cheapquest.backend.dto.firebase.sections.SectionSnapshotDto;
import com.cheapquest.backend.mapper.SectionSnapshotMapper;
import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldPath;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.cloud.firestore.WriteBatch;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 * <p>The {@code read*} methods route through the shared
 * {@link FirestoreRetrier} (same policy as the DAO layer):
 * transient gRPC failures (unavailable, deadline exceeded,
 * internal, resource exhausted, aborted) are retried with
 * exponential backoff; permanent failures propagate immediately
 * wrapped in {@code FirebaseUnavailableException}.
 *
 * <p>The store is stateless and thread-safe. The instance is
 * created once at startup in {@code App.runServe} and shared
 * across the admin endpoint, the public read endpoint and the
 * {@code SectionsService}.
 */
public final class FirestoreSectionStore implements SectionStore {

    private static final Logger log = LoggerFactory.getLogger(FirestoreSectionStore.class);

    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long DEFAULT_BASE_DELAY_MILLIS = 200L;

    /** Sentinel doc id under {@code sections/} that holds the live mirror. */
    static final String LATEST_SENTINEL = "latest";
    /** Subcollection name that holds the per-section snapshots. */
    static final String ITEMS_SUBCOLLECTION = "items";

    private final Firestore firestore;
    private final String sectionsPath;
    private final SectionSnapshotMapper mapper;
    private final FirestoreRetrier retrier;

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
        this.retrier = new FirestoreRetrier(maxRetries, baseDelayMillis);
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
     * Delegates to the shared {@link FirestoreRetrier}. See that
     * class for the full retry policy.
     */
    private <T> T await(String op, String subject, Supplier<ApiFuture<T>> futureSupplier) {
        return retrier.await(op, subject, futureSupplier);
    }

    private static ApiFuture<QuerySnapshot> orderedGet(CollectionReference col) {
        return col.orderBy(FieldPath.documentId()).get();
    }
}
