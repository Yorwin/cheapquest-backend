package com.cheapquest.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cheapquest.backend.client.DeepLClient;
import com.cheapquest.backend.dao.GameDao;
import com.cheapquest.backend.dao.TranslationQueueDao;
import com.cheapquest.backend.domain.rawg.RawgGenre;
import com.cheapquest.backend.domain.rawg.RawgTag;
import com.cheapquest.backend.dto.firebase.GameDocumentDto;
import com.cheapquest.backend.dto.firebase.LocaleBlock;
import com.cheapquest.backend.dto.firebase.RawgBlock;
import com.cheapquest.backend.dto.firebase.RawgDocumentDto;
import com.cheapquest.backend.dto.firebase.TranslationFailedDoc;
import com.cheapquest.backend.dto.firebase.TranslationPendingDoc;
import com.cheapquest.backend.exception.TranslationFailedException;
import com.cheapquest.backend.mapper.FirebaseMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TranslationServiceTest {

    private static final Instant T = Instant.parse("2026-06-30T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-06-30T10:05:00Z");

    private GameDao gameDao;
    private TranslationQueueDao translationQueueDao;
    private DeepLClient deepLClient;
    private FirebaseMapper firebaseMapper;
    private Clock clock;
    private TranslationService service;

    @BeforeEach
    void setUp() {
        gameDao = mock(GameDao.class);
        translationQueueDao = mock(TranslationQueueDao.class);
        deepLClient = mock(DeepLClient.class);
        firebaseMapper = mock(FirebaseMapper.class);
        clock = Clock.fixed(T2, ZoneOffset.UTC);
        service = new TranslationService(gameDao, translationQueueDao, deepLClient,
                firebaseMapper, clock, 3, List.of("es", "fr"));
    }

    @Test
    void markStaleAndEnqueue_callsDaoForEachTargetLocale() {
        service.markStaleAndEnqueue("portal", T);

        verify(translationQueueDao).enqueue("portal", "es", T);
        verify(translationQueueDao).enqueue("portal", "fr", T);
    }

    @Test
    void markStaleAndEnqueue_isNoOpForNullInputs() {
        service.markStaleAndEnqueue(null, T);
        service.markStaleAndEnqueue("portal", null);
        verify(translationQueueDao, never()).enqueue(anyString(), anyString(), any());
    }

    @Test
    void translateAll_processesAllPendingEntries() {
        TranslationPendingDoc p1 = new TranslationPendingDoc(
                "portal", "es", T, 1, T, null);
        TranslationPendingDoc p2 = new TranslationPendingDoc(
                "hl2", "fr", T, 1, T, null);
        when(translationQueueDao.readPending()).thenReturn(List.of(p1, p2));
        when(gameDao.read("portal")).thenReturn(Optional.of(sampleGame("portal")));
        when(gameDao.read("hl2")).thenReturn(Optional.of(sampleGame("hl2")));
        when(deepLClient.translate(anyList(), eq("es")))
                .thenReturn(List.of("<p>Hola</p>"));
        when(deepLClient.translate(anyList(), eq("fr")))
                .thenReturn(List.of("<p>Bonjour</p>"));

        int done = service.translateAll();

        assertThat(done).isEqualTo(2);
        verify(deepLClient, times(2)).translate(anyList(), anyString());
        verify(gameDao).writeLocaleTranslation(
                eq("portal"), eq("es"), anyString(), anyList(), eq(T), eq(T2));
        verify(translationQueueDao).removeFromPending("portal", "es");
        verify(translationQueueDao).removeFromPending("hl2", "fr");
    }

    @Test
    void translateOne_writesDescriptionAndTagsAndRemovesFromPending() {
        TranslationPendingDoc entry = new TranslationPendingDoc(
                "portal", "es", T, 1, T, null);
        when(gameDao.read("portal")).thenReturn(Optional.of(sampleGame("portal")));
        when(deepLClient.translate(anyList(), eq("es")))
                .thenReturn(List.of("<p>Hola mundo</p>", "Acción", "Aventura"));

        boolean ok = service.translateOne(entry);

        assertThat(ok).isTrue();
        ArgumentCaptor<String> descCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> tagsCaptor = ArgumentCaptor.forClass(List.class);
        verify(gameDao).writeLocaleTranslation(
                eq("portal"), eq("es"),
                descCaptor.capture(), tagsCaptor.capture(),
                eq(T), eq(T2));
        verify(translationQueueDao).removeFromPending("portal", "es");
        assertThat(descCaptor.getValue()).isEqualTo("<p>Hola mundo</p>");
        assertThat(tagsCaptor.getValue()).containsExactly("Acción", "Aventura");
    }

    @Test
    void translateOne_skipsNullsInInputs() {
        TranslationPendingDoc entry = new TranslationPendingDoc(
                "portal", "es", T, 1, T, null);
        when(gameDao.read("portal")).thenReturn(Optional.of(sampleGame("portal")));
        // null source description is skipped by DeepLClient; the
        // returned list still has 3 entries (1 description + 2 tags).
        when(deepLClient.translate(anyList(), eq("es")))
                .thenReturn(List.of("<p>Hola</p>", "Acción", "Aventura"));

        boolean ok = service.translateOne(entry);

        assertThat(ok).isTrue();
        verify(translationQueueDao).removeFromPending("portal", "es");
    }

    @Test
    void translateOne_handlesMissingGameDoc() {
        TranslationPendingDoc entry = new TranslationPendingDoc(
                "ghost", "es", T, 1, T, null);
        when(gameDao.read("ghost")).thenReturn(Optional.empty());

        boolean ok = service.translateOne(entry);

        assertThat(ok).isFalse();
        // attempt counter was bumped, not moved to failed (this is
        // attempt 1 of 3).
        verify(translationQueueDao).recordFailure(
                eq("ghost"), eq("es"), eq(2), eq(T2), eq("game document gone"));
        verify(translationQueueDao, never()).moveToFailed(any());
    }

    @Test
    void translateOne_handlesMissingRawgData() {
        TranslationPendingDoc entry = new TranslationPendingDoc(
                "no-rawg", "es", T, 1, T, null);
        GameDocumentDto doc = new GameDocumentDto(
                "no-rawg", "no-rawg", "en", true, T.toString(),
                com.cheapquest.backend.dto.firebase.CheapsharkBlock.empty(),
                RawgBlock.empty(),  // synced=false, fetchedAt=null, data=null
                Map.of("es", LocaleBlock.unsynced(),
                        "en", LocaleBlock.unsynced(),
                        "fr", LocaleBlock.unsynced()),
                null);
        when(gameDao.read("no-rawg")).thenReturn(Optional.of(doc));

        boolean ok = service.translateOne(entry);

        assertThat(ok).isFalse();
        verify(translationQueueDao).recordFailure(
                eq("no-rawg"), eq("es"), eq(2), eq(T2), eq("no rawg data"));
    }

    @Test
    void translateOne_movesToFailedAfterMaxAttempts() {
        // attempts=2 + 1 = 3 >= maxAttempts (3) -> move to failed
        TranslationPendingDoc entry = new TranslationPendingDoc(
                "stuck", "es", T, 2, T, "previous error");
        when(gameDao.read("stuck")).thenReturn(Optional.empty());

        boolean ok = service.translateOne(entry);

        assertThat(ok).isFalse();
        ArgumentCaptor<TranslationFailedDoc> captor =
                ArgumentCaptor.forClass(TranslationFailedDoc.class);
        verify(translationQueueDao).moveToFailed(captor.capture());
        assertThat(captor.getValue().slug()).isEqualTo("stuck");
        assertThat(captor.getValue().locale()).isEqualTo("es");
        assertThat(captor.getValue().attempts()).isEqualTo(3);
        assertThat(captor.getValue().lastError()).isEqualTo("game document gone");
        // No recordFailure call: the moveToFailed already includes
        // the bump in its DLQ entry.
        verify(translationQueueDao, never()).recordFailure(
                anyString(), anyString(), anyInt(), any(), anyString());
    }

    @Test
    void translateOne_wrapsDeepLExceptionAndBumpsAttempt() {
        TranslationPendingDoc entry = new TranslationPendingDoc(
                "portal", "es", T, 1, T, null);
        when(gameDao.read("portal")).thenReturn(Optional.of(sampleGame("portal")));
        when(deepLClient.translate(anyList(), eq("es")))
                .thenThrow(new TranslationFailedException("quota exhausted",
                        new RuntimeException("rate limit")));

        boolean ok = service.translateOne(entry);

        assertThat(ok).isFalse();
        verify(translationQueueDao).recordFailure(
                eq("portal"), eq("es"), eq(2), eq(T2), eq("quota exhausted"));
        verify(translationQueueDao, never()).moveToFailed(any());
    }

    @Test
    void translateOne_wrapsRuntimeException() {
        TranslationPendingDoc entry = new TranslationPendingDoc(
                "portal", "es", T, 1, T, null);
        when(gameDao.read("portal"))
                .thenThrow(new RuntimeException("Firestore blip"));

        boolean ok = service.translateOne(entry);

        assertThat(ok).isFalse();
        verify(translationQueueDao).recordFailure(
                eq("portal"), eq("es"), eq(2), eq(T2), eq("RuntimeException: Firestore blip"));
    }

    @Test
    void translateOne_preservesSourceFetchedAtOnSuccess() {
        // sourceFetchedAt is propagated to the LocaleBlock so
        // the next hydration can detect that the translation is
        // still current.
        TranslationPendingDoc entry = new TranslationPendingDoc(
                "portal", "es", T, 1, T, null);
        when(gameDao.read("portal")).thenReturn(Optional.of(sampleGame("portal")));
        when(deepLClient.translate(anyList(), eq("es")))
                .thenReturn(List.of("<p>Hola</p>", "Acción", "Aventura"));

        service.translateOne(entry);

        verify(gameDao).writeLocaleTranslation(
                eq("portal"), eq("es"), anyString(), anyList(),
                eq(T), any(Instant.class));
    }

    @Test
    void translateAll_returnsZeroForEmptyQueue() {
        when(translationQueueDao.readPending()).thenReturn(List.of());

        int done = service.translateAll();

        assertThat(done).isZero();
        verify(deepLClient, never()).translate(anyList(), anyString());
    }

    private static GameDocumentDto sampleGame(String slug) {
        RawgDocumentDto data = new RawgDocumentDto(
                slug, slug, slug, "2007-10-10",
                "<p>An action game.</p>", "An action game.",
                "https://example.com/header.jpg", null, null, null, null, null,
                0, 0, 0, 0,
                List.of(), List.of(),
                List.of(new RawgGenre(1, "Action", "action")),
                List.of(new RawgTag(1, "FPS", "fps", "eng")),
                List.of(), List.of(), List.of(), List.of(),
                List.of(),
                false, null, null, List.of(), 0, 0, 0, null, 0, 0, 0, 0, 0,
                null, List.of(), null, List.of(), List.of(),
                Map.of(), Map.of(), 0,
                T.toString());
        return new GameDocumentDto(
                slug, slug, "en", true, T.toString(),
                com.cheapquest.backend.dto.firebase.CheapsharkBlock.empty(),
                new RawgBlock(true, T.toString(), data),
                Map.of("es", LocaleBlock.unsynced(),
                        "en", LocaleBlock.unsynced(),
                        "fr", LocaleBlock.unsynced()),
                null);
    }
}
