package com.cheapquest.backend.dao.firestore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cheapquest.backend.client.FirestoreRetrier;
import com.cheapquest.backend.dto.firebase.CheapsharkBlock;
import com.cheapquest.backend.dto.firebase.GameDocumentDto;
import com.cheapquest.backend.dto.firebase.HydrationPatch;
import com.cheapquest.backend.dto.firebase.RawgBlock;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FirestoreGameDaoTest {

    private Firestore firestore;
    private CollectionReference gamesCollection;
    private FirestoreGameDao dao;

    @BeforeEach
    void setUp() {
        firestore = mock(Firestore.class);
        gamesCollection = mock(CollectionReference.class);
        when(firestore.collection("games")).thenReturn(gamesCollection);
        FirestoreRetrier retrier = new FirestoreRetrier(2, 1L);
        dao = new FirestoreGameDao(firestore, "games", 300, retrier);
    }

    @Test
    void readAll_iteratesSinglePageWhenCollectionFitsInOnePage() {
        QueryDocumentSnapshot doc1 = mockQueryDoc("portal", "Portal");
        QueryDocumentSnapshot doc2 = mockQueryDoc("hl2", "Half-Life 2");
        Query firstQuery = mockQueryReturning(List.of(doc1, doc2));
        stubOrderedQuery(firstQuery);

        List<GameDocumentDto> result = collect(dao.readAll());

        assertThat(result).containsExactly(
                GameDocumentDtoFixtures.emptyDoc("portal", "Portal"),
                GameDocumentDtoFixtures.emptyDoc("hl2", "Half-Life 2"));
    }

    @Test
    void readAll_paginatesAcrossMultiplePages() {
        FirestoreGameDao smallPageDao = new FirestoreGameDao(
                firestore, "games", 2, new FirestoreRetrier(2, 1L));
        QueryDocumentSnapshot d1 = mockQueryDoc("portal", "Portal");
        QueryDocumentSnapshot d2 = mockQueryDoc("hl2", "Half-Life 2");
        QueryDocumentSnapshot d3 = mockQueryDoc("stardew", "Stardew Valley");

        Query q1 = mockQueryReturning(List.of(d1, d2));
        Query q2 = mockQueryReturning(List.of(d3));
        Query ordered = stubOrderedQuery(q1);
        when(q1.startAfter(d2)).thenReturn(q2);

        List<GameDocumentDto> result = collect(smallPageDao.readAll());

        assertThat(result).hasSize(3);
        verify(ordered, org.mockito.Mockito.times(2)).limit(2);
        verify(q1).get();
        verify(q1).startAfter(d2);
        verify(q2).get();
        verify(q2, org.mockito.Mockito.never()).startAfter(any(DocumentSnapshot.class));
    }

    @Test
    void readAll_returnsEmptyIterableWhenCollectionIsEmpty() {
        Query emptyQuery = mockQueryReturning(List.of());
        stubOrderedQuery(emptyQuery);

        List<GameDocumentDto> result = collect(dao.readAll());

        assertThat(result).isEmpty();
    }

    @Test
    void readAll_stopsAfterPartialLastPage() {
        FirestoreGameDao smallPageDao = new FirestoreGameDao(
                firestore, "games", 3, new FirestoreRetrier(2, 1L));
        QueryDocumentSnapshot d1 = mockQueryDoc("portal", "Portal");
        QueryDocumentSnapshot d2 = mockQueryDoc("hl2", "Half-Life 2");
        Query q1 = mockQueryReturning(List.of(d1, d2));
        stubOrderedQuery(q1);

        List<GameDocumentDto> result = collect(smallPageDao.readAll());

        assertThat(result).hasSize(2);
        verify(q1).get();
        verify(q1, org.mockito.Mockito.never()).startAfter(any(DocumentSnapshot.class));
    }

    @Test
    void readAll_usesDocumentIdOrderForStableCursor() {
        Query q = mockQueryReturning(List.of());
        stubOrderedQuery(q);

        collect(dao.readAll());

        verify(gamesCollection).orderBy(FieldPath.documentId());
    }

    @Test
    void readAll_wrapsExecutionExceptionInFirebaseUnavailable() {
        Query failingQuery = mock(Query.class);
        SettableApiFuture<QuerySnapshot> future = SettableApiFuture.create();
        future.setException(new RuntimeException("network down"));
        when(failingQuery.get()).thenReturn(future);
        stubOrderedQuery(failingQuery);

        assertThatThrownBy(() -> collect(dao.readAll()))
                .isInstanceOf(FirebaseUnavailableException.class)
                .hasMessageContaining("games")
                .hasCauseInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("network down");
    }

    @Test
    void read_returnsDtoWhenDocumentExists() {
        DocumentReference ref = mock(DocumentReference.class);
        DocumentSnapshot snap = mock(DocumentSnapshot.class);
        GameDocumentDto dto = sampleDto("portal", "Portal");
        when(gamesCollection.document("portal")).thenReturn(ref);
        when(snap.exists()).thenReturn(true);
        when(snap.toObject(GameDocumentDto.class)).thenReturn(dto);
        SettableApiFuture<DocumentSnapshot> future = SettableApiFuture.create();
        future.set(snap);
        when(ref.get()).thenReturn(future);

        assertThat(dao.read("portal")).contains(dto);
    }

    @Test
    void read_returnsEmptyWhenDocumentMissing() {
        DocumentReference ref = mock(DocumentReference.class);
        DocumentSnapshot snap = mock(DocumentSnapshot.class);
        when(gamesCollection.document("missing")).thenReturn(ref);
        when(snap.exists()).thenReturn(false);
        SettableApiFuture<DocumentSnapshot> future = SettableApiFuture.create();
        future.set(snap);
        when(ref.get()).thenReturn(future);

        assertThat(dao.read("missing")).isEmpty();
    }

    @Test
    void read_wrapsExecutionException() {
        DocumentReference ref = mock(DocumentReference.class);
        when(gamesCollection.document("portal")).thenReturn(ref);
        SettableApiFuture<DocumentSnapshot> future = SettableApiFuture.create();
        future.setException(new RuntimeException("io error"));
        when(ref.get()).thenReturn(future);

        assertThatThrownBy(() -> dao.read("portal"))
                .isInstanceOf(FirebaseUnavailableException.class)
                .hasMessageContaining("portal");
    }

    @Test
    void createIfNotExists_returnsTrueWhenCreated() {
        DocumentReference ref = mock(DocumentReference.class);
        when(gamesCollection.document("new-slug")).thenReturn(ref);
        SettableApiFuture<WriteResult> future = SettableApiFuture.create();
        future.set(mock(WriteResult.class));
        when(ref.create(any(GameDocumentDto.class))).thenReturn(future);

        boolean created = dao.createIfNotExists("new-slug", sampleDto("new-slug", "New"));

        assertThat(created).isTrue();
    }

    @Test
    void createIfNotExists_returnsFalseWhenAlreadyExists() {
        DocumentReference ref = mock(DocumentReference.class);
        when(gamesCollection.document("existing")).thenReturn(ref);
        FirestoreException fe = FirestoreException.forServerRejection(
                Status.ALREADY_EXISTS, "doc exists");
        SettableApiFuture<WriteResult> future = SettableApiFuture.create();
        future.setException(fe);
        when(ref.create(any(GameDocumentDto.class))).thenReturn(future);

        boolean created = dao.createIfNotExists("existing", sampleDto("existing", "Existing"));

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

        assertThatThrownBy(() -> dao.createIfNotExists("slug", sampleDto("slug", "Slug")))
                .isInstanceOf(FirebaseUnavailableException.class)
                .hasMessageContaining("slug")
                .hasCause(underlying);
    }

    @Test
    void update_callsFirestoreUpdateWithGivenPatch() {
        DocumentReference ref = mock(DocumentReference.class);
        when(gamesCollection.document("slug")).thenReturn(ref);
        SettableApiFuture<WriteResult> future = SettableApiFuture.create();
        future.set(mock(WriteResult.class));
        when(ref.update(anyMap())).thenReturn(future);

        HydrationPatch patch = new HydrationPatch(
                "X", CheapsharkBlock.empty(), RawgBlock.empty(), null);
        dao.update("slug", patch);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> captor =
                ArgumentCaptor.forClass(java.util.Map.class);
        verify(ref).update(captor.capture());
        assertThat(captor.getValue())
                .containsEntry("title", "X")
                .containsKey("cheapshark")
                .containsKey("rawg")
                .containsKey("validationReport");
    }

    @Test
    void update_omitsNullCheapsharkToPreserveExistingBlock() {
        DocumentReference ref = mock(DocumentReference.class);
        when(gamesCollection.document("slug")).thenReturn(ref);
        SettableApiFuture<WriteResult> future = SettableApiFuture.create();
        future.set(mock(WriteResult.class));
        when(ref.update(anyMap())).thenReturn(future);

        HydrationPatch patch = new HydrationPatch(
                "X", null, RawgBlock.empty(), null);
        dao.update("slug", patch);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> captor =
                ArgumentCaptor.forClass(java.util.Map.class);
        verify(ref).update(captor.capture());
        assertThat(captor.getValue())
                .containsEntry("title", "X")
                .doesNotContainKey("cheapshark")
                .containsKey("rawg")
                .containsKey("validationReport");
    }

    @Test
    void update_omitsNullRawgToPreserveExistingBlock() {
        DocumentReference ref = mock(DocumentReference.class);
        when(gamesCollection.document("slug")).thenReturn(ref);
        SettableApiFuture<WriteResult> future = SettableApiFuture.create();
        future.set(mock(WriteResult.class));
        when(ref.update(anyMap())).thenReturn(future);

        HydrationPatch patch = new HydrationPatch(
                "X", CheapsharkBlock.empty(), null, null);
        dao.update("slug", patch);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> captor =
                ArgumentCaptor.forClass(java.util.Map.class);
        verify(ref).update(captor.capture());
        assertThat(captor.getValue())
                .containsEntry("title", "X")
                .containsKey("cheapshark")
                .doesNotContainKey("rawg")
                .containsKey("validationReport");
    }

    @Test
    void update_throwsDocumentNotFoundOnNotFound() {
        DocumentReference ref = mock(DocumentReference.class);
        when(gamesCollection.document("missing")).thenReturn(ref);
        FirestoreException fe = FirestoreException.forServerRejection(
                Status.NOT_FOUND, "no such doc");
        SettableApiFuture<WriteResult> future = SettableApiFuture.create();
        future.setException(fe);
        when(ref.update(anyMap())).thenReturn(future);

        HydrationPatch patch = new HydrationPatch(
                "X", CheapsharkBlock.empty(), RawgBlock.empty(), null);
        assertThatThrownBy(() -> dao.update("missing", patch))
                .isInstanceOf(DocumentNotFoundException.class)
                .isInstanceOf(FirebaseUnavailableException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void update_doesNotRetryOnNotFound() {
        DocumentReference ref = mock(DocumentReference.class);
        when(gamesCollection.document("missing")).thenReturn(ref);
        FirestoreException fe = FirestoreException.forServerRejection(
                Status.NOT_FOUND, "no such doc");
        SettableApiFuture<WriteResult> future = SettableApiFuture.create();
        future.setException(fe);
        when(ref.update(anyMap())).thenReturn(future);

        HydrationPatch patch = new HydrationPatch(
                "X", CheapsharkBlock.empty(), RawgBlock.empty(), null);
        try {
            dao.update("missing", patch);
        } catch (DocumentNotFoundException expected) {
            verify(ref, org.mockito.Mockito.times(1)).update(anyMap());
            return;
        }
        org.junit.jupiter.api.Assertions.fail("expected DocumentNotFoundException");
    }

    @Test
    void update_retriesOnTransientErrorThenSucceeds() {
        DocumentReference ref = mock(DocumentReference.class);
        when(gamesCollection.document("slug")).thenReturn(ref);

        SettableApiFuture<WriteResult> firstAttempt = SettableApiFuture.create();
        firstAttempt.setException(FirestoreException.forServerRejection(
                Status.UNAVAILABLE, "blip"));
        SettableApiFuture<WriteResult> secondAttempt = SettableApiFuture.create();
        secondAttempt.set(mock(WriteResult.class));
        when(ref.update(anyMap())).thenReturn(firstAttempt).thenReturn(secondAttempt);

        HydrationPatch patch = new HydrationPatch(
                "X", CheapsharkBlock.empty(), RawgBlock.empty(), null);
        dao.update("slug", patch);

        verify(ref, org.mockito.Mockito.times(2)).update(anyMap());
    }

    @Test
    void markLocaleSynced_writesDotNotationPartialUpdate() {
        DocumentReference ref = mock(DocumentReference.class);
        when(gamesCollection.document("portal")).thenReturn(ref);
        SettableApiFuture<WriteResult> future = SettableApiFuture.create();
        future.set(mock(WriteResult.class));
        when(ref.update(anyMap())).thenReturn(future);

        Instant now = Instant.parse("2026-06-30T10:00:00Z");
        dao.markLocaleSynced("portal", "en", now);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> captor =
                ArgumentCaptor.forClass(java.util.Map.class);
        verify(ref).update(captor.capture());
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
                dao.markLocaleSynced("portal", "", Instant.parse("2026-06-30T10:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lang");
        assertThatThrownBy(() ->
                dao.markLocaleSynced("portal", null, Instant.parse("2026-06-30T10:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lang");
    }

    @Test
    void writeLocaleTranslation_writesDotNotationPartialUpdate() {
        DocumentReference ref = mock(DocumentReference.class);
        when(gamesCollection.document("portal")).thenReturn(ref);
        SettableApiFuture<WriteResult> future = SettableApiFuture.create();
        future.set(mock(WriteResult.class));
        when(ref.update(anyMap())).thenReturn(future);

        Instant sourceFetchedAt = Instant.parse("2026-06-30T10:00:00Z");
        Instant translatedAt = Instant.parse("2026-06-30T10:05:00Z");
        dao.writeLocaleTranslation(
                "portal", "es",
                "<p>Hola mundo</p>",
                List.of("Acción", "Aventura"),
                sourceFetchedAt, translatedAt);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> captor =
                ArgumentCaptor.forClass(java.util.Map.class);
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

    @Test
    void writeLocaleTranslation_skipsNullFields() {
        DocumentReference ref = mock(DocumentReference.class);
        when(gamesCollection.document("portal")).thenReturn(ref);
        SettableApiFuture<WriteResult> future = SettableApiFuture.create();
        future.set(mock(WriteResult.class));
        when(ref.update(anyMap())).thenReturn(future);

        Instant sourceFetchedAt = Instant.parse("2026-06-30T10:00:00Z");
        Instant translatedAt = Instant.parse("2026-06-30T10:05:00Z");
        dao.writeLocaleTranslation("portal", "es", null, null,
                sourceFetchedAt, translatedAt);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> captor =
                ArgumentCaptor.forClass(java.util.Map.class);
        verify(ref).update(captor.capture());
        // Only the sync flag, updatedAt, and sourceFetchedAt are written.
        assertThat(captor.getValue())
                .containsEntry("locales.es.synced", Boolean.TRUE)
                .containsEntry("locales.es.updatedAt", "2026-06-30T10:05:00Z")
                .containsEntry("locales.es.sourceFetchedAt", "2026-06-30T10:00:00Z")
                .doesNotContainKey("locales.es.description")
                .doesNotContainKey("locales.es.tags")
                .hasSize(3);
    }

    private static GameDocumentDto sampleDto(String slug, String title) {
        return GameDocumentDtoFixtures.emptyDoc(slug, title);
    }

    private static QueryDocumentSnapshot mockQueryDoc(String slug, String title) {
        QueryDocumentSnapshot snap = mock(QueryDocumentSnapshot.class);
        when(snap.toObject(GameDocumentDto.class))
                .thenReturn(GameDocumentDtoFixtures.emptyDoc(slug, title));
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
