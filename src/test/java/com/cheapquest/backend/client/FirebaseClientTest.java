package com.cheapquest.backend.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import com.cheapquest.backend.exception.FirebaseUnavailableException;
import com.cheapquest.backend.fixtures.GameDocumentDtoFixtures;
import com.google.api.core.SettableApiFuture;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreException;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.cloud.firestore.WriteResult;
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
        props = mock(AppProperties.class);
        when(props.firestoreCollectionGamesPath()).thenReturn("games");
        when(props.firestoreReadPageSize()).thenReturn(300);
        client = new FirebaseClient(firestore, props);
    }

    @Test
    void readAll_iteratesSinglePageWhenCollectionFitsInOnePage() throws Exception {
        QueryDocumentSnapshot doc1 = mockQueryDoc("portal", "Portal");
        QueryDocumentSnapshot doc2 = mockQueryDoc("hl2", "Half-Life 2");
        Query firstQuery = mockQueryReturning(List.of(doc1, doc2));
        when(gamesCollection.limit(300)).thenReturn(firstQuery);

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
        when(gamesCollection.limit(2)).thenReturn(q1);
        when(q1.startAfter(d2)).thenReturn(q2);

        List<GameDocumentDto> result = collect(smallPageClient.readAll());

        assertThat(result).hasSize(3);
        verify(gamesCollection, org.mockito.Mockito.times(2)).limit(2);
        verify(q1).get();
        verify(q1).startAfter(d2);
        verify(q2).get();
        verify(q2, org.mockito.Mockito.never()).startAfter(any(DocumentSnapshot.class));
    }

    @Test
    void readAll_returnsEmptyIterableWhenCollectionIsEmpty() throws Exception {
        Query emptyQuery = mockQueryReturning(List.of());
        when(gamesCollection.limit(300)).thenReturn(emptyQuery);

        List<GameDocumentDto> result = collect(client.readAll());

        assertThat(result).isEmpty();
    }

    @Test
    void readAll_stopsAfterPartialLastPage() throws Exception {
        FirebaseClient smallPageClient = new FirebaseClient(firestore, "games", 3);
        QueryDocumentSnapshot d1 = mockQueryDoc("portal", "Portal");
        QueryDocumentSnapshot d2 = mockQueryDoc("hl2", "Half-Life 2");
        Query q1 = mockQueryReturning(List.of(d1, d2));
        when(gamesCollection.limit(3)).thenReturn(q1);

        List<GameDocumentDto> result = collect(smallPageClient.readAll());

        assertThat(result).hasSize(2);
        verify(q1).get();
        verify(q1, org.mockito.Mockito.never()).startAfter(any(DocumentSnapshot.class));
    }

    @Test
    void readAll_wrapsExecutionExceptionInFirebaseUnavailable() {
        Query failingQuery = mock(Query.class);
        SettableApiFuture<QuerySnapshot> future = SettableApiFuture.create();
        future.setException(new RuntimeException("network down"));
        when(failingQuery.get()).thenReturn(future);
        when(gamesCollection.limit(300)).thenReturn(failingQuery);

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
        when(gamesCollection.limit(300)).thenReturn(failingQuery);

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
                Map.of("es", LocaleBlock.unsynced(), "en", LocaleBlock.unsynced(), "fr", LocaleBlock.unsynced()),
                null);
        client.update("slug", patch);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(ref).update(captor.capture());
        assertThat(captor.getValue())
                .containsEntry("title", "X")
                .containsKey("cheapshark")
                .containsKey("rawg")
                .containsKey("locales")
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
                Map.of("es", LocaleBlock.unsynced(), "en", LocaleBlock.unsynced(), "fr", LocaleBlock.unsynced()),
                null);
        client.update("slug", patch);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(ref).update(captor.capture());
        assertThat(captor.getValue())
                .containsEntry("title", "X")
                .doesNotContainKey("cheapshark")
                .containsKey("rawg")
                .containsKey("locales")
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
                Map.of("es", LocaleBlock.unsynced(), "en", LocaleBlock.unsynced(), "fr", LocaleBlock.unsynced()),
                null);
        client.update("slug", patch);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(ref).update(captor.capture());
        assertThat(captor.getValue())
                .containsEntry("title", "X")
                .containsKey("cheapshark")
                .doesNotContainKey("rawg")
                .containsKey("locales")
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
                Map.of("es", LocaleBlock.unsynced(), "en", LocaleBlock.unsynced(), "fr", LocaleBlock.unsynced()),
                null);
        assertThatThrownBy(() -> client.update("slug", patch))
                .isInstanceOf(FirebaseUnavailableException.class)
                .hasMessageContaining("slug")
                .hasCause(underlying);
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

    private static List<GameDocumentDto> collect(Iterable<GameDocumentDto> iterable) {
        List<GameDocumentDto> out = new ArrayList<>();
        Iterator<GameDocumentDto> it = iterable.iterator();
        while (it.hasNext()) {
            out.add(it.next());
        }
        return out;
    }
}
