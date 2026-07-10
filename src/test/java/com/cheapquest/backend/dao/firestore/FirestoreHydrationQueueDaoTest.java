package com.cheapquest.backend.dao.firestore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cheapquest.backend.client.FirestoreRetrier;
import com.cheapquest.backend.dto.firebase.FailedDoc;
import com.cheapquest.backend.dto.firebase.PendingDoc;
import com.google.api.core.SettableApiFuture;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
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

class FirestoreHydrationQueueDaoTest {

    private Firestore firestore;
    private CollectionReference pendingCollection;
    private CollectionReference failedCollection;
    private FirestoreHydrationQueueDao dao;

    @BeforeEach
    void setUp() {
        firestore = mock(Firestore.class);
        pendingCollection = mock(CollectionReference.class);
        failedCollection = mock(CollectionReference.class);
        when(firestore.collection("pending")).thenReturn(pendingCollection);
        when(firestore.collection("failed")).thenReturn(failedCollection);
        FirestoreRetrier retrier = new FirestoreRetrier(2, 1L);
        dao = new FirestoreHydrationQueueDao(
                firestore, "pending", "failed", retrier);
    }

    @Test
    void enqueue_createsFirstAttempt() {
        DocumentReference ref = mock(DocumentReference.class);
        when(pendingCollection.document("portal")).thenReturn(ref);
        SettableApiFuture<WriteResult> future = SettableApiFuture.create();
        future.set(mock(WriteResult.class));
        when(ref.create(any(PendingDoc.class))).thenReturn(future);

        dao.enqueue("portal");

        ArgumentCaptor<PendingDoc> captor = ArgumentCaptor.forClass(PendingDoc.class);
        verify(ref).create(captor.capture());
        assertThat(captor.getValue().slug()).isEqualTo("portal");
        assertThat(captor.getValue().attempts()).isEqualTo(1);
        assertThat(captor.getValue().lastError()).isNull();
    }

    @Test
    void enqueue_isNoOpWhenAlreadyExists() {
        DocumentReference ref = mock(DocumentReference.class);
        when(pendingCollection.document("portal")).thenReturn(ref);
        SettableApiFuture<WriteResult> future = SettableApiFuture.create();
        future.setException(FirestoreException.forServerRejection(
                Status.ALREADY_EXISTS, "dup"));
        when(ref.create(any(PendingDoc.class))).thenReturn(future);

        // No exception expected: a duplicate enqueue is a no-op so
        // the operator can re-enqueue a slug without resetting the
        // attempt counter.
        dao.enqueue("portal");
    }

    @Test
    void readPending_returnsMaterialisedListOfPendingDocs() {
        PendingDoc p1 = new PendingDoc(
                "portal", 1, Instant.parse("2026-06-30T10:00:00Z"), null);
        PendingDoc p2 = new PendingDoc("hl2", 0, null, null);

        QueryDocumentSnapshot d1 = mockQueryDoc();
        QueryDocumentSnapshot d2 = mockQueryDoc();
        when(d1.toObject(PendingDoc.class)).thenReturn(p1);
        when(d2.toObject(PendingDoc.class)).thenReturn(p2);

        Query orderedQuery = mock(Query.class);
        QuerySnapshot pendingSnapshot = mock(QuerySnapshot.class);
        when(pendingSnapshot.getDocuments()).thenReturn(List.of(d1, d2));
        SettableApiFuture<QuerySnapshot> future = SettableApiFuture.create();
        future.set(pendingSnapshot);
        when(orderedQuery.get()).thenReturn(future);
        when(pendingCollection.orderBy(any(FieldPath.class))).thenReturn(orderedQuery);

        List<PendingDoc> result = dao.readPending();

        assertThat(result).containsExactly(p1, p2);
    }

