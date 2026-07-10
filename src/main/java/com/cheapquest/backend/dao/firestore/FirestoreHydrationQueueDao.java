package com.cheapquest.backend.dao.firestore;

import com.cheapquest.backend.client.FirestoreRetrier;
import com.cheapquest.backend.dao.HydrationQueueDao;
import com.cheapquest.backend.dto.firebase.FailedDoc;
import com.cheapquest.backend.dto.firebase.PendingDoc;
import com.cheapquest.backend.exception.FirebaseUnavailableException;
import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.FieldPath;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.cloud.firestore.WriteResult;
import io.grpc.Status;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Firestore-backed implementation of {@link HydrationQueueDao}.
 * Owns the {@code pending} and {@code failed} collections. The
 * {@code moveToFailed} operation is two separate SDK calls
 * (write to {@code failed} + delete from {@code pending}) and is
 * not atomic at the storage level; a future hardening could move
 * it to a {@code WriteBatch} without changing the DAO contract.
 */
public final class FirestoreHydrationQueueDao implements HydrationQueueDao {

    private static final Logger log = LoggerFactory.getLogger(FirestoreHydrationQueueDao.class);

    private final Firestore firestore;
    private final String pendingCollectionPath;
    private final String failedCollectionPath;
    private final FirestoreRetrier retrier;

    public FirestoreHydrationQueueDao(Firestore firestore,
            String pendingCollectionPath, String failedCollectionPath,
            FirestoreRetrier retrier) {
        this.firestore = firestore;
        this.pendingCollectionPath = pendingCollectionPath;
        this.failedCollectionPath = failedCollectionPath;
        this.retrier = retrier;
        log.debug("firestore_hydration_queue_dao_initialized pendingPath={} failedPath={}",
                pendingCollectionPath, failedCollectionPath);
    }

    @Override
    public void enqueue(String slug) {
        DocumentReference ref = pendingCollection().document(slug);
        try {
            retrier.await("enqueue-pending", slug,
                    () -> ref.create(PendingDoc.firstAttempt(slug)));
        } catch (FirebaseUnavailableException e) {
            if (FirestoreRetrier.hasStatus(e.getCause(), Status.Code.ALREADY_EXISTS)) {
                return;
            }
            throw e;
        }
    }

    @Override
    public List<PendingDoc> readPending() {
        QuerySnapshot snapshot = retrier.await("reading-pending", pendingCollectionPath,
                () -> pendingCollection().orderBy(FieldPath.documentId()).get());
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

    @Override
    public void replacePending(PendingDoc pending) {
        DocumentReference ref = pendingCollection().document(pending.slug());
        retrier.await("enqueue-pending-replace", pending.slug(), () -> ref.set(pending));
    }

    @Override
    public void recordFailure(String slug, int newAttempts,
            Instant lastAttemptAt, String lastError) {
        DocumentReference ref = pendingCollection().document(slug);
        PendingDoc updated = new PendingDoc(slug, newAttempts, lastAttemptAt, lastError);
        retrier.await("update-pending-failure", slug, () -> ref.set(updated));
    }

    @Override
    public void removeFromPending(String slug) {
        DocumentReference ref = pendingCollection().document(slug);
        retrier.await("remove-pending", slug, ref::delete);
    }

    @Override
    public List<FailedDoc> readFailed() {
        QuerySnapshot snapshot = retrier.await("reading-failed", failedCollectionPath,
                () -> failedCollection().orderBy(FieldPath.documentId()).get());
        List<QueryDocumentSnapshot> docs = snapshot.getDocuments();
        List<FailedDoc> result = new ArrayList<>(docs.size());
        for (QueryDocumentSnapshot d : docs) {
            FailedDoc f = d.toObject(FailedDoc.class);
            if (f != null) {
                result.add(f);
            }
        }
        return List.copyOf(result);
    }

    @Override
    public void moveToFailed(FailedDoc doc) {
        DocumentReference failedRef = failedCollection().document(doc.slug());
        retrier.await("move-to-failed", doc.slug(), () -> failedRef.set(doc));
        DocumentReference pendingRef = pendingCollection().document(doc.slug());
        retrier.await("remove-pending-after-failed", doc.slug(), pendingRef::delete);
    }

    private CollectionReference pendingCollection() {
        return firestore.collection(pendingCollectionPath);
    }

    private CollectionReference failedCollection() {
        return firestore.collection(failedCollectionPath);
    }
}
