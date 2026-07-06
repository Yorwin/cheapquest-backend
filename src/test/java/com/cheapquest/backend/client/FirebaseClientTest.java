package com.cheapquest.backend.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cheapquest.backend.config.AppProperties;
import com.cheapquest.backend.dto.firebase.CheapsharkBlock;
import com.cheapquest.backend.dto.firebase.GameDocumentDto;
import com.cheapquest.backend.dto.firebase.HydrationPatch;
import com.cheapquest.backend.dto.firebase.LocaleBlock;
import com.cheapquest.backend.dto.firebase.RawgBlock;
import com.cheapquest.backend.dto.firebase.TranslationFailedDoc;
import com.cheapquest.backend.dto.firebase.TranslationPendingDoc;
import com.cheapquest.backend.dto.firebase.ValidationReportDto;
import com.cheapquest.backend.exception.DocumentNotFoundException;
import com.cheapquest.backend.exception.FirebaseUnavailableException;
import com.cheapquest.backend.fixtures.GameDocumentDtoFixtures;
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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FirebaseClientTest {

    private Firestore firestore;
    private CollectionReference gamesCollection;
    private AppProperties props;
    private FirebaseClient client;

    @BeforeEach
    void setUp() {
        firestore = mock(Firestore.class);
        gamesCollection = mock(CollectionReference.class);
        when(firestore.collection("games")).thenReturn(gamesCollection);
        // pending / failed collection references are stubbed
        // per-test; default to a fresh mock so an unstubbed call
        // returns a non-null reference rather than NPE.
        org.mockito.Mockito.lenient().when(firestore.collection("pending"))
                .thenReturn(mock(CollectionReference.class));
        org.mockito.Mockito.lenient().when(firestore.collection("failed"))
                .thenReturn(mock(CollectionReference.class));
        org.mockito.Mockito.lenient().when(firestore.collection("translations-pending"))
                .thenReturn(mock(CollectionReference.class));
        org.mockito.Mockito.lenient().when(firestore.collection("translations-failed"))
                .thenReturn(mock(CollectionReference.class));
        props = mock(AppProperties.class);
        when(props.firestoreCollectionGamesPath()).thenReturn("games");
        when(props.firestoreCollectionPendingPath()).thenReturn("pending");
        when(props.firestoreCollectionFailedPath()).thenReturn("failed");
        when(props.firestoreCollectionTranslationPendingPath()).thenReturn("translations-pending");
        when(props.firestoreCollectionTranslationFailedPath()).thenReturn("translations-failed");
        when(props.firestoreReadPageSize()).thenReturn(300);
        client = new FirebaseClient(firestore, props);
    }

    @Test
    void readAll_iteratesSinglePageWhenCollectionFitsInOnePage() throws Exception {
        QueryDocumentSnapshot doc1 = mockQueryDoc("portal", "Portal");
        QueryDocumentSnapshot doc2 = mockQueryDoc("hl2", "Half-Life 2");
        Query firstQuery = mockQueryReturning(List.of(doc1, doc2));
        stubOrderedQuery(firstQuery);

        List<GameDocumentDto> result = collect(client.readAll());

        assertThat(result).containsExactly(
                GameDocumentDtoFixtures.emptyDoc("portal", "Portal"),
                GameDocumentDtoFixtures.emptyDoc("hl2", "Half-Life 2"));
    }

    @Test
    void readAll_paginatesAcrossMultiplePages() throws Exception {
        FirebaseClient smallPageClient = new FirebaseClient(firestore, "games", 2);
        QueryDocumentSnapshot d1 = mockQueryDoc("portal", "Portal");
        QueryDocumentSnapshot d2 = mockQueryDoc("hl2", "Half-Life 2");
        QueryDocumentSnapshot d3 = mockQueryDoc("stardew", "Stardew Valley");

        Query q1 = mockQueryReturning(List.of(d1, d2));
        Query q2 = mockQueryReturning(List.of(d3));
        Query ordered = stubOrderedQuery(q1);
        when(q1.startAfter(d2)).thenReturn(q2);

        List<GameDocumentDto> result = collect(smallPageClient.readAll());

        assertThat(result).hasSize(3);
        verify(ordered, org.mockito.Mockito.times(2)).limit(2);
        verify(q1).get();
        verify(q1).startAfter(d2);
        verify(q2).get();
        verify(q2, org.mockito.Mockito.never()).startAfter(any(DocumentSnapshot.class));
    }

    @Test
    void readAll_returnsEmptyIterableWhenCollectionIsEmpty() throws Exception {
        Query emptyQuery = mockQueryReturning(List.of());
        stubOrderedQuery(emptyQuery);

        List<GameDocumentDto> result = collect(client.readAll());

        assertThat(result).isEmpty();
    }

    @Test
    void readAll_stopsAfterPartialLastPage() throws Exception {
        FirebaseClient smallPageClient = new FirebaseClient(firestore, "games", 3);
        QueryDocumentSnapshot d1 = mockQueryDoc("portal", "Portal");
        QueryDocumentSnapshot d2 = mockQueryDoc("hl2", "Half-Life 2");
        Query q1 = mockQueryReturning(List.of(d1, d2));
        stubOrderedQuery(q1);

        List<GameDocumentDto> result = collect(smallPageClient.readAll());

        assertThat(result).hasSize(2);
        verify(q1).get();
        verify(q1, org.mockito.Mockito.never()).startAfter(any(DocumentSnapshot.class));
    }

    @Test
    void readAll_usesDocumentIdOrderForStableCursor() throws Exception {
        // Contract: the iterator must order the query by document ID so
        // startAfter(lastDoc) is stable across requests. Without this
        // ordering, Firestore does not guarantee a deterministic
        // document order between pages, and a document can be skipped
        // or duplicated across the cursor.
        Query q = mockQueryReturning(List.of());
        stubOrderedQuery(q);

        collect(client.readAll());

        verify(gamesCollection).orderBy(FieldPath.documentId());
    }

    @Test
    void readAll_wrapsExecutionExceptionInFirebaseUnavailable() {
        Query failingQuery = mock(Query.class);
        SettableApiFuture<QuerySnapshot> future = SettableApiFuture.create();
        future.setException(new RuntimeException("network down"));
        when(failingQuery.get()).thenReturn(future);
        stubOrderedQuery(failingQuery);

        assertThatThrownBy(() -> collect(client.readAll()))
                .isInstanceOf(FirebaseUnavailableException.class)
                .hasMessageContaining("games")
                .hasCauseInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("network down");
    }

    @Test
    void readAll_wrapsRuntimeException() {
        Query failingQuery = mock(Query.class);
        when(failingQuery.get()).thenThrow(new RuntimeException("boom"));
        stubOrderedQuery(failingQuery);

        assertThatThrownBy(() -> collect(client.readAll()))
                .isInstanceOf(FirebaseUnavailableException.class)
                .hasMessageContaining("games");
    }

    @Test
    void readOne_returnsDtoWhenDocumentExists() throws Exception {
        DocumentReference ref = mock(DocumentReference.class);
        DocumentSnapshot snap = mock(DocumentSnapshot.class);
        GameDocumentDto dto = sampleDto("portal", "Portal");
        when(gamesCollection.document("portal")).thenReturn(ref);
        when(snap.exists()).thenReturn(true);
        when(snap.toObject(GameDocumentDto.class)).thenReturn(dto);
        SettableApiFuture<DocumentSnapshot> future = SettableApiFuture.create();
        future.set(snap);
        when(ref.get()).thenReturn(future);

        assertThat(client.readOne("portal")).contains(dto);
    }

    @Test
    void readOne_returnsEmptyWhenDocumentMissing() throws Exception {
        DocumentReference ref = mock(DocumentReference.class);
        DocumentSnapshot snap = mock(DocumentSnapshot.class);
        when(gamesCollection.document("missing")).thenReturn(ref);
        when(snap.exists()).thenReturn(false);
        SettableApiFuture<DocumentSnapshot> future = SettableApiFuture.create();
        future.set(snap);
        when(ref.get()).thenReturn(future);

        assertThat(client.readOne("missing")).isEmpty();
    }

    @Test
    void readOne_wrapsExecutionException() {
        DocumentReference ref = mock(DocumentReference.class);
        when(gamesCollection.document("portal")).thenReturn(ref);
        SettableApiFuture<DocumentSnapshot> future = SettableApiFuture.create();
        future.setException(new RuntimeException("io error"));
        when(ref.get()).thenReturn(future);

        assertThatThrownBy(() -> client.readOne("portal"))
                .isInstanceOf(FirebaseUnavailableException.class)
                .hasMessageContaining("portal");
    }

    @Test
    void createIfNotExists_returnsTrueWhenCreated() throws Exception {
        DocumentReference ref = mock(DocumentReference.class);
        when(gamesCollection.document("new-slug")).thenReturn(ref);
        SettableApiFuture<WriteResult> future = SettableApiFuture.create();
        future.set(mock(WriteResult.class));
        when(ref.create(any(GameDocumentDto.class))).thenReturn(future);

        boolean created = client.createIfNotExists("new-slug", sampleDto("new-slug", "New"));

        assertThat(created).isTrue();
    }

    @Test
    void createIfNotExists_returnsFalseWhenAlreadyExists() {
        DocumentReference ref = mock(DocumentReference.class);
        when(gamesCollection.document("existing")).thenReturn(ref);
        FirestoreException fe = FirestoreException.forServerRejection(
                io.grpc.Status.ALREADY_EXISTS, "doc exists");
        SettableApiFuture<WriteResult> future = SettableApiFuture.create();
        future.setException(fe);
        when(ref.create(any(GameDocumentDto.class))).thenReturn(future);

        boolean created = client.createIfNotExists("existing", sampleDto("existing", "Existing"));

        assertThat(created).isFalse();
    }

    @Test
    void createIfNotExists_wrapsOtherExecutionException() {
        DocumentReference ref = mock(DocumentReference.class);
        when(gamesCollection.document("slug")).thenReturn(ref);
        RuntimeException underlying = new RuntimeException("permission denied");
        SettableApiFuture<WriteResult> future = SettableApiFuture.create();
        future.setException(underlying);
        when(ref.create(any(GameDocumentDto.class))).thenReturn(future);

        assertThatThrownBy(() -> client.createIfNotExists("slug", sampleDto("slug", "Slug")))
                .isInstanceOf(FirebaseUnavailableException.class)
                .hasMessageContaining("slug")
                .hasCause(underlying);
    }

    @Test
    void update_callsFirestoreUpdateWithGivenPatch() throws Exception {
        DocumentReference ref = mock(DocumentReference.class);
        when(gamesCollection.document("slug")).thenReturn(ref);
        SettableApiFuture<WriteResult> future = SettableApiFuture.create();
        future.set(mock(WriteResult.class));
        when(ref.update(anyMap())).thenReturn(future);

        HydrationPatch patch = new HydrationPatch(
                "X", CheapsharkBlock.empty(), RawgBlock.empty(),
                null);
        client.update("slug", patch);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(ref).update(captor.capture());
        assertThat(captor.getValue())
                .containsEntry("title", "X")
                .containsKey("cheapshark")
                .containsKey("rawg")
                .containsKey("validationReport");
    }

    @Test
    void update_omitsNullCheapsharkToPreserveExistingBlock() throws Exception {
        DocumentReference ref = mock(DocumentReference.class);
        when(gamesCollection.document("slug")).thenReturn(ref);
        SettableApiFuture<WriteResult> future = SettableApiFuture.create();
        future.set(mock(WriteResult.class));
        when(ref.update(anyMap())).thenReturn(future);

        HydrationPatch patch = new HydrationPatch(
                "X", null, RawgBlock.empty(),
                null);
        client.update("slug", patch);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(ref).update(captor.capture());
        assertThat(captor.getValue())
                .containsEntry("title", "X")
                .doesNotContainKey("cheapshark")
                .containsKey("rawg")
                .containsKey("validationReport");
    }

    @Test
    void update_omitsNullRawgToPreserveExistingBlock() throws Exception {
        DocumentReference ref = mock(DocumentReference.class);
        when(gamesCollection.document("slug")).thenReturn(ref);
        SettableApiFuture<WriteResult> future = SettableApiFuture.create();
        future.set(mock(WriteResult.class));
        when(ref.update(anyMap())).thenReturn(future);

        HydrationPatch patch = new HydrationPatch(
                "X", CheapsharkBlock.empty(), null,
                null);
        client.update("slug", patch);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(ref).update(captor.capture());
        assertThat(captor.getValue())
                .containsEntry("title", "X")
                .containsKey("cheapshark")
                .doesNotContainKey("rawg")
                .containsKey("validationReport");
    }

    @Test
    void update_wrapsExecutionException() {
        DocumentReference ref = mock(DocumentReference.class);
        when(gamesCollection.document("slug")).thenReturn(ref);
        RuntimeException underlying = new RuntimeException("not found");
        SettableApiFuture<WriteResult> future = SettableApiFuture.create();
        future.setException(underlying);
        when(ref.update(anyMap())).thenReturn(future);

        HydrationPatch patch = new HydrationPatch(
                "X", CheapsharkBlock.empty(), RawgBlock.empty(),
                null);
        assertThatThrownBy(() -> client.update("slug", patch))
                .isInstanceOf(FirebaseUnavailableException.class)
                .hasMessageContaining("slug")
                .hasCause(underlying);
    }

    @Test
    void createIfNotExists_retries_on_transient_error_then_succeeds() {
        FirebaseClient retryClient = new FirebaseClient(firestore, "games", 300, 2, 1L);
        DocumentReference ref = mock(DocumentReference.class);
        when(gamesCollection.document("slug")).thenReturn(ref);

        SettableApiFuture<WriteResult> firstAttempt = SettableApiFuture.create();
        firstAttempt.setException(FirestoreException.forServerRejection(Status.UNAVAILABLE, "network blip"));
        SettableApiFuture<WriteResult> secondAttempt = SettableApiFuture.create();
        secondAttempt.set(mock(WriteResult.class));
        when(ref.create(any(GameDocumentDto.class)))
                .thenReturn(firstAttempt)
                .thenReturn(secondAttempt);

        boolean created = retryClient.createIfNotExists("slug", sampleDto("slug", "Slug"));

        assertThat(created).isTrue();
        org.mockito.Mockito.verify(ref, org.mockito.Mockito.times(2))
                .create(any(GameDocumentDto.class));
    }

    @Test
    void createIfNotExists_throws_after_exhausting_retries() {
        FirebaseClient retryClient = new FirebaseClient(firestore, "games", 300, 2, 1L);
        DocumentReference ref = mock(DocumentReference.class);
        when(gamesCollection.document("slug")).thenReturn(ref);

        SettableApiFuture<WriteResult> future = SettableApiFuture.create();
        future.setException(FirestoreException.forServerRejection(Status.UNAVAILABLE, "still down"));
        when(ref.create(any(GameDocumentDto.class))).thenReturn(future);

        assertThatThrownBy(() -> retryClient.createIfNotExists("slug", sampleDto("slug", "Slug")))
                .isInstanceOf(FirebaseUnavailableException.class)
                .hasMessageContaining("slug");

        // 1 initial attempt + 2 retries = 3 invocations.
        org.mockito.Mockito.verify(ref, org.mockito.Mockito.times(3))
                .create(any(GameDocumentDto.class));
    }

    @Test
    void createIfNotExists_does_not_retry_on_permanent_error() {
        FirebaseClient retryClient = new FirebaseClient(firestore, "games", 300, 2, 1L);
        DocumentReference ref = mock(DocumentReference.class);
        when(gamesCollection.document("slug")).thenReturn(ref);

        // PERMISSION_DENIED is a permanent failure: no retry, immediate throw.
        SettableApiFuture<WriteResult> future = SettableApiFuture.create();
        future.setException(FirestoreException.forServerRejection(Status.PERMISSION_DENIED, "denied"));
        when(ref.create(any(GameDocumentDto.class))).thenReturn(future);

        assertThatThrownBy(() -> retryClient.createIfNotExists("slug", sampleDto("slug", "Slug")))
                .isInstanceOf(FirebaseUnavailableException.class);

        org.mockito.Mockito.verify(ref, org.mockito.Mockito.times(1))
                .create(any(GameDocumentDto.class));
    }

    @Test
    void update_throws_DocumentNotFoundException_on_not_found() {
        FirebaseClient retryClient = new FirebaseClient(firestore, "games", 300, 2, 1L);
        DocumentReference ref = mock(DocumentReference.class);
        when(gamesCollection.document("missing")).thenReturn(ref);

        SettableApiFuture<WriteResult> future = SettableApiFuture.create();
        future.setException(FirestoreException.forServerRejection(Status.NOT_FOUND, "no such doc"));
        when(ref.update(anyMap())).thenReturn(future);

        HydrationPatch patch = new HydrationPatch(
                "X", CheapsharkBlock.empty(), RawgBlock.empty(),
                null);
        assertThatThrownBy(() -> retryClient.update("missing", patch))
                .isInstanceOf(DocumentNotFoundException.class)
                .isInstanceOf(FirebaseUnavailableException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void update_does_not_retry_on_not_found() {
        // NOT_FOUND is not transient: the call must surface immediately
        // without burning retry budget.
        FirebaseClient retryClient = new FirebaseClient(firestore, "games", 300, 2, 1L);
        DocumentReference ref = mock(DocumentReference.class);
        when(gamesCollection.document("missing")).thenReturn(ref);

        SettableApiFuture<WriteResult> future = SettableApiFuture.create();
        future.setException(FirestoreException.forServerRejection(Status.NOT_FOUND, "no such doc"));
        when(ref.update(anyMap())).thenReturn(future);

        HydrationPatch patch = new HydrationPatch(
                "X", CheapsharkBlock.empty(), RawgBlock.empty(),
                null);
        try {
            retryClient.update("missing", patch);
        } catch (DocumentNotFoundException expected) {
            org.mockito.Mockito.verify(ref, org.mockito.Mockito.times(1)).update(anyMap());
            return;
        }
        throw new AssertionError("expected DocumentNotFoundException");
    }

    @Test
    void update_retries_on_transient_error_then_succeeds() {
        FirebaseClient retryClient = new FirebaseClient(firestore, "games", 300, 2, 1L);
        DocumentReference ref = mock(DocumentReference.class);
        when(gamesCollection.document("slug")).thenReturn(ref);

        SettableApiFuture<WriteResult> firstAttempt = SettableApiFuture.create();
        firstAttempt.setException(FirestoreException.forServerRejection(Status.UNAVAILABLE, "blip"));
        SettableApiFuture<WriteResult> secondAttempt = SettableApiFuture.create();
        secondAttempt.set(mock(WriteResult.class));
        when(ref.update(anyMap())).thenReturn(firstAttempt).thenReturn(secondAttempt);

        HydrationPatch patch = new HydrationPatch(
                "X", CheapsharkBlock.empty(), RawgBlock.empty(),
                null);
        retryClient.update("slug", patch);

        org.mockito.Mockito.verify(ref, org.mockito.Mockito.times(2)).update(anyMap());
    }

    @Test
    void readAll_retries_on_transient_error_in_first_page() {
        FirebaseClient retryClient = new FirebaseClient(firestore, "games", 300, 2, 1L);
        QueryDocumentSnapshot doc1 = mockQueryDoc("portal", "Portal");

        // The leaf query is the one that calls .get(); the intermediate
        // query (returned by orderBy) is just chained into .limit(...).
        Query orderedQuery = mock(Query.class);
        Query leafQuery = mock(Query.class);

        SettableApiFuture<QuerySnapshot> firstAttempt = SettableApiFuture.create();
        firstAttempt.setException(FirestoreException.forServerRejection(
                Status.DEADLINE_EXCEEDED, "slow"));
        SettableApiFuture<QuerySnapshot> secondAttempt = SettableApiFuture.create();
        QuerySnapshot snapshot = mock(QuerySnapshot.class);
        when(snapshot.getDocuments()).thenReturn(List.of(doc1));
        secondAttempt.set(snapshot);
        when(leafQuery.get()).thenReturn(firstAttempt).thenReturn(secondAttempt);

        when(gamesCollection.orderBy(any(FieldPath.class))).thenReturn(orderedQuery);
        when(orderedQuery.limit(anyInt())).thenReturn(leafQuery);

        List<GameDocumentDto> result = collect(retryClient.readAll());

        assertThat(result).hasSize(1);
        org.mockito.Mockito.verify(leafQuery, org.mockito.Mockito.times(2)).get();
    }

    private static GameDocumentDto sampleDto(String slug, String title) {
        return GameDocumentDtoFixtures.emptyDoc(slug, title);
    }

    private static QueryDocumentSnapshot mockQueryDoc(String slug, String title) {
        QueryDocumentSnapshot snap = mock(QueryDocumentSnapshot.class);
        when(snap.toObject(GameDocumentDto.class)).thenReturn(GameDocumentDtoFixtures.emptyDoc(slug, title));
        return snap;
    }

    private static Query mockQueryReturning(List<QueryDocumentSnapshot> docs) {
        Query query = mock(Query.class);
        QuerySnapshot snapshot = mock(QuerySnapshot.class);
        when(snapshot.getDocuments()).thenReturn(docs);
        SettableApiFuture<QuerySnapshot> future = SettableApiFuture.create();
        future.set(snapshot);
        when(query.get()).thenReturn(future);
        return query;
    }

    @Test
    void readPending_returnsMaterialisedListOfPendingDocs() throws Exception {
        com.cheapquest.backend.dto.firebase.PendingDoc p1 =
                new com.cheapquest.backend.dto.firebase.PendingDoc(
                        "portal", 1, Instant.parse("2026-06-30T10:00:00Z"), null);
        com.cheapquest.backend.dto.firebase.PendingDoc p2 =
                new com.cheapquest.backend.dto.firebase.PendingDoc(
                        "hl2", 0, null, null);

        com.google.cloud.firestore.QueryDocumentSnapshot d1 = mockQueryDoc("portal", "Portal");
        com.google.cloud.firestore.QueryDocumentSnapshot d2 = mockQueryDoc("hl2", "Half-Life 2");
        when(d1.toObject(com.cheapquest.backend.dto.firebase.PendingDoc.class)).thenReturn(p1);
        when(d2.toObject(com.cheapquest.backend.dto.firebase.PendingDoc.class)).thenReturn(p2);

        com.google.cloud.firestore.Query orderedQuery = mock(com.google.cloud.firestore.Query.class);
        com.google.cloud.firestore.QuerySnapshot pendingSnapshot = mock(com.google.cloud.firestore.QuerySnapshot.class);
        when(pendingSnapshot.getDocuments()).thenReturn(List.of(d1, d2));
        SettableApiFuture<com.google.cloud.firestore.QuerySnapshot> future = SettableApiFuture.create();
        future.set(pendingSnapshot);
        when(orderedQuery.get()).thenReturn(future);

        com.google.cloud.firestore.CollectionReference pendingCollection =
                mock(com.google.cloud.firestore.CollectionReference.class);
        when(firestore.collection("pending")).thenReturn(pendingCollection);
        when(pendingCollection.orderBy(any(FieldPath.class))).thenReturn(orderedQuery);

        List<com.cheapquest.backend.dto.firebase.PendingDoc> result = client.readPending();

        assertThat(result).containsExactly(p1, p2);
    }

    @Test
    void readFailed_returnsMaterialisedListOfFailedDocs() throws Exception {
        com.cheapquest.backend.dto.firebase.FailedDoc f1 =
                new com.cheapquest.backend.dto.firebase.FailedDoc(
                        "portal", 3,
                        Instant.parse("2026-06-29T10:00:00Z"),
                        Instant.parse("2026-06-30T10:00:00Z"),
                        "both sources returned empty");
        com.cheapquest.backend.dto.firebase.FailedDoc f2 =
                new com.cheapquest.backend.dto.firebase.FailedDoc(
                        "hl2", 3,
                        Instant.parse("2026-06-29T10:05:00Z"),
                        Instant.parse("2026-06-30T10:05:00Z"),
                        "rawg 404");

        com.google.cloud.firestore.QueryDocumentSnapshot d1 = mockQueryDoc("portal", "Portal");
        com.google.cloud.firestore.QueryDocumentSnapshot d2 = mockQueryDoc("hl2", "Half-Life 2");
        when(d1.toObject(com.cheapquest.backend.dto.firebase.FailedDoc.class)).thenReturn(f1);
        when(d2.toObject(com.cheapquest.backend.dto.firebase.FailedDoc.class)).thenReturn(f2);

        com.google.cloud.firestore.Query orderedQuery = mock(com.google.cloud.firestore.Query.class);
        com.google.cloud.firestore.QuerySnapshot failedSnapshot = mock(com.google.cloud.firestore.QuerySnapshot.class);
        when(failedSnapshot.getDocuments()).thenReturn(List.of(d1, d2));
        SettableApiFuture<com.google.cloud.firestore.QuerySnapshot> future = SettableApiFuture.create();
        future.set(failedSnapshot);
        when(orderedQuery.get()).thenReturn(future);

        com.google.cloud.firestore.CollectionReference failedCollection =
                mock(com.google.cloud.firestore.CollectionReference.class);
        when(firestore.collection("failed")).thenReturn(failedCollection);
        when(failedCollection.orderBy(any(FieldPath.class))).thenReturn(orderedQuery);

        List<com.cheapquest.backend.dto.firebase.FailedDoc> result = client.readFailed();

        assertThat(result).containsExactly(f1, f2);
    }

    @Test
    void markLocaleSynced_writesDotNotationPartialUpdate() throws Exception {
        DocumentReference ref = mock(DocumentReference.class);
        when(gamesCollection.document("portal")).thenReturn(ref);
        SettableApiFuture<WriteResult> future = SettableApiFuture.create();
        future.set(mock(WriteResult.class));
        when(ref.update(anyMap())).thenReturn(future);

        Instant now = Instant.parse("2026-06-30T10:00:00Z");
        client.markLocaleSynced("portal", "en", now);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(ref).update(captor.capture());
        // Dot-notation: only the two nested fields are written,
        // the rest of locales.* and the rest of the document are
        // untouched.
        assertThat(captor.getValue())
                .containsEntry("locales.en.synced", Boolean.TRUE)
                .containsEntry("locales.en.updatedAt", "2026-06-30T10:00:00Z")
                .doesNotContainKey("locales.es")
                .doesNotContainKey("locales.fr")
                .hasSize(2);
    }

    @Test
    void markLocaleSynced_rejectsBlankLang() {
        assertThatThrownBy(() ->
                client.markLocaleSynced("portal", "", Instant.parse("2026-06-30T10:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lang");
        assertThatThrownBy(() ->
                client.markLocaleSynced("portal", null, Instant.parse("2026-06-30T10:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lang");
    }

    @Test
    void addToPending_createsFirstAttempt() throws Exception {
        com.google.cloud.firestore.CollectionReference pending = mock(CollectionReference.class);
        com.google.cloud.firestore.DocumentReference ref = mock(DocumentReference.class);
        when(firestore.collection("pending")).thenReturn(pending);
        when(pending.document("portal")).thenReturn(ref);
        SettableApiFuture<WriteResult> future = SettableApiFuture.create();
        future.set(mock(WriteResult.class));
        when(ref.create(any(com.cheapquest.backend.dto.firebase.PendingDoc.class))).thenReturn(future);

        client.addToPending("portal");

        ArgumentCaptor<com.cheapquest.backend.dto.firebase.PendingDoc> captor =
                ArgumentCaptor.forClass(com.cheapquest.backend.dto.firebase.PendingDoc.class);
        verify(ref).create(captor.capture());
        assertThat(captor.getValue().slug()).isEqualTo("portal");
        assertThat(captor.getValue().attempts()).isEqualTo(1);
        assertThat(captor.getValue().lastError()).isNull();
    }

    @Test
    void addToPending_isNoOpWhenAlreadyExists() {
        com.google.cloud.firestore.CollectionReference pending = mock(CollectionReference.class);
        com.google.cloud.firestore.DocumentReference ref = mock(DocumentReference.class);
        when(firestore.collection("pending")).thenReturn(pending);
        when(pending.document("portal")).thenReturn(ref);
        SettableApiFuture<WriteResult> future = SettableApiFuture.create();
        future.setException(FirestoreException.forServerRejection(Status.ALREADY_EXISTS, "dup"));
        when(ref.create(any(com.cheapquest.backend.dto.firebase.PendingDoc.class))).thenReturn(future);

        // No exception expected: a duplicate enqueue is a no-op so
        // the operator can re-enqueue a slug without resetting the
        // attempt counter.
        client.addToPending("portal");
    }

    @Test
    void removeFromPending_deletesDoc() throws Exception {
        com.google.cloud.firestore.CollectionReference pending = mock(CollectionReference.class);
        com.google.cloud.firestore.DocumentReference ref = mock(DocumentReference.class);
        when(firestore.collection("pending")).thenReturn(pending);
        when(pending.document("portal")).thenReturn(ref);
        SettableApiFuture<WriteResult> future = SettableApiFuture.create();
        future.set(mock(WriteResult.class));
        when(ref.delete()).thenReturn(future);

        client.removeFromPending("portal");

        verify(ref).delete();
    }

    @Test
    void recordPendingFailure_writesUpdatedDoc() throws Exception {
        com.google.cloud.firestore.CollectionReference pending = mock(CollectionReference.class);
        com.google.cloud.firestore.DocumentReference ref = mock(DocumentReference.class);
        when(firestore.collection("pending")).thenReturn(pending);
        when(pending.document("portal")).thenReturn(ref);
        SettableApiFuture<WriteResult> future = SettableApiFuture.create();
        future.set(mock(WriteResult.class));
        when(ref.set(any(com.cheapquest.backend.dto.firebase.PendingDoc.class))).thenReturn(future);

        Instant now = Instant.parse("2026-06-30T10:05:00Z");
        client.recordPendingFailure("portal", 2, now, "network blip");

        ArgumentCaptor<com.cheapquest.backend.dto.firebase.PendingDoc> captor =
                ArgumentCaptor.forClass(com.cheapquest.backend.dto.firebase.PendingDoc.class);
        verify(ref).set(captor.capture());
        assertThat(captor.getValue().slug()).isEqualTo("portal");
        assertThat(captor.getValue().attempts()).isEqualTo(2);
        assertThat(captor.getValue().lastAttemptAt()).isEqualTo(now);
        assertThat(captor.getValue().lastError()).isEqualTo("network blip");
    }

    @Test
    void moveToFailed_writesFailedDocAndRemovesFromPending() throws Exception {
        com.google.cloud.firestore.CollectionReference failed = mock(CollectionReference.class);
        com.google.cloud.firestore.DocumentReference failedRef = mock(DocumentReference.class);
        com.google.cloud.firestore.CollectionReference pending = mock(CollectionReference.class);
        com.google.cloud.firestore.DocumentReference pendingRef = mock(DocumentReference.class);
        when(firestore.collection("failed")).thenReturn(failed);
        when(failed.document("portal")).thenReturn(failedRef);
        when(firestore.collection("pending")).thenReturn(pending);
        when(pending.document("portal")).thenReturn(pendingRef);
        SettableApiFuture<WriteResult> setFuture = SettableApiFuture.create();
        setFuture.set(mock(WriteResult.class));
        when(failedRef.set(any(com.cheapquest.backend.dto.firebase.FailedDoc.class))).thenReturn(setFuture);
        SettableApiFuture<WriteResult> delFuture = SettableApiFuture.create();
        delFuture.set(mock(WriteResult.class));
        when(pendingRef.delete()).thenReturn(delFuture);

        com.cheapquest.backend.dto.firebase.FailedDoc doc = new com.cheapquest.backend.dto.firebase.FailedDoc(
                "portal", 3,
                Instant.parse("2026-06-30T10:00:00Z"),
                Instant.parse("2026-06-30T10:05:00Z"),
                "no such game");
        client.moveToFailed(doc);

        ArgumentCaptor<com.cheapquest.backend.dto.firebase.FailedDoc> captor =
                ArgumentCaptor.forClass(com.cheapquest.backend.dto.firebase.FailedDoc.class);
        verify(failedRef).set(captor.capture());
        assertThat(captor.getValue()).isEqualTo(doc);
        verify(pendingRef).delete();
    }

    @Test
    void enqueueTranslation_createsFirstAttemptDoc() throws Exception {
        CollectionReference translationPending = mock(CollectionReference.class);
        DocumentReference ref = mock(DocumentReference.class);
        when(firestore.collection("translations-pending")).thenReturn(translationPending);
        when(translationPending.document("portal_es")).thenReturn(ref);
        SettableApiFuture<WriteResult> future = SettableApiFuture.create();
        future.set(mock(WriteResult.class));
        when(ref.create(any(TranslationPendingDoc.class))).thenReturn(future);

        Instant now = Instant.parse("2026-06-30T10:00:00Z");
        client.enqueueTranslation("portal", "es", now);

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
    void enqueueTranslation_isNoOpWhenAlreadyExists() {
        CollectionReference translationPending = mock(CollectionReference.class);
        DocumentReference ref = mock(DocumentReference.class);
        when(firestore.collection("translations-pending")).thenReturn(translationPending);
        when(translationPending.document("portal_es")).thenReturn(ref);
        SettableApiFuture<WriteResult> future = SettableApiFuture.create();
        future.setException(FirestoreException.forServerRejection(Status.ALREADY_EXISTS, "dup"));
        when(ref.create(any(TranslationPendingDoc.class))).thenReturn(future);

        // No exception expected: a duplicate enqueue is a no-op so
        // the per-attempt counter is not reset on every refresh.
        client.enqueueTranslation("portal", "es", Instant.now());
    }

    @Test
    void readTranslationPending_returnsListOfEntries() throws Exception {
        TranslationPendingDoc p1 = new TranslationPendingDoc(
                "portal", "es", Instant.parse("2026-06-30T10:00:00Z"), 1, Instant.now(), null);
        TranslationPendingDoc p2 = new TranslationPendingDoc(
                "hl2", "fr", Instant.parse("2026-06-30T11:00:00Z"), 2, Instant.now(), "blip");

        QueryDocumentSnapshot d1 = mockQueryDoc("portal", "Portal");
        QueryDocumentSnapshot d2 = mockQueryDoc("hl2", "Half-Life 2");
        when(d1.toObject(TranslationPendingDoc.class)).thenReturn(p1);
        when(d2.toObject(TranslationPendingDoc.class)).thenReturn(p2);

        Query orderedQuery = mock(Query.class);
        QuerySnapshot snapshot = mock(QuerySnapshot.class);
        when(snapshot.getDocuments()).thenReturn(List.of(d1, d2));
        SettableApiFuture<QuerySnapshot> future = SettableApiFuture.create();
        future.set(snapshot);
        when(orderedQuery.get()).thenReturn(future);

        CollectionReference translationPending = mock(CollectionReference.class);
        when(firestore.collection("translations-pending")).thenReturn(translationPending);
        when(translationPending.orderBy(any(FieldPath.class))).thenReturn(orderedQuery);

        List<TranslationPendingDoc> result = client.readTranslationPending();

        assertThat(result).containsExactly(p1, p2);
    }

    @Test
    void recordTranslationFailure_preservesSourceFetchedAt() throws Exception {
        Instant sourceFetchedAt = Instant.parse("2026-06-30T10:00:00Z");
        Instant lastAttemptAt = Instant.parse("2026-06-30T10:05:00Z");
        TranslationPendingDoc current = new TranslationPendingDoc(
                "portal", "es", sourceFetchedAt, 1, Instant.now(), null);

        CollectionReference translationPending = mock(CollectionReference.class);
        DocumentReference ref = mock(DocumentReference.class);
        DocumentSnapshot snap = mock(DocumentSnapshot.class);
        when(firestore.collection("translations-pending")).thenReturn(translationPending);
        when(translationPending.document("portal_es")).thenReturn(ref);
        when(ref.get()).thenReturn(snapFuture(snap));
        when(snap.exists()).thenReturn(true);
        when(snap.toObject(TranslationPendingDoc.class)).thenReturn(current);
        SettableApiFuture<WriteResult> future = SettableApiFuture.create();
        future.set(mock(WriteResult.class));
        when(ref.set(any(TranslationPendingDoc.class))).thenReturn(future);

        client.recordTranslationFailure("portal", "es", 2, lastAttemptAt, "blip");

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
    void removeFromTranslationPending_deletesDoc() throws Exception {
        CollectionReference translationPending = mock(CollectionReference.class);
        DocumentReference ref = mock(DocumentReference.class);
        when(firestore.collection("translations-pending")).thenReturn(translationPending);
        when(translationPending.document("hl2_fr")).thenReturn(ref);
        SettableApiFuture<WriteResult> future = SettableApiFuture.create();
        future.set(mock(WriteResult.class));
        when(ref.delete()).thenReturn(future);

        client.removeFromTranslationPending("hl2", "fr");

        verify(ref).delete();
    }

    @Test
    void moveToTranslationFailed_writesFailedDocAndRemovesFromPending() throws Exception {
        CollectionReference failedCol = mock(CollectionReference.class);
        CollectionReference pendingCol = mock(CollectionReference.class);
        DocumentReference failedRef = mock(DocumentReference.class);
        DocumentReference pendingRef = mock(DocumentReference.class);
        when(firestore.collection("translations-failed")).thenReturn(failedCol);
        when(failedCol.document("portal_es")).thenReturn(failedRef);
        when(firestore.collection("translations-pending")).thenReturn(pendingCol);
        when(pendingCol.document("portal_es")).thenReturn(pendingRef);
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
        client.moveToTranslationFailed(doc);

        ArgumentCaptor<TranslationFailedDoc> captor =
                ArgumentCaptor.forClass(TranslationFailedDoc.class);
        verify(failedRef).set(captor.capture());
        assertThat(captor.getValue()).isEqualTo(doc);
        verify(pendingRef).delete();
    }

    @Test
    void writeLocaleTranslation_writesDotNotationPartialUpdate() throws Exception {
        DocumentReference ref = mock(DocumentReference.class);
        when(gamesCollection.document("portal")).thenReturn(ref);
        SettableApiFuture<WriteResult> future = SettableApiFuture.create();
        future.set(mock(WriteResult.class));
        when(ref.update(anyMap())).thenReturn(future);

        Instant sourceFetchedAt = Instant.parse("2026-06-30T10:00:00Z");
        Instant translatedAt = Instant.parse("2026-06-30T10:05:00Z");
        client.writeLocaleTranslation(
                "portal", "es",
                "<p>Hola mundo</p>",
                List.of("Acción", "Aventura"),
                sourceFetchedAt, translatedAt);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(ref).update(captor.capture());
        assertThat(captor.getValue())
                .containsEntry("locales.es.synced", Boolean.TRUE)
                .containsEntry("locales.es.updatedAt", "2026-06-30T10:05:00Z")
                .containsEntry("locales.es.sourceFetchedAt", "2026-06-30T10:00:00Z")
                .containsEntry("locales.es.description", "<p>Hola mundo</p>")
                .containsEntry("locales.es.tags", List.of("Acción", "Aventura"))
                .doesNotContainKey("locales.fr")
                .hasSize(5);
    }

    private static SettableApiFuture<DocumentSnapshot> snapFuture(DocumentSnapshot snap) {
        SettableApiFuture<DocumentSnapshot> f = SettableApiFuture.create();
        f.set(snap);
        return f;
    }

    /**
     * Wires the {@code gamesCollection.orderBy(...).limit(N)} chain
     * that the paging iterator walks: the orderBy mock is the
     * intermediate query, its {@code limit} call returns the leaf
     * query that supplies the documents. Returns the orderBy mock
     * so the caller can verify how many times {@code limit} was
     * invoked (once per page).
     */
    private Query stubOrderedQuery(Query leaf) {
        Query ordered = mock(Query.class);
        when(ordered.limit(anyInt())).thenReturn(leaf);
        when(gamesCollection.orderBy(any(FieldPath.class))).thenReturn(ordered);
        return ordered;
    }

    private static List<GameDocumentDto> collect(Iterable<GameDocumentDto> iterable) {
        List<GameDocumentDto> out = new ArrayList<>();
        Iterator<GameDocumentDto> it = iterable.iterator();
        while (it.hasNext()) {
            out.add(it.next());
        }
        return out;
    }
}