    @Test
    void readFailed_returnsMaterialisedListOfFailedDocs() {
        FailedDoc f1 = new FailedDoc(
                "portal", 3,
                Instant.parse("2026-06-29T10:00:00Z"),
                Instant.parse("2026-06-30T10:00:00Z"),
                "both sources returned empty");
        FailedDoc f2 = new FailedDoc(
                "hl2", 3,
                Instant.parse("2026-06-29T10:05:00Z"),
                Instant.parse("2026-06-30T10:05:00Z"),
                "rawg 404");

        QueryDocumentSnapshot d1 = mockQueryDoc();
        QueryDocumentSnapshot d2 = mockQueryDoc();
        when(d1.toObject(FailedDoc.class)).thenReturn(f1);
        when(d2.toObject(FailedDoc.class)).thenReturn(f2);

        Query orderedQuery = mock(Query.class);
        QuerySnapshot failedSnapshot = mock(QuerySnapshot.class);
        when(failedSnapshot.getDocuments()).thenReturn(List.of(d1, d2));
        SettableApiFuture<QuerySnapshot> future = SettableApiFuture.create();
        future.set(failedSnapshot);
        when(orderedQuery.get()).thenReturn(future);
        when(failedCollection.orderBy(any(FieldPath.class))).thenReturn(orderedQuery);

        List<FailedDoc> result = dao.readFailed();

        assertThat(result).containsExactly(f1, f2);
    }

    @Test
    void removeFromPending_deletesDoc() {
        DocumentReference ref = mock(DocumentReference.class);
        when(pendingCollection.document("portal")).thenReturn(ref);
        SettableApiFuture<WriteResult> future = SettableApiFuture.create();
        future.set(mock(WriteResult.class));
        when(ref.delete()).thenReturn(future);

        dao.removeFromPending("portal");

        verify(ref).delete();
    }

    @Test
    void recordFailure_writesUpdatedDoc() {
        DocumentReference ref = mock(DocumentReference.class);
        when(pendingCollection.document("portal")).thenReturn(ref);
        SettableApiFuture<WriteResult> future = SettableApiFuture.create();
        future.set(mock(WriteResult.class));
        when(ref.set(any(PendingDoc.class))).thenReturn(future);

        Instant now = Instant.parse("2026-06-30T10:05:00Z");
        dao.recordFailure("portal", 2, now, "network blip");

        ArgumentCaptor<PendingDoc> captor = ArgumentCaptor.forClass(PendingDoc.class);
        verify(ref).set(captor.capture());
        assertThat(captor.getValue().slug()).isEqualTo("portal");
        assertThat(captor.getValue().attempts()).isEqualTo(2);
        assertThat(captor.getValue().lastAttemptAt()).isEqualTo(now);
        assertThat(captor.getValue().lastError()).isEqualTo("network blip");
    }

    @Test
    void replacePending_writesGivenDoc() {
        DocumentReference ref = mock(DocumentReference.class);
        when(pendingCollection.document("portal")).thenReturn(ref);
        SettableApiFuture<WriteResult> future = SettableApiFuture.create();
        future.set(mock(WriteResult.class));
        when(ref.set(any(PendingDoc.class))).thenReturn(future);

        PendingDoc incoming = new PendingDoc(
                "portal", 0, Instant.parse("2026-06-30T10:00:00Z"), null);
        dao.replacePending(incoming);

        verify(ref).set(incoming);
    }

    @Test
    void moveToFailed_writesFailedDocAndRemovesFromPending() {
        DocumentReference failedRef = mock(DocumentReference.class);
        DocumentReference pendingRef = mock(DocumentReference.class);
        when(failedCollection.document("portal")).thenReturn(failedRef);
        when(pendingCollection.document("portal")).thenReturn(pendingRef);
        SettableApiFuture<WriteResult> setFuture = SettableApiFuture.create();
        setFuture.set(mock(WriteResult.class));
        when(failedRef.set(any(FailedDoc.class))).thenReturn(setFuture);
        SettableApiFuture<WriteResult> delFuture = SettableApiFuture.create();
        delFuture.set(mock(WriteResult.class));
        when(pendingRef.delete()).thenReturn(delFuture);

        FailedDoc doc = new FailedDoc(
                "portal", 3,
                Instant.parse("2026-06-30T10:00:00Z"),
                Instant.parse("2026-06-30T10:05:00Z"),
                "no such game");
        dao.moveToFailed(doc);

        ArgumentCaptor<FailedDoc> captor = ArgumentCaptor.forClass(FailedDoc.class);
        verify(failedRef).set(captor.capture());
        assertThat(captor.getValue()).isEqualTo(doc);
        verify(pendingRef).delete();
    }

    private static QueryDocumentSnapshot mockQueryDoc() {
        return mock(QueryDocumentSnapshot.class);
    }
}
