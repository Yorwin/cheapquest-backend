package com.cheapquest.backend.service.sections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cheapquest.backend.domain.Offer;
import com.cheapquest.backend.domain.sections.SectionItem;
import com.cheapquest.backend.domain.sections.SectionName;
import com.cheapquest.backend.domain.sections.SectionSnapshot;
import com.cheapquest.backend.dto.firebase.OfferDto;
import com.cheapquest.backend.dto.firebase.sections.SectionItemDto;
import com.cheapquest.backend.dto.firebase.sections.SectionSnapshotDto;
import com.cheapquest.backend.exception.FirebaseUnavailableException;
import com.cheapquest.backend.mapper.SectionSnapshotMapper;
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
import com.google.cloud.firestore.WriteBatch;
import com.google.cloud.firestore.WriteResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FirestoreSectionStoreTest {

    private static final String SECTIONS_PATH = "sections";
    private static final LocalDate DAY = LocalDate.parse("2026-07-06");
    private static final Instant NOW = Instant.parse("2026-07-06T00:00:05Z");

    private static final Offer OFFER = new Offer(
            "1", "Steam", null,
            new BigDecimal("9.99"), new BigDecimal("29.99"),
            new BigDecimal("66.70"), null, null);
    private static final SectionItem ITEM = new SectionItem(
            "slug", "Title", OFFER, new BigDecimal("66.70"),
            Map.of("savingsPct", "66.70"), null);
    private static final SectionSnapshot SNAPSHOT = new SectionSnapshot(
            SectionName.MEJORES_PROMOS, DAY, NOW, 5, List.of(ITEM));
    private static final SectionSnapshotDto DTO = new SectionSnapshotDto(
            "mejores-promos", "2026-07-06", "2026-07-06T00:00:05Z", 5,
            List.of(new SectionItemDto(
                    "slug", "Title",
                    new OfferDto("1", "Steam", null,
                            new BigDecimal("9.99"), new BigDecimal("29.99"),
                            new BigDecimal("66.70"), null, null),
                    new BigDecimal("66.70"),
                    Map.of("savingsPct", "66.70"), null)));

    private static final SectionSnapshotMapper MAPPER = new SectionSnapshotMapper();

    private Firestore firestore;
    private WriteBatch batch;
    private FirestoreSectionStore store;

    @BeforeEach
    void setUp() {
        firestore = mock(Firestore.class);
        batch = mock(WriteBatch.class);
        when(firestore.batch()).thenReturn(batch);
        // Tight retry budget keeps the transient-retry test fast.
        store = new FirestoreSectionStore(firestore, SECTIONS_PATH, MAPPER, 2, 1L);
    }

    // -------- write ----------------------------------------------------------

    @Test
    void write_batches_history_and_latest_then_commits() {
        // The path-traversal test for a specific (date, slug)
        // pair is covered by the read tests. Here we just want
        // to assert that the write is a single batched commit
        // carrying the same payload twice (once to the history
        // doc, once to the latest mirror). Use a deep-stub
        // firestore on this test only so the path-traversal
        // works without manual step-by-step wiring.
        Firestore deepFirestore = mock(Firestore.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        when(deepFirestore.batch()).thenReturn(batch);
        FirestoreSectionStore writeStore = new FirestoreSectionStore(
                deepFirestore, SECTIONS_PATH, MAPPER, 2, 1L);

        SettableApiFuture<List<WriteResult>> future = SettableApiFuture.create();
        future.set(List.of());
        when(batch.commit()).thenReturn(future);

        writeStore.write(SNAPSHOT);

        ArgumentCaptor<Object> dataCaptor = ArgumentCaptor.forClass(Object.class);
        verify(batch, times(2)).set(any(DocumentReference.class), dataCaptor.capture());
        verify(batch, times(1)).commit();

        // The same DTO is sent to both destinations; if the mapper
        // ever changes per-call, this assertion will catch it.
        List<Object> datas = dataCaptor.getAllValues();
        assertThat(datas).hasSize(2);
        assertThat(datas.get(0)).isInstanceOf(SectionSnapshotDto.class);
        assertThat(datas.get(1)).isInstanceOf(SectionSnapshotDto.class);
        assertThat(datas.get(0)).isEqualTo(datas.get(1));
    }

    // -------- read (history) -------------------------------------------------

    @Test
    void read_returns_parsed_snapshot_when_doc_exists() {
        DocumentReference docRef = stubItemPath("2026-07-06", "mejores-promos");
        DocumentSnapshot snap = mock(DocumentSnapshot.class);
        when(snap.exists()).thenReturn(true);
        when(snap.toObject(SectionSnapshotDto.class)).thenReturn(DTO);
        when(docRef.get()).thenReturn(snapFuture(snap));

        SectionSnapshot back = store.read(SectionName.MEJORES_PROMOS, DAY).orElseThrow();

        assertThat(back.name()).isEqualTo(SectionName.MEJORES_PROMOS);
        assertThat(back.date()).isEqualTo(DAY);
        assertThat(back.computedAt()).isEqualTo(NOW);
        assertThat(back.totalCandidates()).isEqualTo(5);
        assertThat(back.items()).hasSize(1);
    }

    @Test
    void read_returns_empty_when_doc_does_not_exist() {
        DocumentReference docRef = stubItemPath("2026-07-06", "mejores-promos");
        DocumentSnapshot snap = mock(DocumentSnapshot.class);
        when(snap.exists()).thenReturn(false);
        when(docRef.get()).thenReturn(snapFuture(snap));

        assertThat(store.read(SectionName.MEJORES_PROMOS, DAY)).isEmpty();
    }

    @Test
    void read_returns_empty_when_toObject_returns_null() {
        DocumentReference docRef = stubItemPath("2026-07-06", "mejores-promos");
        DocumentSnapshot snap = mock(DocumentSnapshot.class);
        when(snap.exists()).thenReturn(true);
        when(snap.toObject(SectionSnapshotDto.class)).thenReturn(null);
        when(docRef.get()).thenReturn(snapFuture(snap));

        assertThat(store.read(SectionName.MEJORES_PROMOS, DAY)).isEmpty();
    }

    // -------- readLatest -----------------------------------------------------

    @Test
    void readLatest_reads_from_latest_sentinel() {
        DocumentReference docRef = stubItemPath("latest", "mejores-promos");
        DocumentSnapshot snap = mock(DocumentSnapshot.class);
        when(snap.exists()).thenReturn(true);
        when(snap.toObject(SectionSnapshotDto.class)).thenReturn(DTO);
        when(docRef.get()).thenReturn(snapFuture(snap));

        SectionSnapshot back = store.readLatest(SectionName.MEJORES_PROMOS).orElseThrow();
        assertThat(back.name()).isEqualTo(SectionName.MEJORES_PROMOS);
    }

    // -------- readAllLatest --------------------------------------------------

    @Test
    void readAllLatest_returns_map_of_parsed_snapshots() {
        CollectionReference items = mock(CollectionReference.class);
        CollectionReference latestDoc = mock(CollectionReference.class);
        DocumentReference latestDocRef = mock(DocumentReference.class);
        Query ordered = mock(Query.class);
        QueryDocumentSnapshot doc1 = mockQueryDoc("mejores-promos", DTO);
        QueryDocumentSnapshot doc2 = mockQueryDoc("populares",
                new SectionSnapshotDto(
                        "populares", "2026-07-06", "2026-07-06T00:00:05Z", 3, List.of()));
        QuerySnapshot qs = mock(QuerySnapshot.class);

        when(firestore.collection(SECTIONS_PATH)).thenReturn(latestDoc);
        when(latestDoc.document(FirestoreSectionStore.LATEST_SENTINEL)).thenReturn(latestDocRef);
        when(latestDocRef.collection(FirestoreSectionStore.ITEMS_SUBCOLLECTION)).thenReturn(items);
        when(items.orderBy(FieldPath.documentId())).thenReturn(ordered);

        SettableApiFuture<QuerySnapshot> future = SettableApiFuture.create();
        future.set(qs);
        when(ordered.get()).thenReturn(future);
        when(qs.getDocuments()).thenReturn(List.of(doc1, doc2));

        Map<SectionName, SectionSnapshot> out = store.readAllLatest();
        assertThat(out.keySet()).containsExactlyInAnyOrder(
                SectionName.MEJORES_PROMOS, SectionName.POPULARES);
        assertThat(out.get(SectionName.MEJORES_PROMOS).totalCandidates()).isEqualTo(5);
        assertThat(out.get(SectionName.POPULARES).totalCandidates()).isEqualTo(3);
    }

    @Test
    void readAllLatest_skips_documents_that_fail_to_deserialise() {
        CollectionReference items = mock(CollectionReference.class);
        CollectionReference latestDoc = mock(CollectionReference.class);
        DocumentReference latestDocRef = mock(DocumentReference.class);
        Query ordered = mock(Query.class);
        QueryDocumentSnapshot ok = mockQueryDoc("mejores-promos", DTO);
        QueryDocumentSnapshot bad = mockQueryDoc("populares", null);
        QuerySnapshot qs = mock(QuerySnapshot.class);

        when(firestore.collection(SECTIONS_PATH)).thenReturn(latestDoc);
        when(latestDoc.document(FirestoreSectionStore.LATEST_SENTINEL)).thenReturn(latestDocRef);
        when(latestDocRef.collection(FirestoreSectionStore.ITEMS_SUBCOLLECTION)).thenReturn(items);
        when(items.orderBy(FieldPath.documentId())).thenReturn(ordered);

        SettableApiFuture<QuerySnapshot> future = SettableApiFuture.create();
        future.set(qs);
        when(ordered.get()).thenReturn(future);
        when(qs.getDocuments()).thenReturn(List.of(ok, bad));

        Map<SectionName, SectionSnapshot> out = store.readAllLatest();
        assertThat(out.keySet()).containsExactly(SectionName.MEJORES_PROMOS);
    }

    // -------- retry ---------------------------------------------------------

    @Test
    void read_retries_on_transient_failure_then_succeeds() {
        DocumentReference docRef = stubItemPath("2026-07-06", "mejores-promos");
        DocumentSnapshot snap = mock(DocumentSnapshot.class);
        when(snap.exists()).thenReturn(true);
        when(snap.toObject(SectionSnapshotDto.class)).thenReturn(DTO);

        SettableApiFuture<DocumentSnapshot> failing = SettableApiFuture.create();
        failing.setException(transientError());
        SettableApiFuture<DocumentSnapshot> ok = snapFuture(snap);
        when(docRef.get()).thenReturn(failing, ok);

        SectionSnapshot back = store.read(SectionName.MEJORES_PROMOS, DAY).orElseThrow();
        assertThat(back.name()).isEqualTo(SectionName.MEJORES_PROMOS);
        verify(docRef, times(2)).get();
    }

    @Test
    void read_throws_after_retry_budget_exhausted() {
        DocumentReference docRef = stubItemPath("2026-07-06", "mejores-promos");

        SettableApiFuture<DocumentSnapshot> failing = SettableApiFuture.create();
        failing.setException(transientError());
        when(docRef.get()).thenReturn(failing);

        assertThatThrownBy(() -> store.read(SectionName.MEJORES_PROMOS, DAY))
                .isInstanceOf(FirebaseUnavailableException.class)
                .hasMessageContaining("reading-section");
        // maxRetries=2 => 1 initial + 2 retries = 3 attempts
        verify(docRef, times(3)).get();
    }

    @Test
    void read_propagates_permanent_failure_immediately() {
        DocumentReference docRef = stubItemPath("2026-07-06", "mejores-promos");

        SettableApiFuture<DocumentSnapshot> failing = SettableApiFuture.create();
        failing.setException(FirestoreException.forServerRejection(
                io.grpc.Status.PERMISSION_DENIED, "denied"));
        when(docRef.get()).thenReturn(failing);

        assertThatThrownBy(() -> store.read(SectionName.MEJORES_PROMOS, DAY))
                .isInstanceOf(FirebaseUnavailableException.class)
                .hasMessageContaining("reading-section");
        verify(docRef, times(1)).get();
    }

    // -------- helpers --------------------------------------------------------

    /**
     * Stubs the full chain
     * {@code firestore.collection(sectionsPath).document(dateKey)
     * .collection(items).document(slug)} and returns the leaf
     * {@link DocumentReference} for further stubs (typically
     * {@code .get()}) or verification.
     */
    private DocumentReference stubItemPath(String dateKey, String slug) {
        CollectionReference sectionsCol = mock(CollectionReference.class);
        DocumentReference dateDoc = mock(DocumentReference.class);
        CollectionReference itemsCol = mock(CollectionReference.class);
        DocumentReference itemDoc = mock(DocumentReference.class);

        when(firestore.collection(SECTIONS_PATH)).thenReturn(sectionsCol);
        when(sectionsCol.document(dateKey)).thenReturn(dateDoc);
        when(dateDoc.collection(FirestoreSectionStore.ITEMS_SUBCOLLECTION)).thenReturn(itemsCol);
        when(itemsCol.document(slug)).thenReturn(itemDoc);
        return itemDoc;
    }

    private static SettableApiFuture<DocumentSnapshot> snapFuture(DocumentSnapshot snap) {
        SettableApiFuture<DocumentSnapshot> f = SettableApiFuture.create();
        f.set(snap);
        return f;
    }

    private static FirestoreException transientError() {
        return FirestoreException.forServerRejection(io.grpc.Status.UNAVAILABLE, "transient");
    }

    private static QueryDocumentSnapshot mockQueryDoc(String id, SectionSnapshotDto dto) {
        QueryDocumentSnapshot doc = mock(QueryDocumentSnapshot.class);
        org.mockito.Mockito.lenient().when(doc.getId()).thenReturn(id);
        org.mockito.Mockito.lenient().when(doc.toObject(SectionSnapshotDto.class)).thenReturn(dto);
        return doc;
    }
}
