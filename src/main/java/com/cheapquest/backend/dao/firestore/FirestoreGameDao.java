package com.cheapquest.backend.dao.firestore;

import com.cheapquest.backend.client.FirestoreRetrier;
import com.cheapquest.backend.dao.GameDao;
import com.cheapquest.backend.dto.firebase.GameDocumentDto;
import com.cheapquest.backend.dto.firebase.HydrationPatch;
import com.cheapquest.backend.exception.DocumentNotFoundException;
import com.cheapquest.backend.exception.FirebaseUnavailableException;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldPath;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import io.grpc.Status;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Firestore-backed implementation of {@link GameDao}. Owns the
 * {@code games/{slug}} collection and the {@code locales} sub-map
 * on each game document. Pagination, retry, and SDK-exception
 * translation are handled by the shared {@link FirestoreRetrier};
 * the only domain-specific error translation is
 * {@link DocumentNotFoundException} for missing docs on update.
 */
public final class FirestoreGameDao implements GameDao {

    private static final Logger log = LoggerFactory.getLogger(FirestoreGameDao.class);

    private final Firestore firestore;
    private final String collectionPath;
    private final int pageSize;
    private final FirestoreRetrier retrier;

    public FirestoreGameDao(Firestore firestore, String collectionPath,
            int pageSize, FirestoreRetrier retrier) {
        this.firestore = firestore;
        this.collectionPath = collectionPath;
        this.pageSize = pageSize;
        this.retrier = retrier;
        log.debug("firestore_game_dao_initialized collectionPath={} pageSize={}",
                collectionPath, pageSize);
    }

    @Override
    public boolean createIfNotExists(String slug, GameDocumentDto dto) {
        DocumentReference ref = gamesCollection().document(slug);
        try {
            retrier.await("creating", slug, () -> ref.create(dto));
            return true;
        } catch (FirebaseUnavailableException e) {
            if (FirestoreRetrier.hasStatus(e.getCause(), Status.Code.ALREADY_EXISTS)) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public Optional<GameDocumentDto> read(String slug) {
        DocumentSnapshot snap = retrier.await("reading", slug,
                () -> gamesCollection().document(slug).get());
        if (!snap.exists()) {
            return Optional.empty();
        }
        return Optional.of(snap.toObject(GameDocumentDto.class));
    }

    @Override
    public Iterable<GameDocumentDto> readAll() {
        return new PagingIterable();
    }

    @Override
    public void update(String slug, HydrationPatch patch) {
        DocumentReference ref = gamesCollection().document(slug);
        try {
            retrier.await("updating", slug, () -> ref.update(patch.toFirestoreMap()));
        } catch (FirebaseUnavailableException e) {
            if (FirestoreRetrier.hasStatus(e.getCause(), Status.Code.NOT_FOUND)) {
                throw new DocumentNotFoundException("document missing: " + slug, e);
            }
            throw e;
        }
    }

    @Override
    public void writeLocaleTranslation(String slug, String locale,
            String description, List<String> tagTranslations,
            Instant sourceFetchedAt, Instant translatedAt) {
        DocumentReference ref = gamesCollection().document(slug);
        Map<String, Object> updates = new HashMap<>();
        updates.put("locales." + locale + ".synced", Boolean.TRUE);
        updates.put("locales." + locale + ".updatedAt", translatedAt.toString());
        updates.put("locales." + locale + ".sourceFetchedAt", sourceFetchedAt.toString());
        if (description != null) {
            updates.put("locales." + locale + ".description", description);
        }
        if (tagTranslations != null) {
            updates.put("locales." + locale + ".tags", tagTranslations);
        }
        retrier.await("write-locale-translation", slug + "/" + locale,
                () -> ref.update(updates));
    }

    @Override
    public void markLocaleSynced(String slug, String lang, Instant syncedAt) {
        if (lang == null || lang.isBlank()) {
            throw new IllegalArgumentException("lang must be non-blank, got: " + lang);
        }
        DocumentReference ref = gamesCollection().document(slug);
        Map<String, Object> updates = new HashMap<>();
        updates.put("locales." + lang + ".synced", Boolean.TRUE);
        updates.put("locales." + lang + ".updatedAt", syncedAt.toString());
        retrier.await("mark-locale-synced", slug + "/" + lang, () -> ref.update(updates));
    }

    private CollectionReference gamesCollection() {
        return firestore.collection(collectionPath);
    }

    private final class PagingIterable implements Iterable<GameDocumentDto> {
        @Override
        public Iterator<GameDocumentDto> iterator() {
            return new PagingIterator();
        }
    }

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
            QuerySnapshot snapshot = retrier.await("reading", collectionPath, query::get);
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
