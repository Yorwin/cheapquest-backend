package com.cheapquest.backend.dao.firestore;

import com.cheapquest.backend.client.FirestoreRetrier;
import com.cheapquest.backend.dao.TranslationQueueDao;
import com.cheapquest.backend.dto.firebase.TranslationFailedDoc;
import com.cheapquest.backend.dto.firebase.TranslationPendingDoc;
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
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Firestore-backed implementation of {@link TranslationQueueDao}.
 * Owns the {@code translations-pending} and
 * {@code translations-failed} collections. Document id format is
 * {@code "{slug}_{locale}"} (e.g. {@code portal_es}) so a second
 * concurrent enqueue lands on the same doc and is rejected with
 * {@code ALREADY_EXISTS}.
 */
public final class FirestoreTranslationQueueDao implements TranslationQueueDao {

    private static final Logger log = LoggerFactory.getLogger(FirestoreTranslationQueueDao.class);

    private final Firestore firestore;
    private final String pendingCollectionPath;
    private final String failedCollectionPath;
    private final FirestoreRetrier retrier;

    public FirestoreTranslationQueueDao(Firestore firestore,
            String pendingCollectionPath, String failedCollectionPath,
            FirestoreRetrier retrier) {
        this.firestore = firestore;
        this.pendingCollectionPath = pendingCollectionPath;
        this.failedCollectionPath = failedCollectionPath;
        this.retrier = retrier;
        log.debug("firestore_translation_queue_dao_initialized pendingPath={} failedPath={}",
                pendingCollectionPath, failedCollectionPath);
    }

    @Override
    public void enqueue(String slug, String locale, Instant sourceFetchedAt) {
        String docId = translationDocId(slug, locale);
        DocumentReference ref = pendingCollection().document(docId);
        try {
            retrier.await("enqueue-translation", docId,
                    () -> ref.create(TranslationPendingDoc.firstAttempt(slug, locale, sourceFetchedAt)));
        } catch (FirebaseUnavailableException e) {
            if (FirestoreRetrier.hasStatus(e.getCause(), Status.Code.ALREADY_EXISTS)) {
                return;
            }
            throw e;
        }
    }

    @Override
    public List<TranslationPendingDoc> readPending() {
        QuerySnapshot snapshot = retrier.await("reading-translation-pending",
                pendingCollectionPath,
                () -> pendingCollection().orderBy(FieldPath.documentId()).get());
        List<QueryDocumentSnapshot> docs = snapshot.getDocuments();
        List<TranslationPendingDoc> result = new ArrayList<>(docs.size());
        for (QueryDocumentSnapshot d : docs) {
            TranslationPendingDoc p = d.toObject(TranslationPendingDoc.class);
            if (p != null) {
                result.add(p);
            }
        }
        return List.copyOf(result);
    }

    @Override
    public TranslationPendingDoc readOne(String slug, String locale) {
        String docId = translationDocId(slug, locale);
        DocumentSnapshot snap = retrier.await("reading-translation-pending-one", docId,
                () -> pendingCollection().document(docId).get());
        if (!snap.exists()) {
            return null;
        }
        return snap.toObject(TranslationPendingDoc.class);
    }

    @Override
    public void recordFailure(String slug, String locale, int newAttempts,
            Instant lastAttemptAt, String lastError) {
        String docId = translationDocId(slug, locale);
        DocumentReference ref = pendingCollection().document(docId);
        // Re-read the existing doc to preserve sourceFetchedAt: a
        // partial update would clobber it and the next successful
        // translation would write a stale sourceFetchedAt.
        TranslationPendingDoc current = readOne(slug, locale);
        if (current == null) {
            // The doc was removed concurrently; just no-op rather
            // than resurrect a stale entry.
            return;
        }
        TranslationPendingDoc updated = new TranslationPendingDoc(
                slug, locale, current.sourceFetchedAt(),
                newAttempts, lastAttemptAt, lastError);
        retrier.await("record-translation-failure", docId, () -> ref.set(updated));
    }

    @Override
    public void removeFromPending(String slug, String locale) {
        String docId = translationDocId(slug, locale);
        DocumentReference ref = pendingCollection().document(docId);
        retrier.await("remove-translation-pending", docId, ref::delete);
    }

    @Override
    public void moveToFailed(TranslationFailedDoc doc) {
        String docId = translationDocId(doc.slug(), doc.locale());
        DocumentReference failedRef = failedCollection().document(docId);
        retrier.await("move-to-translation-failed", docId, () -> failedRef.set(doc));
        DocumentReference pendingRef = pendingCollection().document(docId);
        retrier.await("remove-translation-pending-after-failed", docId, pendingRef::delete);
    }

    private CollectionReference pendingCollection() {
        return firestore.collection(pendingCollectionPath);
    }

    private CollectionReference failedCollection() {
        return firestore.collection(failedCollectionPath);
    }

    private static String translationDocId(String slug, String locale) {
        return slug + "_" + locale;
    }
}
