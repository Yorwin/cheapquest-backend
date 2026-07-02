package com.cheapquest.backend.client;

import com.cheapquest.backend.config.AppProperties;
import com.cheapquest.backend.dto.firebase.FailedDoc;
import com.cheapquest.backend.dto.firebase.GameDocumentDto;
import com.cheapquest.backend.dto.firebase.HydrationPatch;
import com.cheapquest.backend.dto.firebase.PendingDoc;
import com.cheapquest.backend.exception.DocumentNotFoundException;
import com.cheapquest.backend.exception.FirebaseUnavailableException;
import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldPath;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.cloud.firestore.WriteResult;
import io.grpc.Status;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin wrapper over the Firestore SDK that exposes only the operations
 * the backend needs against the games collection. Wraps every SDK
 * exception in {@link FirebaseUnavailableException} so callers do not
 * have to catch the Firestore exception hierarchy.
 *
 * <p>The collection path comes from {@code firestore.collection.games-path}
 * (default {@code games}); the document ID is the RAWG slug.
 */
public final class FirebaseClient {

    private static final Logger log = LoggerFactory.getLogger(FirebaseClient.class);

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

    private final Firestore firestore;
    private final String collectionPath;
    private final String pendingCollectionPath;
    private final String failedCollectionPath;
    private final int pageSize;
    private final int maxRetries;
    private final long baseDelayMillis;

    public FirebaseClient(Firestore firestore, AppProperties props) {
        this(firestore,
                props.firestoreCollectionGamesPath(),
                props.firestoreCollectionPendingPath(),
                props.firestoreCollectionFailedPath(),
                props.firestoreReadPageSize(),
                DEFAULT_MAX_RETRIES,
                DEFAULT_BASE_DELAY_MILLIS);
    }

    FirebaseClient(Firestore firestore, String collectionPath, int pageSize) {
        this(firestore, collectionPath, "pending", "failed",
                pageSize, DEFAULT_MAX_RETRIES, DEFAULT_BASE_DELAY_MILLIS);
    }

    /** Convenience for retry-related tests: default paths, custom retry budget. */
    FirebaseClient(Firestore firestore, String collectionPath, int pageSize,
            int maxRetries, long baseDelayMillis) {
        this(firestore, collectionPath, "pending", "failed",
                pageSize, maxRetries, baseDelayMillis);
    }

    FirebaseClient(Firestore firestore, String collectionPath,
            String pendingCollectionPath, String failedCollectionPath,
            int pageSize, int maxRetries, long baseDelayMillis) {
        this.firestore = firestore;
        this.collectionPath = collectionPath;
        this.pendingCollectionPath = pendingCollectionPath;
        this.failedCollectionPath = failedCollectionPath;
        this.pageSize = pageSize;
        this.maxRetries = maxRetries;
        this.baseDelayMillis = baseDelayMillis;
        log.debug("firebase_client_initialized collectionPath={} pendingPath={} failedPath={} "
                        + "pageSize={} maxRetries={} baseDelayMillis={}",
                collectionPath, pendingCollectionPath, failedCollectionPath,
                pageSize, maxRetries, baseDelayMillis);
    }

    /**
     * Returns a lazy {@link Iterable} that walks the games collection
     * page by page. The Firestore SDK limits a single {@code get()} to
     * roughly 1000 documents / 1 MiB; this iterator uses
     * {@code limit(N) + startAfter(lastDoc)} to advance beyond that.
     * The default page size comes from {@code firestore.read-page-size}
     * (300) and is exposed for testing via the
     * {@link FirebaseClient(Firestore, String, int)} constructor.
     */
    public Iterable<GameDocumentDto> readAll() {
        return new PagingIterable();
    }

    public Optional<GameDocumentDto> readOne(String slug) {
        DocumentSnapshot snap = await("reading", slug,
                () -> gamesCollection().document(slug).get());
        if (!snap.exists()) {
            return Optional.empty();
        }
        return Optional.of(snap.toObject(GameDocumentDto.class));
    }

