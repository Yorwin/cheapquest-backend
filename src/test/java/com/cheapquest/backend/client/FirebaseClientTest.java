package com.cheapquest.backend.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cheapquest.backend.config.AppProperties;
import com.cheapquest.backend.dto.firebase.GameDocumentDto;
import com.cheapquest.backend.exception.FirebaseUnavailableException;
import com.cheapquest.backend.fixtures.GameDocumentDtoFixtures;
import com.google.api.core.ApiFuture;
import com.google.api.core.SettableApiFuture;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreException;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.cloud.firestore.WriteResult;
import io.grpc.Status;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
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
        client = new FirebaseClient(firestore, props);
    }

    @Test
    void readAll_returnsListOfDocuments() throws Exception {
        QueryDocumentSnapshot doc1 = mock(QueryDocumentSnapshot.class);
        QueryDocumentSnapshot doc2 = mock(QueryDocumentSnapshot.class);
        GameDocumentDto dto1 = sampleDto("portal", "Portal");
        GameDocumentDto dto2 = sampleDto("hl2", "Half-Life 2");
        when(doc1.toObject(GameDocumentDto.class)).thenReturn(dto1);
        when(doc2.toObject(GameDocumentDto.class)).thenReturn(dto2);
        QuerySnapshot snapshot = mock(QuerySnapshot.class);
        when(snapshot.size()).thenReturn(2);
        when(snapshot.getDocuments()).thenReturn(List.of(doc1, doc2));
        SettableApiFuture<QuerySnapshot> future = SettableApiFuture.create();
        future.set(snapshot);
        when(gamesCollection.get()).thenReturn(future);

        List<GameDocumentDto> result = client.readAll();

        assertThat(result).containsExactly(dto1, dto2);
    }

    @Test
    void readAll_wrapsExecutionExceptionInFirebaseUnavailable() {
        SettableApiFuture<QuerySnapshot> future = SettableApiFuture.create();
        future.setException(new RuntimeException("network down"));
        when(gamesCollection.get()).thenReturn(future);

        assertThatThrownBy(() -> client.readAll())
                .isInstanceOf(FirebaseUnavailableException.class)
                .hasMessageContaining("games")
                .hasCauseInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("network down");
    }

    @Test
    void readAll_wrapsRuntimeException() {
        when(gamesCollection.get()).thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> client.readAll())
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
    void update_callsFirestoreUpdateWithGivenFields() throws Exception {
        DocumentReference ref = mock(DocumentReference.class);
        when(gamesCollection.document("slug")).thenReturn(ref);
        SettableApiFuture<WriteResult> future = SettableApiFuture.create();
        future.set(mock(WriteResult.class));
        when(ref.update(anyMap())).thenReturn(future);

        Map<String, Object> patch = Map.of("title", "X", "active", true);
        client.update("slug", patch);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(ref).update(captor.capture());
        assertThat(captor.getValue()).containsEntry("title", "X").containsEntry("active", true);
    }

    @Test
    void update_wrapsExecutionException() {
        DocumentReference ref = mock(DocumentReference.class);
        when(gamesCollection.document("slug")).thenReturn(ref);
        RuntimeException underlying = new RuntimeException("not found");
        SettableApiFuture<WriteResult> future = SettableApiFuture.create();
        future.setException(underlying);
        when(ref.update(anyMap())).thenReturn(future);

        assertThatThrownBy(() -> client.update("slug", Map.of("k", "v")))
                .isInstanceOf(FirebaseUnavailableException.class)
                .hasMessageContaining("slug")
                .hasCause(underlying);
    }

    private static GameDocumentDto sampleDto(String slug, String title) {
        return GameDocumentDtoFixtures.emptyDoc(slug, title);
    }
}
