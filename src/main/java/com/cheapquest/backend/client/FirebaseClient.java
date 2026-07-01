package com.cheapquest.backend.client;

import com.cheapquest.backend.config.AppProperties;
import com.cheapquest.backend.dto.firebase.GameDocumentDto;
import com.cheapquest.backend.dto.firebase.HydrationPatch;
import com.cheapquest.backend.exception.FirebaseUnavailableException;
import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.cloud.firestore.WriteResult;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
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

    private final Firestore firestore;
    private final String collectionPath;
    private final int pageSize;

    public FirebaseClient(Firestore firestore, AppProperties props) {
        this(firestore, props.firestoreCollectionGamesPath(), props.firestoreReadPageSize());
    }

    FirebaseClient(Firestore firestore, String collectionPath, int pageSize) {
        this.firestore = firestore;
        this.collectionPath = collectionPath;
        this.pageSize = pageSize;
        log.debug("firebase_client_initialized collectionPath={} pageSize={}", collectionPath, pageSize);
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
     * Surgical update: only the fields present in the {@link HydrationPatch}
     * are rewritten (title, cheapshark, rawg, locales, validationReport).
     * Fails if the document does not exist.
     */
    public void update(String slug, HydrationPatch patch) {
        DocumentReference ref = gamesCollection().document(slug);
        await("updating", slug, () -> ref.update(patch.toFirestoreMap()));
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
     */
    private static <T> T await(String op, String subject, Supplier<ApiFuture<T>> futureSupplier) {
        try {
            return futureSupplier.get().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FirebaseUnavailableException("interrupted " + op + " " + subject, e);
        } catch (ExecutionException e) {
            throw new FirebaseUnavailableException("failed " + op + " " + subject, e.getCause());
        } catch (RuntimeException e) {
            throw new FirebaseUnavailableException("failed " + op + " " + subject, e);
        }
    }

    private static boolean isAlreadyExists(Throwable t) {
        if (t == null) {
            return false;
        }
        if (t instanceof com.google.cloud.firestore.FirestoreException fe) {
            return fe.getStatus() != null
                    && fe.getStatus().getCode() == io.grpc.Status.Code.ALREADY_EXISTS;
        }
        if (t instanceof com.google.api.gax.rpc.ApiException api
                && api.getStatusCode() != null
                && api.getStatusCode().getCode() != null) {
            return api.getStatusCode().getCode()
                    == com.google.api.gax.rpc.StatusCode.Code.ALREADY_EXISTS;
        }
        return false;
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
            Query query = gamesCollection().limit(pageSize);
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
