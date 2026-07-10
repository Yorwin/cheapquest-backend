package com.cheapquest.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cheapquest.backend.dao.GameDao;
import com.cheapquest.backend.dao.HydrationQueueDao;
import com.cheapquest.backend.dto.firebase.GameDocumentDto;
import com.cheapquest.backend.mapper.FirebaseMapper;
import com.cheapquest.backend.service.GameIngestService.IngestAction;
import com.cheapquest.backend.service.GameIngestService.IngestFailure;
import com.cheapquest.backend.service.GameIngestService.IngestItem;
import com.cheapquest.backend.service.GameIngestService.IngestOutcome;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GameIngestServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-30T10:00:00Z"), ZoneOffset.UTC);

    private GameDao gameDao;
    private HydrationQueueDao hydrationQueueDao;
    private FirebaseMapper firebaseMapper;
    private GameIngestService service;

    @BeforeEach
    void setUp() {
        gameDao = mock(GameDao.class);
        hydrationQueueDao = mock(HydrationQueueDao.class);
        firebaseMapper = mock(FirebaseMapper.class);
        service = new GameIngestService(gameDao, hydrationQueueDao, firebaseMapper, CLOCK);
        when(firebaseMapper.toBootstrapDocument(anyString(), anyString()))
                .thenReturn(mock(GameDocumentDto.class));
    }

    @Test
    void ingest_createsAllDocsAndEnqueuesAll() {
        when(gameDao.createIfNotExists(anyString(), any())).thenReturn(true);

        IngestOutcome out = service.ingest(List.of("Portal", "Half-Life 2", "Stardew Valley"), null);

        assertThat(out.accepted()).extracting(IngestItem::slug)
                .containsExactly("portal", "half-life-2", "stardew-valley");
        assertThat(out.accepted()).extracting(IngestItem::action)
                .containsOnly(IngestAction.CREATED);
        assertThat(out.failed()).isEmpty();
        verify(gameDao, times(3)).createIfNotExists(anyString(), any());
        verify(hydrationQueueDao, times(3)).enqueue(anyString());
    }

    @Test
    void ingest_marksAlreadyExistedWhenDocPresent() {
        when(gameDao.createIfNotExists(eq("portal"), any())).thenReturn(true);
        when(gameDao.createIfNotExists(eq("hades"), any())).thenReturn(false);

        IngestOutcome out = service.ingest(List.of("Portal", "Hades"), null);

        assertThat(out.accepted()).hasSize(2);
        assertThat(out.accepted().get(0).action()).isEqualTo(IngestAction.CREATED);
        assertThat(out.accepted().get(1).action()).isEqualTo(IngestAction.ALREADY_EXISTED);
        // Even already-existing docs are enqueued: a stale or
        // failed doc gets a second chance on the next refresh.
        verify(hydrationQueueDao, times(2)).enqueue(anyString());
    }

    @Test
    void ingest_dedupesWithinBatchPreservingFirstOccurrenceOrder() {
        when(gameDao.createIfNotExists(anyString(), any())).thenReturn(true);

        IngestOutcome out = service.ingest(
                List.of("Portal", "Hades", "Portal", "Stardew Valley", "Hades"), null);

        assertThat(out.accepted()).extracting(IngestItem::name)
                .containsExactly("Portal", "Hades", "Stardew Valley");
        verify(gameDao, times(3)).createIfNotExists(anyString(), any());
        verify(hydrationQueueDao, times(3)).enqueue(anyString());
    }

    @Test
    void ingest_normalisesWhitespaceAndTrims() {
        when(gameDao.createIfNotExists(anyString(), any())).thenReturn(true);

        IngestOutcome out = service.ingest(
                List.of("  Portal  ", "Half\tLife 2", "  Stardew\nValley  "), null);

        // Whitespace is collapsed before slug derivation, so
        // "Half\tLife 2" -> "half-life-2" and "Stardew\nValley" -> "stardew-valley".
        assertThat(out.accepted()).extracting(IngestItem::slug)
                .containsExactly("portal", "half-life-2", "stardew-valley");
        assertThat(out.failed()).isEmpty();
    }

    @Test
    void ingest_capturesEmptyNameAsFailureWithoutAbortingBatch() {
        when(gameDao.createIfNotExists(anyString(), any())).thenReturn(true);

        // Arrays.asList instead of List.of because the input
        // contains a null that List.of rejects.
        IngestOutcome out = service.ingest(
                new ArrayList<>(java.util.Arrays.asList("", "  ", "Portal", null)), null);

        assertThat(out.failed()).extracting(IngestFailure::name)
                .containsExactly("", "  ", null);
        assertThat(out.failed()).allMatch(f -> "empty name".equals(f.error()));
        assertThat(out.accepted()).extracting(IngestItem::name)
                .containsExactly("Portal");
    }

    @Test
    void ingest_capturesSlugDerivationFailure() {
        // "!!!" normalises to "!!!" which strips to "" in toSlug.
        // The mapper mock bypasses toSlug, so we exercise the
        // real mapper to verify the integration.
        GameIngestService realService = new GameIngestService(
                gameDao, hydrationQueueDao, new FirebaseMapper(CLOCK), CLOCK);
        when(gameDao.createIfNotExists(anyString(), any())).thenReturn(true);

        IngestOutcome out = realService.ingest(List.of("!!!", "Portal"), null);

        assertThat(out.failed()).hasSize(1);
        assertThat(out.failed().get(0).name()).isEqualTo("!!!");
        assertThat(out.failed().get(0).error()).contains("empty slug");
        assertThat(out.accepted()).extracting(IngestItem::name)
                .containsExactly("Portal");
        // No DAO write for the name that failed slug derivation.
        verify(gameDao, never()).createIfNotExists(eq("!!!"), any());
    }

    @Test
    void ingest_capturesDaoFailureAndContinues() {
        when(gameDao.createIfNotExists(eq("portal"), any())).thenReturn(true);
        when(gameDao.createIfNotExists(eq("hades"), any()))
                .thenThrow(new RuntimeException("firestore blip"));
        when(gameDao.createIfNotExists(eq("stardew-valley"), any())).thenReturn(true);

        IngestOutcome out = service.ingest(
                List.of("Portal", "Hades", "Stardew Valley"), null);

        assertThat(out.accepted()).extracting(IngestItem::name)
                .containsExactly("Portal", "Stardew Valley");
        assertThat(out.failed()).hasSize(1);
        assertThat(out.failed().get(0).name()).isEqualTo("Hades");
        assertThat(out.failed().get(0).error()).contains("firestore blip");
    }

    @Test
    void ingest_acceptsExplicitEnglishLanguage() {
        when(gameDao.createIfNotExists(anyString(), any())).thenReturn(true);

        IngestOutcome out = service.ingest(List.of("Portal"), "en");

        assertThat(out.accepted()).hasSize(1);
    }

    @Test
    void ingest_rejectsNonEnglishLanguage() {
        assertThatThrownBy(() -> service.ingest(List.of("Portal"), "es"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("language");
        assertThatThrownBy(() -> service.ingest(List.of("Portal"), "fr"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.ingest(List.of("Portal"), "de"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("de");
        verify(gameDao, never()).createIfNotExists(anyString(), any());
    }

    @Test
    void ingest_rejectsNullOrEmptyList() {
        assertThatThrownBy(() -> service.ingest(null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty");
        assertThatThrownBy(() -> service.ingest(List.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty");
        verify(gameDao, never()).createIfNotExists(anyString(), any());
    }

    @Test
    void ingest_rejectsBatchAboveMaxSize() {
        List<String> oversized = IntStream.range(0, GameIngestService.MAX_BATCH_SIZE + 1)
                .mapToObj(i -> "Game " + i)
                .toList();

        assertThatThrownBy(() -> service.ingest(oversized, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds maximum");
        verify(gameDao, never()).createIfNotExists(anyString(), any());
    }

    @Test
    void ingest_acceptsBatchAtMaxSize() {
        List<String> atMax = IntStream.range(0, GameIngestService.MAX_BATCH_SIZE)
                .mapToObj(i -> "Game " + i)
                .toList();
        when(gameDao.createIfNotExists(anyString(), any())).thenReturn(true);

        IngestOutcome out = service.ingest(atMax, null);

        assertThat(out.accepted()).hasSize(GameIngestService.MAX_BATCH_SIZE);
        assertThat(out.failed()).isEmpty();
        verify(gameDao, times(GameIngestService.MAX_BATCH_SIZE))
                .createIfNotExists(anyString(), any());
    }

    @Test
    void ingest_resubmitOnPartialFailureIsSafe() {
        // First call: "Portal" creates OK, "Hades" fails.
        when(gameDao.createIfNotExists(eq("portal"), any())).thenReturn(true);
        when(gameDao.createIfNotExists(eq("hades"), any()))
                .thenThrow(new RuntimeException("blip"))
                .thenReturn(false); // second call: doc already exists from a previous run

        GameIngestService.IngestOutcome first =
                service.ingest(List.of("Portal", "Hades"), null);
        assertThat(first.accepted()).extracting(IngestItem::name).containsExactly("Portal");
        assertThat(first.failed()).extracting(IngestFailure::name).containsExactly("Hades");

        GameIngestService.IngestOutcome second =
                service.ingest(List.of("Portal", "Hades"), null);
        // Second call: both names come back as accepted (idempotent).
        // Portal reports ALREADY_EXISTED (the doc from the first call),
        // Hades reports ALREADY_EXISTED too (the doc was eventually
        // created on a separate run between the two calls). What matters
        // is that the second call does not throw and returns a complete
        // result for both names.
        assertThat(second.accepted()).extracting(IngestItem::name)
                .containsExactlyInAnyOrder("Portal", "Hades");
        assertThat(second.failed()).isEmpty();
    }

    @Test
    void ingest_preservesOriginalNameInFailureEvenIfNull() {
        when(gameDao.createIfNotExists(anyString(), any())).thenReturn(true);

        IngestOutcome out = service.ingest(
                new ArrayList<>(List.of("", "Portal")), null);

        assertThat(out.failed()).hasSize(1);
        assertThat(out.failed().get(0).name()).isEmpty();
        assertThat(out.failed().get(0).error()).isEqualTo("empty name");
        // The accepted entry holds the normalised (trimmed) form.
        assertThat(out.accepted().get(0).name()).isEqualTo("Portal");
        // And the batch is processed: we still see Portal in accepted.
        assertThat(out.accepted()).hasSize(1);
    }

    @Test
    void ingest_handlesEmptyFailureList() {
        when(gameDao.createIfNotExists(anyString(), any())).thenReturn(true);

        IngestOutcome out = service.ingest(List.of("Portal"), null);

        assertThat(out.failed()).isEmpty();
        assertThat(out.failed()).isEqualTo(Collections.emptyList());
    }

    @Test
    void ingest_passesCorrectSlugToGameDao() {
        ArgumentCaptor<String> slugCaptor = ArgumentCaptor.forClass(String.class);
        when(gameDao.createIfNotExists(slugCaptor.capture(), any())).thenReturn(true);

        service.ingest(List.of("Half-Life 2"), null);

        assertThat(slugCaptor.getValue()).isEqualTo("half-life-2");
    }

    @Test
    void ingest_preservesNullInInputList() {
        when(gameDao.createIfNotExists(anyString(), any())).thenReturn(true);

        IngestOutcome out = service.ingest(
                new ArrayList<>(java.util.Arrays.asList("Portal", null, "  ", "Hades")), null);

        assertThat(out.accepted()).extracting(IngestItem::name)
                .containsExactly("Portal", "Hades");
        assertThat(out.failed()).extracting(IngestFailure::name)
                .containsExactly(null, "  ");
    }
}