    /**
     * Read the {@code pending} collection. The result is
     * materialised into a {@code List} so the caller can iterate
     * it more than once without triggering additional reads
     * (see {@link PagingIterator} for the single-use caveat on
     * the {@code games} iterator).
     */
    public List<PendingDoc> readPending() {
        QuerySnapshot snapshot = await("reading-pending", pendingCollectionPath,
                () -> firestore.collection(pendingCollectionPath)
                        .orderBy(FieldPath.documentId())
                        .get());
        List<QueryDocumentSnapshot> docs = snapshot.getDocuments();
        List<PendingDoc> result = new ArrayList<>(docs.size());
        for (QueryDocumentSnapshot d : docs) {
            PendingDoc p = d.toObject(PendingDoc.class);
            if (p != null) {
                result.add(p);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Atomic create. Returns {@code true} if the document was created,
     * {@code false} if a document with that slug already exists.
     */
    public boolean createIfNotExists(String slug, GameDocumentDto dto) {
        DocumentReference ref = gamesCollection().document(slug);
        try {
            await("creating", slug, () -> ref.create(dto));
            return true;
        } catch (FirebaseUnavailableException e) {
            if (isAlreadyExists(e.getCause())) {
                return false;
            }
            throw e;
        }
    }

    /**
     * Enqueue a slug for the next hydration run. Idempotent: if a
     * pending doc already exists for the slug the call is a no-op
     * (a stale or duplicate enqueue from the operator does not
     * reset the attempt counter or wipe a previous {@code lastError}).
     * Use {@link #replacePending} if you need to overwrite.
     */
    public void addToPending(String slug) {
        DocumentReference ref = firestore.collection(pendingCollectionPath).document(slug);
        try {
            await("enqueue-pending", slug, () -> ref.create(PendingDoc.firstAttempt(slug)));
        } catch (FirebaseUnavailableException e) {
            if (isAlreadyExists(e.getCause())) {
                return;
            }
            throw e;
        }
    }

    /**
     * Overwrite the pending entry for a slug, resetting the
     * attempt counter. Used by the operator to re-enqueue a
     * doc that was previously moved to {@code failed} and has
     * since been fixed (e.g. after a network blip or a key
     * rotation).
     */
    public void replacePending(PendingDoc pending) {
        DocumentReference ref = firestore.collection(pendingCollectionPath).document(pending.slug());
        await("enqueue-pending-replace", pending.slug(), () -> ref.set(pending));
    }

    /**
     * Increment the attempt counter for a pending slug after a
     * failure. Writes back the same doc with the new counter,
     * the new {@code lastAttemptAt}, and the new {@code lastError}.
     */
    public void recordPendingFailure(String slug, int newAttempts, Instant lastAttemptAt, String lastError) {
        DocumentReference ref = firestore.collection(pendingCollectionPath).document(slug);
        PendingDoc updated = new PendingDoc(slug, newAttempts, lastAttemptAt, lastError);
        await("update-pending-failure", slug, () -> ref.set(updated));
    }

    /**
     * Remove a slug from the pending queue. Called after a
     * successful hydration so the next cron run does not
     * reprocess it.
     */
    public void removeFromPending(String slug) {
        DocumentReference ref = firestore.collection(pendingCollectionPath).document(slug);
        await("remove-pending", slug, ref::delete);
    }

    /**
     * Move a slug to the DLQ after the configured number of
     * consecutive failures. Creates the {@code failed} doc and
     * removes the slug from {@code pending} in one call so a
     * partial failure (e.g. process killed between the two
     * operations) does not leave the slug bouncing between
     * queues.
     */
    public void moveToFailed(FailedDoc doc) {
        DocumentReference failedRef = firestore.collection(failedCollectionPath).document(doc.slug());
        await("move-to-failed", doc.slug(), () -> failedRef.set(doc));
        DocumentReference pendingRef = firestore.collection(pendingCollectionPath).document(doc.slug());
        await("remove-pending-after-failed", doc.slug(), pendingRef::delete);
    }

    /**
     * Surgical update: only the fields present in the {@link HydrationPatch}
     * are rewritten (title, cheapshark, rawg, validationReport).
     * Throws {@link DocumentNotFoundException} when the document does
     * not exist (so the caller can distinguish a missing doc from a
     * genuine backend failure); rethrows other
     * {@link FirebaseUnavailableException}s as-is.
     *
     * <p>{@code locales} is intentionally NOT in the patch: it is
     * managed by a separate call to {@link #markLocaleSynced} so a
     * later translation service does not get clobbered on every
     * hydration.
     */
    public void update(String slug, HydrationPatch patch) {
        DocumentReference ref = gamesCollection().document(slug);
        try {
            await("updating", slug, () -> ref.update(patch.toFirestoreMap()));
        } catch (FirebaseUnavailableException e) {
            if (isNotFound(e.getCause())) {
                throw new DocumentNotFoundException("document missing: " + slug, e);
            }
            throw e;
        }
    }

    /**
     * Mark a single locale as synced via a dot-notation partial
     * update on the {@code locales} map. Only {@code synced} and
     * {@code updatedAt} are written, leaving the rest of the
     * document (and the rest of the locales map) untouched. This
     * is the only write path for {@code locales.en} from the
     * hydration side; {@code locales.es} and {@code locales.fr}
     * are owned by the future translation service.
     */
    public void markLocaleSynced(String slug, String lang, java.time.Instant syncedAt) {
        if (lang == null || lang.isBlank()) {
            throw new IllegalArgumentException("lang must be non-blank, got: " + lang);
        }
        DocumentReference ref = gamesCollection().document(slug);
        Map<String, Object> updates = new java.util.HashMap<>();
        updates.put("locales." + lang + ".synced", Boolean.TRUE);
        updates.put("locales." + lang + ".updatedAt", syncedAt.toString());
        await("mark-locale-synced", slug + "/" + lang, () -> ref.update(updates));
    }

    private CollectionReference gamesCollection() {
        return firestore.collection(collectionPath);
    }

    /**
     * Resolves an {@link ApiFuture}, translating every SDK failure
     * (interruption, execution failure, runtime error) into a single
     * {@link FirebaseUnavailableException} tagged with the operation
     * and the subject. The cause of any wrapped exception is the
     * original throwable, so callers can still pattern-match on it.
     *
     * <p>The future is supplied lazily so that synchronous SDK failures
     * (e.g. a {@code get()} that throws before returning a future) are
     * also wrapped.
     *
     * <p>Transient gRPC failures (unavailable, deadline exceeded,
     * internal, resource exhausted, aborted) are retried with
     * exponential backoff up to {@link #maxRetries} additional
     * attempts. Permanent failures (not found, permission denied,
     * already exists, invalid argument) propagate immediately so
     * callers do not wait for an impossible retry. The first
     * {@link InterruptedException} aborts the loop and re-raises as
     * a {@link FirebaseUnavailableException} with the interrupt
     * flag set on the current thread.
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
                    log.warn("firebase_retry op={} subject={} attempt={} reason={} delayMs={}",
                            op, subject, attempt + 1, statusOf(cause), delay, cause);
                    sleep(delay);
                    continue;
                }
                throw new FirebaseUnavailableException("failed " + op + " " + subject, cause);
            } catch (RuntimeException e) {
                if (attempt < maxRetries && isTransient(e)) {
                    long delay = computeBackoffMillis(attempt);
                    log.warn("firebase_retry op={} subject={} attempt={} reason={} delayMs={}",
                            op, subject, attempt + 1, statusOf(e), delay, e);
                    sleep(delay);
                    continue;
                }
                throw new FirebaseUnavailableException("failed " + op + " " + subject, e);
            }
        }
        throw new IllegalStateException("unreachable: retry loop must return or throw");
    }

    private static boolean isAlreadyExists(Throwable t) {
        return hasStatus(t, Status.Code.ALREADY_EXISTS);
    }

    private static boolean isNotFound(Throwable t) {
        return hasStatus(t, Status.Code.NOT_FOUND);
    }

    private static boolean isTransient(Throwable t) {
        if (t == null) {
            return false;
        }
        if (t instanceof com.google.cloud.firestore.FirestoreException fe
                && fe.getStatus() != null) {
            return TRANSIENT_CODES.contains(fe.getStatus().getCode());
        }
        if (t instanceof com.google.api.gax.rpc.ApiException api
                && api.getStatusCode() != null
                && api.getStatusCode().getCode() != null) {
            return TRANSIENT_CODES.contains(
                    com.google.api.gax.rpc.StatusCode.Code.valueOf(
                            api.getStatusCode().getCode().name()));
        }
        return false;
    }

    private static boolean hasStatus(Throwable t, Status.Code code) {
        if (t == null) {
            return false;
        }
        if (t instanceof com.google.cloud.firestore.FirestoreException fe
                && fe.getStatus() != null) {
            return fe.getStatus().getCode() == code;
        }
        if (t instanceof com.google.api.gax.rpc.ApiException api
                && api.getStatusCode() != null
                && api.getStatusCode().getCode() != null) {
            return api.getStatusCode().getCode().name().equals(code.name());
        }
        return false;
    }

    private static String statusOf(Throwable t) {
        if (t instanceof com.google.cloud.firestore.FirestoreException fe && fe.getStatus() != null) {
            return fe.getStatus().getCode().name();
        }
        if (t instanceof com.google.api.gax.rpc.ApiException api
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

    /**
     * Lazy iterable over the games collection. Each call to
     * {@link #iterator()} returns a fresh {@link PagingIterator}
     * that walks the collection from page 1.
     */
    private final class PagingIterable implements Iterable<GameDocumentDto> {
        @Override
        public Iterator<GameDocumentDto> iterator() {
            return new PagingIterator();
        }
    }

    /**
     * Cursor-based iterator over the games collection. Issues one
     * Firestore {@code get()} per page; advances with
     * {@code startAfter(lastDoc)} until the page comes back short
     * (size {@code <} pageSize) or empty.
     *
     * <p>The base query is ordered by document ID
     * ({@link FieldPath#documentId()}) so the cursor on
     * {@code startAfter} is stable across requests. Without an
     * explicit ordering, the SDK does not guarantee a deterministic
     * document order between pages: a document may appear in two
     * pages, or be skipped entirely, if a write lands between the
     * two {@code get()} calls and the internal order shifts. See
     * the Firestore docs on query cursors for the contract.
     *
     * <p><b>Note for future maintainers:</b> every call to
     * {@link #hasNext()} may perform a network round-trip, and the
     * iterator is single-use by design. If the caller materialises
     * the iterable into a {@code List} for re-walking (e.g. for
     * size logging or re-iteration), be aware that each walk is a
     * full page-by-page fetch. For one-shot consumption (the
     * for-each pattern), this is fine.
     */
    private final class PagingIterator implements Iterator<GameDocumentDto> {
        private QueryDocumentSnapshot lastDoc;
        private Iterator<QueryDocumentSnapshot> currentPage;
        private boolean exhausted;

        PagingIterator() {
            fetchNextPage();
        }

        @Override
        public boolean hasNext() {
            if (currentPage != null && currentPage.hasNext()) {
                return true;
            }
            if (exhausted) {
                return false;
            }
            return fetchNextPage();
        }

        @Override
        public GameDocumentDto next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return currentPage.next().toObject(GameDocumentDto.class);
        }

        private boolean fetchNextPage() {
            Query query = gamesCollection()
                    .orderBy(FieldPath.documentId())
                    .limit(pageSize);
            if (lastDoc != null) {
                query = query.startAfter(lastDoc);
            }
            QuerySnapshot snapshot = await("reading", collectionPath, query::get);
            List<QueryDocumentSnapshot> docs = snapshot.getDocuments();
            if (docs.isEmpty()) {
                exhausted = true;
                return false;
            }
            currentPage = docs.iterator();
            lastDoc = docs.get(docs.size() - 1);
            if (docs.size() < pageSize) {
                exhausted = true;
            }
            return true;
        }
    }
}
