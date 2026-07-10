package com.cheapquest.backend.dao.firestore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cheapquest.backend.client.FirestoreRetrier;
import com.cheapquest.backend.dto.firebase.TranslationFailedDoc;
import com.cheapquest.backend.dto.firebase.TranslationPendingDoc;
import com.google.api.core.SettableApiFuture;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldPath;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreException;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.cloud.firestore.WriteResult;
import io.grpc.Status;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FirestoreTranslationQueueDaoTest {

    private Firestore firestore;
    private CollectionReference pendingCollection;
    private CollectionReference failedCollection;
    private FirestoreTranslationQueueDao dao;

    @BeforeEach
    void setUp() {
        firestore = mock(Firestore.class);
        pendingCollection = mock(CollectionReference.class);
        failedCollection = mock(CollectionReference.class);
        when(firestore.collection("translations-pending")).thenReturn(pendingCollection);
        when(firestore.collection("translations-failed")).thenReturn(failedCollection);
        FirestoreRetrier retrier = new FirestoreRetrier(2, 1L);
        dao = new FirestoreTranslationQueueDao(
                firestore, "translations-pending", "translations-failed", retrier);
    }

    @Test
    void enqueue_createsFirstAttemptDoc() {
        DocumentReference ref = mock(DocumentReference.class);
        when(pendingCollection.document("portal_es")).thenReturn(ref);
        SettableApiFuture<WriteResult> future = SettableApiFuture.create();
        future.set(mock(WriteResult.class));
        when(ref.create(any(TranslationPendingDoc.class))).thenReturn(future);

        Instant now = Instant.parse("2026-06-30T10:00:00Z");
        dao.enqueue("portal", "es", now);

        ArgumentCaptor<TranslationPendingDoc> captor =
                ArgumentCaptor.forClass(TranslationPendingDoc.class);
        verify(ref).create(captor.capture());
        assertThat(captor.getValue().slug()).isEqualTo("portal");
        assertThat(captor.getValue().locale()).isEqualTo("es");
        assertThat(captor.getValue().sourceFetchedAt()).isEqualTo(now);
        assertThat(captor.getValue().attempts()).isEqualTo(1);
        assertThat(captor.getValue().lastError()).isNull();
    }

    @Test
    void enqueue_isNoOpWhenAlreadyExists() {
        DocumentReference ref = mock(DocumentReference.class);
        when(pendingCollection.document("portal_es")).thenReturn(ref);
        SettableApiFuture<WriteResult> future = SettableApiFuture.create();
        future.setException(FirestoreException.forServerRejection(
                Status.ALREADY_EXISTS, "dup"));
        when(ref.create(any(TranslationPendingDoc.class))).thenReturn(future);

        // No exception expected: a duplicate enqueue is a no-op so
        // the per-attempt counter is not reset on every refresh.
        dao.enqueue("portal", "es", Instant.now());
    }

    @Test
    void readPending_returnsListOfEntries() {
        TranslationPendingDoc p1 = new TranslationPendingDoc(
                "portal", "es", Instant.parse("2026-06-30T10:00:00Z"), 1, Instant.now(), null);
        TranslationPendingDoc p2 = new TranslationPendingDoc(
                "hl2", "fr", Instant.parse("2026-06-30T11:00:00Z"), 2, Instant.now(), "blip");

        QueryDocumentSnapshot d1 = mockQueryDoc();
        QueryDocumentSnapshot d2 = mockQueryDoc();
        when(d1.toObject(TranslationPendingDoc.class)).thenReturn(p1);
        when(d2.toObject(TranslationPendingDoc.class)).thenReturn(p2);

        Query orderedQuery = mock(Query.class);
        QuerySnapshot snapshot = mock(QuerySnapshot.class);
        when(snapshot.getDocuments()).thenReturn(List.of(d1, d2));
        SettableApiFuture<QuerySnapshot> future = SettableApiFuture.create();
        future.set(snapshot);
        when(orderedQuery.get()).thenReturn(future);
        when(pendingCollection.orderBy(any(FieldPath.class))).thenReturn(orderedQuery);

        List<TranslationPendingDoc> result = dao.readPending();

        assertThat(result).containsExactly(p1, p2);
    }

    @Test
    void readOne_returnsDocWhenPresent() {
        DocumentReference ref = mock(DocumentReference.class);
        DocumentSnapshot snap = mock(DocumentSnapshot.class);
        TranslationPendingDoc doc = new TranslationPendingDoc(
                "portal", "es", Instant.parse("2026-06-30T10:00:00Z"), 1, Instant.now(), null);
        when(pendingCollection.document("portal_es")).thenReturn(ref);
        when(snap.exists()).thenReturn(true);
        when(snap.toObject(TranslationPendingDoc.class)).thenReturn(doc);
        SettableApiFuture<DocumentSnapshot> future = SettableApiFuture.create();
        future.set(snap);
        when(ref.get()).thenReturn(future);

        assertThat(dao.readOne("portal", "es")).isEqualTo(doc);
    }

    @Test
    void readOne_returnsNullWhenMissing() {
        DocumentReference ref = mock(DocumentReference.class);
        DocumentSnapshot snap = mock(DocumentSnapshot.class);
        when(pendingCollection.document("missing_es")).thenReturn(ref);
        when(snap.exists()).thenReturn(false);
        SettableApiFuture<DocumentSnapshot> future = SettableApiFuture.create();
        future.set(snap);
        when(ref.get()).thenReturn(future);

        assertThat(dao.readOne("missing", "es")).isNull();
    }

    @Test
    void recordFailure_preservesSourceFetchedAt() {
        Instant sourceFetchedAt = Instant.parse("2026-06-30T10:00:00Z");
        Instant lastAttemptAt = Instant.parse("2026-06-30T10:05:00Z");
        TranslationPendingDoc current = new TranslationPendingDoc(
                "portal", "es", sourceFetchedAt, 1, Instant.now(), null);

        DocumentReference ref = mock(DocumentReference.class);
        DocumentSnapshot snap = mock(DocumentSnapshot.class);
        when(pendingCollection.document("portal_es")).thenReturn(ref);
        when(ref.get()).thenReturn(snapFuture(snap));
        when(snap.exists()).thenReturn(true);
        when(snap.toObject(TranslationPendingDoc.class)).thenReturn(current);
        SettableApiFuture<WriteResult> future = SettableApiFuture.create();
        future.set(mock(WriteResult.class));
        when(ref.set(any(TranslationPendingDoc.class))).thenReturn(future);

        dao.recordFailure("portal", "es", 2, lastAttemptAt, "blip");

        ArgumentCaptor<TranslationPendingDoc> captor =
                ArgumentCaptor.forClass(TranslationPendingDoc.class);
        verify(ref).set(captor.capture());
        // The critical invariant: sourceFetchedAt is NOT reset
        // to the current attempt's now(); it stays bound to the
        // original rawg.fetchedAt the enqueue recorded.
        assertThat(captor.getValue().sourceFetchedAt()).isEqualTo(sourceFetchedAt);
        assertThat(captor.getValue().attempts()).isEqualTo(2);
        assertThat(captor.getValue().lastAttemptAt()).isEqualTo(lastAttemptAt);
        assertThat(captor.getValue().lastError()).isEqualTo("blip");
    }

    @Test
    void recordFailure_isNoOpWhenDocMissing() {
        DocumentReference ref = mock(DocumentReference.class);
        DocumentSnapshot snap = mock(DocumentSnapshot.class);
        when(pendingCollection.document("missing_es")).thenReturn(ref);
        when(ref.get()).thenReturn(snapFuture(snap));
        when(snap.exists()).thenReturn(false);
        SettableApiFuture<DocumentSnapshot> future = SettableApiFuture.create();
        future.set(snap);
        when(ref.get()).thenReturn(future);

        // No exception, no set call: the doc was removed concurrently.
        dao.recordFailure("missing", "es", 2, Instant.now(), "blip");
        verify(ref, org.mockito.Mockito.never()).set(any(TranslationPendingDoc.class));
    }

    @Test
    void removeFromPending_deletesDoc() {
        DocumentReference ref = mock(DocumentReference.class);
        when(pendingCollection.document("hl2_fr")).thenReturn(ref);
        SettableApiFuture<WriteResult> future = SettableApiFuture.create();
        future.set(mock(WriteResult.class));
        when(ref.delete()).thenReturn(future);

        dao.removeFromPending("hl2", "fr");

        verify(ref).delete();
    }

    @Test
    void moveToFailed_writesFailedDocAndRemovesFromPending() {
        DocumentReference failedRef = mock(DocumentReference.class);
        DocumentReference pendingRef = mock(DocumentReference.class);
        when(failedCollection.document("portal_es")).thenReturn(failedRef);
        when(pendingCollection.document("portal_es")).thenReturn(pendingRef);
        SettableApiFuture<WriteResult> setFuture = SettableApiFuture.create();
        setFuture.set(mock(WriteResult.class));
        when(failedRef.set(any(TranslationFailedDoc.class))).thenReturn(setFuture);
        SettableApiFuture<WriteResult> delFuture = SettableApiFuture.create();
        delFuture.set(mock(WriteResult.class));
        when(pendingRef.delete()).thenReturn(delFuture);

        TranslationFailedDoc doc = new TranslationFailedDoc(
                "portal", "es", 3,
                Instant.parse("2026-06-30T10:00:00Z"),
                Instant.parse("2026-06-30T10:05:00Z"),
                "no such game");
        dao.moveToFailed(doc);

        ArgumentCaptor<TranslationFailedDoc> captor =
                ArgumentCaptor.forClass(TranslationFailedDoc.class);
        verify(failedRef).set(captor.capture());
        assertThat(captor.getValue()).isEqualTo(doc);
        verify(pendingRef).delete();
    }

    private static QueryDocumentSnapshot mockQueryDoc() {
        return mock(QueryDocumentSnapshot.class);
    }

    private static SettableApiFuture<DocumentSnapshot> snapFuture(DocumentSnapshot snap) {
        SettableApiFuture<DocumentSnapshot> f = SettableApiFuture.create();
        f.set(snap);
        return f;
    }
}
