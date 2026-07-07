package com.cheapquest.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cheapquest.backend.client.FirebaseClient;
import com.cheapquest.backend.domain.AggregatedGame;
import com.cheapquest.backend.domain.GameDeals;
import com.cheapquest.backend.domain.Offer;
import com.cheapquest.backend.domain.rawg.RawgDetails;
import com.cheapquest.backend.domain.validation.GameField;
import com.cheapquest.backend.domain.validation.ValidationReport;
import com.cheapquest.backend.domain.validation.ValidationStatus;
import com.cheapquest.backend.dto.HydrationReport;
import com.cheapquest.backend.dto.firebase.CheapsharkBlock;
import com.cheapquest.backend.dto.firebase.GameDocumentDto;
import com.cheapquest.backend.dto.firebase.HydrationPatch;
import com.cheapquest.backend.dto.firebase.LocaleBlock;
import com.cheapquest.backend.dto.firebase.PendingDoc;
import com.cheapquest.backend.dto.firebase.RawgBlock;
import com.cheapquest.backend.dto.firebase.ValidationReportDto;
import com.cheapquest.backend.exception.DocumentNotFoundException;
import com.cheapquest.backend.exception.FirebaseUnavailableException;
import com.cheapquest.backend.fixtures.GameDocumentDtoFixtures;
import com.cheapquest.backend.fixtures.RawgDetailsFixtures;
import com.cheapquest.backend.mapper.FirebaseMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Optional;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GameHydrationServiceTest {

    private static final Instant T = Instant.parse("2026-06-30T10:00:00Z");

    private FirebaseClient firebaseClient;
    private FirebaseMapper firebaseMapper;
    private GameLookup gameLookup;
    private GameMerger merger;
    private ValidationService validator;
    private RefreshPolicy refreshPolicy;
    private GameHydrationService service;

    @BeforeEach
    void setUp() {
        firebaseClient = mock(FirebaseClient.class);
        firebaseMapper = mock(FirebaseMapper.class);
        gameLookup = mock(GameLookup.class);
        merger = new GameMerger(Clock.fixed(T, ZoneOffset.UTC));
        validator = new ValidationService(Clock.fixed(T, ZoneOffset.UTC));
        refreshPolicy = mock(RefreshPolicy.class);
        service = new GameHydrationService(
                firebaseClient, firebaseMapper, gameLookup, merger, validator,
                refreshPolicy, Clock.fixed(T, ZoneOffset.UTC));
    }

    @Test
    void constructor_rejectsNullDependencies() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new GameHydrationService(null, firebaseMapper, gameLookup, merger, validator,
                                refreshPolicy, Clock.systemUTC()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void hydrateAll_writesPatchWhenBothSourcesSucceed() {
        GameDocumentDto doc = sampleDoc("portal", "Portal");
        stubPending(doc);
        when(refreshPolicy.decide(doc, false))
                .thenReturn(new RefreshPolicy.RefreshDecision(true, true));
        when(gameLookup.lookupByTitle(eq("Portal"), any()))
                .thenReturn(new GameLookup.GameLookupResult(sampleDeals(), sampleRawgAgg()));
        when(firebaseMapper.toHydrationPatch(any(), any(), any(Boolean.class), any(Boolean.class))).thenReturn(samplePatch());

        HydrationReport report = service.hydrateAll();

        assertThat(report.processed()).isEqualTo(1);
        assertThat(report.complete()).isEqualTo(1);
        assertThat(report.partial()).isZero();
        assertThat(report.empty()).isZero();
        assertThat(report.skipped()).isZero();
        assertThat(report.failed()).isZero();
        assertThat(report.dealsRefreshed()).isEqualTo(1);
        assertThat(report.rawgRefreshed()).isEqualTo(1);
        assertThat(report.failures()).isEmpty();
        verify(firebaseClient).update(eq("portal"), any(HydrationPatch.class));
    }

    @Test
    void hydrateAll_marksEnLocaleAsSyncedOnSuccess() {
        // After a successful hydration the english locale must be
        // marked as synced via a separate partial update; this keeps
        // locales.es and locales.fr (owned by the future translation
        // pipeline) untouched.
        GameDocumentDto doc = sampleDoc("portal", "Portal");
        stubPending(doc);
        when(refreshPolicy.decide(doc, false))
                .thenReturn(new RefreshPolicy.RefreshDecision(true, true));
        when(gameLookup.lookupByTitle(eq("Portal"), any()))
                .thenReturn(new GameLookup.GameLookupResult(sampleDeals(), sampleRawgAgg()));
        when(firebaseMapper.toHydrationPatch(any(), any(), any(Boolean.class), any(Boolean.class))).thenReturn(samplePatch());

        service.hydrateAll();

        org.mockito.Mockito.verify(firebaseClient).markLocaleSynced(
                eq("portal"), eq("en"), any(Instant.class));
    }

    @Test
    void hydrateAll_doesNotMarkEnLocaleOnFailure() {
        // When the hydration throws, the en locale must NOT be
        // marked synced: the english content is not actually
        // verified as fresh, so leaving the flag at its previous
        // value is the right call.
        GameDocumentDto doc = sampleDoc("portal", "Portal");
        stubPending(doc);
        when(refreshPolicy.decide(doc, false))
                .thenReturn(new RefreshPolicy.RefreshDecision(true, true));
        when(gameLookup.lookupByTitle(eq("Portal"), any()))
                .thenThrow(new RuntimeException("Firestore blip"));

        service.hydrateAll();

        org.mockito.Mockito.verify(firebaseClient, org.mockito.Mockito.never())
                .markLocaleSynced(anyString(), anyString(), any(Instant.class));
    }

    @Test
    void hydrateAll_countsPartialWhenSomeFieldsMissing() {
        GameDocumentDto doc = sampleDoc("portal", "Portal");
        stubPending(doc);
        when(refreshPolicy.decide(doc, false))
                .thenReturn(new RefreshPolicy.RefreshDecision(true, true));
        RawgDetails rawgNoTrailer = RawgDetailsFixtures.full("portal", "Portal")
                .trailerUrl(null).build();
        AggregatedGame rawgAgg = new AggregatedGame("Portal", "Portal", "portal",
                null, rawgNoTrailer, T);
        when(gameLookup.lookupByTitle(eq("Portal"), any()))
                .thenReturn(new GameLookup.GameLookupResult(sampleDeals(), rawgAgg));
        when(firebaseMapper.toHydrationPatch(any(), any(), any(Boolean.class), any(Boolean.class))).thenReturn(samplePatch());

        HydrationReport report = service.hydrateAll();

        assertThat(report.processed()).isEqualTo(1);
        assertThat(report.partial()).isEqualTo(1);
        assertThat(report.complete()).isZero();
        verify(firebaseClient).update(eq("portal"), any(HydrationPatch.class));
    }

    @Test
    void hydrateAll_doesNotWriteWhenValidationIsEmpty() {
        GameDocumentDto doc = sampleDoc("portal", "Portal");
        stubPending(doc);
        when(refreshPolicy.decide(doc, false))
                .thenReturn(new RefreshPolicy.RefreshDecision(true, true));
        when(gameLookup.lookupByTitle(eq("Portal"), any()))
                .thenReturn(GameLookup.GameLookupResult.empty());

        HydrationReport report = service.hydrateAll();

        assertThat(report.processed()).isEqualTo(1);
        assertThat(report.empty()).isEqualTo(1);
        assertThat(report.complete()).isZero();
        assertThat(report.partial()).isZero();
        assertThat(report.failed()).isZero();
        verify(firebaseClient, never()).update(anyString(), any(HydrationPatch.class));
    }

    @Test
    void hydrateAll_writesCompleteWhenOnlyCheapSharkRefreshedAndExistingIsEmpty() {
        // Doc has no existing validationReport (bootstrap case). A
        // CheapShark-only refresh on a successful lookup yields no
        // missing fields: the refreshed source has no deficiencies
        // and there is no previous report to carry forward. The
        // composed status is COMPLETE, not PARTIAL. Pre-fix this
        // test asserted partial=1 because the validator saw
        // merged.rawg=null and added 9 RAWG fields to missing.
        GameDocumentDto doc = sampleDoc("portal", "Portal");
        stubPending(doc);
        when(refreshPolicy.decide(doc, false))
                .thenReturn(new RefreshPolicy.RefreshDecision(true, false));
        when(gameLookup.lookupByTitle(eq("Portal"), eq(EnumSet.of(GameLookup.Source.CHEAPSHARK))))
                .thenReturn(new GameLookup.GameLookupResult(sampleDeals(), null));
        when(firebaseMapper.toHydrationPatch(any(), any(), any(Boolean.class), any(Boolean.class))).thenReturn(samplePatch());

        HydrationReport report = service.hydrateAll();

        assertThat(report.processed()).isEqualTo(1);
        assertThat(report.complete()).isEqualTo(1);
        assertThat(report.partial()).isZero();
        assertThat(report.dealsRefreshed()).isEqualTo(1);
        assertThat(report.rawgRefreshed()).isZero();
        verify(firebaseClient).update(eq("portal"), any(HydrationPatch.class));
    }

    @Test
    void hydrateAll_writesCompleteWhenOnlyRawgRefreshedAndExistingIsEmpty() {
        // Same shape as the CheapShark-only test but the other side.
        // A RAWG-only refresh with a successful lookup produces no
        // RAWG-side deficiencies; there is no previous report to
        // carry STORES forward from. Status is COMPLETE.
        GameDocumentDto doc = sampleDoc("portal", "Portal");
        stubPending(doc);
        when(refreshPolicy.decide(doc, false))
                .thenReturn(new RefreshPolicy.RefreshDecision(false, true));
        when(gameLookup.lookupByTitle(eq("Portal"), eq(EnumSet.of(GameLookup.Source.RAWG))))
                .thenReturn(new GameLookup.GameLookupResult(null, sampleRawgAgg()));
        when(firebaseMapper.toHydrationPatch(any(), any(), any(Boolean.class), any(Boolean.class))).thenReturn(samplePatch());

        HydrationReport report = service.hydrateAll();

        assertThat(report.processed()).isEqualTo(1);
        assertThat(report.complete()).isEqualTo(1);
        assertThat(report.partial()).isZero();
        assertThat(report.dealsRefreshed()).isZero();
        assertThat(report.rawgRefreshed()).isEqualTo(1);
        verify(firebaseClient).update(eq("portal"), any(HydrationPatch.class));
    }

    @Test
    void hydrateAll_skipsFreshDoc() {
        GameDocumentDto doc = sampleDoc("portal", "Portal");
        stubPending(doc);
        when(refreshPolicy.decide(doc, false))
                .thenReturn(new RefreshPolicy.RefreshDecision(false, false));

        HydrationReport report = service.hydrateAll();

        assertThat(report.processed()).isEqualTo(1);
        assertThat(report.skipped()).isEqualTo(1);
        assertThat(report.complete()).isZero();
        assertThat(report.partial()).isZero();
        assertThat(report.empty()).isZero();
        assertThat(report.failed()).isZero();
        verify(gameLookup, never()).lookupByTitle(anyString(), any());
        verify(firebaseClient, never()).update(anyString(), any(HydrationPatch.class));
    }

    @Test
    void hydrateAll_countsFailureWhenFirestoreUpdateThrows() {
        GameDocumentDto doc = sampleDoc("portal", "Portal");
        stubPending(doc);
        when(refreshPolicy.decide(doc, false))
                .thenReturn(new RefreshPolicy.RefreshDecision(true, true));
        when(gameLookup.lookupByTitle(eq("Portal"), any()))
                .thenReturn(new GameLookup.GameLookupResult(sampleDeals(), sampleRawgAgg()));
        when(firebaseMapper.toHydrationPatch(any(), any(), any(Boolean.class), any(Boolean.class))).thenReturn(samplePatch());
        org.mockito.Mockito.doThrow(new FirebaseUnavailableException("boom"))
                .when(firebaseClient).update(eq("portal"), any(HydrationPatch.class));

        HydrationReport report = service.hydrateAll();

        assertThat(report.processed()).isEqualTo(1);
        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.failures()).containsExactly("portal");
    }

    @Test
    void hydrateAll_countsDocumentNotFoundAsFailure() {
        // The doc was deleted between read and write. The hydration
        // service still counts the doc as failed (no surviving state
        // to update) and surfaces the slug in the failures list. The
        // exception type lets the operator distinguish this from a
        // genuine backend outage in the logs.
        GameDocumentDto doc = sampleDoc("portal", "Portal");
        stubPending(doc);
        when(refreshPolicy.decide(doc, false))
                .thenReturn(new RefreshPolicy.RefreshDecision(true, true));
        when(gameLookup.lookupByTitle(eq("Portal"), any()))
                .thenReturn(new GameLookup.GameLookupResult(sampleDeals(), sampleRawgAgg()));
        when(firebaseMapper.toHydrationPatch(any(), any(), any(Boolean.class), any(Boolean.class))).thenReturn(samplePatch());
        org.mockito.Mockito.doThrow(new DocumentNotFoundException("document missing: portal",
                        new FirebaseUnavailableException("failed updating portal", null)))
                .when(firebaseClient).update(eq("portal"), any(HydrationPatch.class));

        HydrationReport report = service.hydrateAll();

        assertThat(report.processed()).isEqualTo(1);
        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.failures()).containsExactly("portal");
    }

    @Test
    void hydrateAll_removesFromPendingOnSuccess() {
        GameDocumentDto doc = sampleDoc("portal", "Portal");
        stubPending(doc);
        when(refreshPolicy.decide(doc, false))
                .thenReturn(new RefreshPolicy.RefreshDecision(true, true));
        when(gameLookup.lookupByTitle(eq("Portal"), any()))
                .thenReturn(new GameLookup.GameLookupResult(sampleDeals(), sampleRawgAgg()));
        when(firebaseMapper.toHydrationPatch(any(), any(), any(Boolean.class), any(Boolean.class))).thenReturn(samplePatch());

        service.hydrateAll();

        org.mockito.Mockito.verify(firebaseClient).removeFromPending("portal");
    }

    @Test
    void hydrateAll_recordsPendingFailureOnTransientException() {
        // First failure: attempt count goes to 1, still under the
        // default maxAttempts=3, so the slug stays in pending (not
        // moved to failed).
        GameDocumentDto doc = sampleDoc("portal", "Portal");
        stubPending(doc);
        when(refreshPolicy.decide(doc, false))
                .thenReturn(new RefreshPolicy.RefreshDecision(true, true));
        when(gameLookup.lookupByTitle(eq("Portal"), any()))
                .thenThrow(new RuntimeException("Firestore blip"));

        HydrationReport report = service.hydrateAll();

        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.movedToFailed()).isZero();
        org.mockito.Mockito.verify(firebaseClient).recordPendingFailure(
                eq("portal"), eq(1), any(Instant.class), anyString());
        org.mockito.Mockito.verify(firebaseClient, org.mockito.Mockito.never())
                .moveToFailed(any());
    }

    @Test
    void hydrateAll_movesToFailedAfterMaxAttempts() {
        // Pending entry already has 2 attempts; this run is the
        // 3rd and last before the DLQ. The slug must be moved to
        // failed and the report must include it in movedToFailedList.
        GameDocumentDto doc = sampleDoc("portal", "Portal");
        com.cheapquest.backend.dto.firebase.PendingDoc entry =
                new com.cheapquest.backend.dto.firebase.PendingDoc(
                        "portal", 2, Instant.parse("2026-06-30T10:00:00Z"), "previous error");
        when(firebaseClient.readPending()).thenReturn(List.of(entry));
        when(firebaseClient.readOne("portal")).thenReturn(Optional.of(doc));
        org.mockito.Mockito.doNothing().when(firebaseClient).removeFromPending("portal");
        when(refreshPolicy.decide(doc, false))
                .thenReturn(new RefreshPolicy.RefreshDecision(true, true));
        when(gameLookup.lookupByTitle(eq("Portal"), any()))
                .thenThrow(new RuntimeException("still down"));

        HydrationReport report = service.hydrateAll();

        // The slug is moved to the DLQ after 3 strikes, so it
        // counts as movedToFailed (not as a regular "failed": a
        // per-doc exception is a failure until the DLQ triggers,
        // at which point the bookkeeping shifts to movedToFailed).
        assertThat(report.failed()).isZero();
        assertThat(report.movedToFailed()).isEqualTo(1);
        assertThat(report.movedToFailedList()).containsExactly("portal");
        org.mockito.Mockito.verify(firebaseClient).moveToFailed(any());
        // The slug is removed from pending by moveToFailed itself,
        // so removeFromPending must NOT be called separately.
        org.mockito.Mockito.verify(firebaseClient, org.mockito.Mockito.never())
                .removeFromPending(anyString());
    }

    @Test
    void hydrateAll_treatsEmptyOutcomeAsFailure() {
        // Both sources returning empty is the canonical "doc
        // no longer exists in the upstream APIs" case. It must
        // count toward the 3-strike rule (so a chronically-empty
        // slug eventually lands in the DLQ).
        GameDocumentDto doc = sampleDoc("portal", "Portal");
        stubPending(doc);
        when(refreshPolicy.decide(doc, false))
                .thenReturn(new RefreshPolicy.RefreshDecision(true, true));
        // The typed "empty" marker: both sources failed lookup,
        // validator returns EMPTY.
        when(gameLookup.lookupByTitle(eq("Portal"), any()))
                .thenReturn(GameLookup.GameLookupResult.empty());

        HydrationReport report = service.hydrateAll();

        // EMPTY outcome shows in the "empty" bucket (not "failed")
        // because the per-doc hydration call did not throw. The
        // failure signal is the pending-side bookkeeping: the
        // attempt counter is bumped, the slug stays in pending,
        // and after 3 strikes the slug moves to the failed DLQ.
        assertThat(report.empty()).isEqualTo(1);
        assertThat(report.failed()).isZero();
        org.mockito.Mockito.verify(firebaseClient).recordPendingFailure(
                eq("portal"), eq(1), any(Instant.class), anyString());
        org.mockito.Mockito.verify(firebaseClient, org.mockito.Mockito.never())
                .removeFromPending(anyString());
    }

    @Test
    void hydrateAll_handlesGameDocMissingFromFirestore() {
        // The pending entry points to a slug whose games/{slug}
        // doc no longer exists (operator deleted it directly).
        // The hydration service must treat this as a failure
        // and bump the attempt counter.
        com.cheapquest.backend.dto.firebase.PendingDoc entry =
                new com.cheapquest.backend.dto.firebase.PendingDoc("ghost", 0, null, null);
        when(firebaseClient.readPending()).thenReturn(List.of(entry));
        when(firebaseClient.readOne("ghost")).thenReturn(Optional.empty());

        HydrationReport report = service.hydrateAll();

        assertThat(report.processed()).isEqualTo(1);
        assertThat(report.empty()).isEqualTo(1);
        org.mockito.Mockito.verify(firebaseClient).recordPendingFailure(
                eq("ghost"), eq(1), any(Instant.class), anyString());
    }

    @Test
    void hydrateAll_processesMultiplePendingEntries() {
        // Each entry must be processed independently: successes
        // are removed from pending, failures are recorded against
        // their own attempt counter.
        GameDocumentDto doc1 = sampleDoc("portal", "Portal");
        GameDocumentDto doc2 = sampleDoc("hl2", "Half-Life 2");
        stubPending(doc1, doc2);
        when(refreshPolicy.decide(doc1, false))
                .thenReturn(new RefreshPolicy.RefreshDecision(true, true));
        when(refreshPolicy.decide(doc2, false))
                .thenReturn(new RefreshPolicy.RefreshDecision(true, true));
        when(gameLookup.lookupByTitle(eq("Portal"), any()))
                .thenReturn(new GameLookup.GameLookupResult(sampleDeals(), sampleRawgAgg()));
        when(gameLookup.lookupByTitle(eq("Half-Life 2"), any()))
                .thenThrow(new RuntimeException("network blip"));
        when(firebaseMapper.toHydrationPatch(any(), any(), any(Boolean.class), any(Boolean.class))).thenReturn(samplePatch());

        HydrationReport report = service.hydrateAll();

        assertThat(report.processed()).isEqualTo(2);
        assertThat(report.failed()).isEqualTo(1);
        org.mockito.Mockito.verify(firebaseClient).removeFromPending("portal");
        org.mockito.Mockito.verify(firebaseClient).recordPendingFailure(
                eq("hl2"), eq(1), any(Instant.class), anyString());
    }

    @Test
    void hydrateAll_processesMultipleDocs() {
        GameDocumentDto portal = sampleDoc("portal", "Portal");
        GameDocumentDto hl2 = sampleDoc("half-life-2", "Half-Life 2");
        GameDocumentDto stardew = sampleDoc("stardew-valley", "Stardew Valley");
        stubPending(portal, hl2, stardew);
        when(refreshPolicy.decide(portal, false))
                .thenReturn(new RefreshPolicy.RefreshDecision(true, true));
        when(refreshPolicy.decide(hl2, false))
                .thenReturn(new RefreshPolicy.RefreshDecision(false, true));
        when(refreshPolicy.decide(stardew, false))
                .thenReturn(new RefreshPolicy.RefreshDecision(true, true));
        when(gameLookup.lookupByTitle(eq("Portal"), any()))
                .thenReturn(new GameLookup.GameLookupResult(sampleDeals(), sampleRawgAgg()));
        when(gameLookup.lookupByTitle(eq("Half-Life 2"), any()))
                .thenReturn(new GameLookup.GameLookupResult(null, sampleRawgAgg()));
        when(gameLookup.lookupByTitle(eq("Stardew Valley"), any()))
                .thenReturn(new GameLookup.GameLookupResult(sampleDeals(), sampleRawgAgg()));
        when(firebaseMapper.toHydrationPatch(any(), any(), any(Boolean.class), any(Boolean.class))).thenReturn(samplePatch());

        HydrationReport report = service.hydrateAll();

        // portal and stardew: full refresh with full data, complete.
        // half-life-2: RAWG-only refresh on a doc with no existing
        // validationReport; fresh RAWG is full and there is nothing
        // to carry forward, so composed status is COMPLETE.
        // Pre-fix hl2 was counted as PARTIAL because the validator
        // saw merged.deals=null and marked STORES as missing, which
        // a RAWG-only refresh cannot evaluate.
        assertThat(report.processed()).isEqualTo(3);
        assertThat(report.partial()).isZero();
        assertThat(report.complete()).isEqualTo(3);
        assertThat(report.dealsRefreshed()).isEqualTo(2);
        assertThat(report.rawgRefreshed()).isEqualTo(3);
        verify(firebaseClient, times(3)).update(anyString(), any(HydrationPatch.class));
    }

    @Test
    void hydrateAll_skipsDocsWithMissingSlugOrTitle() {
        GameDocumentDto docNoSlug = new GameDocumentDto(
                "Title", null, "en", true, T.toString(),
                CheapsharkBlock.empty(),
                RawgBlock.empty(),
                Map.of("es", LocaleBlock.unsynced()), null);
        GameDocumentDto docNoTitle = new GameDocumentDto(
                null, "slug", "en", true, T.toString(),
                CheapsharkBlock.empty(),
                RawgBlock.empty(),
                Map.of("es", LocaleBlock.unsynced()), null);
        stubPending(docNoSlug, docNoTitle);

        HydrationReport report = service.hydrateAll();

        assertThat(report.empty()).isEqualTo(2);
        verify(gameLookup, never()).lookupByTitle(anyString(), any());
    }

    @Test
    void hydrateOne_returnsFalseWhenDocMissing() {
        when(firebaseClient.readOne("missing")).thenReturn(java.util.Optional.empty());

        assertThat(service.hydrateOne("missing")).isFalse();
    }

    @Test
    void hydrateOne_returnsFalseWhenBothSourcesFail() {
        when(firebaseClient.readOne("portal")).thenReturn(java.util.Optional.of(sampleDoc("portal", "Portal")));
        when(refreshPolicy.decide(any(), anyBoolean()))
                .thenReturn(new RefreshPolicy.RefreshDecision(true, true));
        when(gameLookup.lookupByTitle(eq("Portal"), any()))
                .thenReturn(GameLookup.GameLookupResult.empty());

        assertThat(service.hydrateOne("portal")).isFalse();
    }

    @Test
    void hydrateOne_returnsTrueAndWritesPatchOnSuccess() {
        when(firebaseClient.readOne("portal")).thenReturn(java.util.Optional.of(sampleDoc("portal", "Portal")));
        when(refreshPolicy.decide(any(), anyBoolean()))
                .thenReturn(new RefreshPolicy.RefreshDecision(true, true));
        when(gameLookup.lookupByTitle(eq("Portal"), any()))
                .thenReturn(new GameLookup.GameLookupResult(sampleDeals(), sampleRawgAgg()));
        when(firebaseMapper.toHydrationPatch(any(), any(), any(Boolean.class), any(Boolean.class))).thenReturn(samplePatch());

        assertThat(service.hydrateOne("portal")).isTrue();
        verify(firebaseClient).update(eq("portal"), any(HydrationPatch.class));
    }

    @Test
    void hydrateAll_passesCorrectTitleAndSourcesToLookup() {
        GameDocumentDto doc = sampleDoc("portal", "Portal");
        stubPending(doc);
        when(refreshPolicy.decide(doc, false))
                .thenReturn(new RefreshPolicy.RefreshDecision(true, false));
        when(gameLookup.lookupByTitle(eq("Portal"), eq(EnumSet.of(GameLookup.Source.CHEAPSHARK))))
                .thenReturn(new GameLookup.GameLookupResult(sampleDeals(), null));
        when(firebaseMapper.toHydrationPatch(any(), any(), any(Boolean.class), any(Boolean.class))).thenReturn(samplePatch());

        service.hydrateAll();

        verify(gameLookup).lookupByTitle("Portal", EnumSet.of(GameLookup.Source.CHEAPSHARK));
    }

    @Test
    void composeReport_preservesLastFullFetchAtOnPartialRefresh() {
        Instant previousFull = T.minus(Duration.ofDays(1));
        GameDocumentDto doc = docWithReport(previousFull.toString(), null);
        stubPending(doc);
        when(refreshPolicy.decide(doc, false))
                .thenReturn(new RefreshPolicy.RefreshDecision(true, false));
        when(gameLookup.lookupByTitle(eq("Portal"), any()))
                .thenReturn(new GameLookup.GameLookupResult(sampleDeals(), null));
        ArgumentCaptor<ValidationReport> captor = ArgumentCaptor.forClass(ValidationReport.class);
        when(firebaseMapper.toHydrationPatch(any(), captor.capture(), any(Boolean.class), any(Boolean.class))).thenReturn(samplePatch());

        service.hydrateAll();

        ValidationReport composed = captor.getValue();
        assertThat(composed.lastFullFetchAt()).isEqualTo(previousFull);
        assertThat(composed.lastPartialFetchAt()).isNotNull();
    }

    @Test
    void composeReport_updatesLastFullFetchAtOnFullRefresh() {
        Instant previousPartial = T.minus(Duration.ofDays(30));
        GameDocumentDto doc = docWithReport(T.toString(), previousPartial.toString());
        stubPending(doc);
        when(refreshPolicy.decide(doc, false))
                .thenReturn(new RefreshPolicy.RefreshDecision(true, true));
        when(gameLookup.lookupByTitle(eq("Portal"), any()))
                .thenReturn(new GameLookup.GameLookupResult(sampleDeals(), sampleRawgAgg()));
        ArgumentCaptor<ValidationReport> captor = ArgumentCaptor.forClass(ValidationReport.class);
        when(firebaseMapper.toHydrationPatch(any(), captor.capture(), any(Boolean.class), any(Boolean.class))).thenReturn(samplePatch());

        service.hydrateAll();

        ValidationReport composed = captor.getValue();
        assertThat(composed.lastFullFetchAt()).isNotNull();
        assertThat(composed.lastPartialFetchAt()).isEqualTo(previousPartial);
    }

    @Test
    void composeReport_usesNowWhenExistingTimestampsMissing() {
        GameDocumentDto doc = docWithReport(null, null);
        stubPending(doc);
        when(refreshPolicy.decide(doc, false))
                .thenReturn(new RefreshPolicy.RefreshDecision(true, true));
        when(gameLookup.lookupByTitle(eq("Portal"), any()))
                .thenReturn(new GameLookup.GameLookupResult(sampleDeals(), sampleRawgAgg()));
        ArgumentCaptor<ValidationReport> captor = ArgumentCaptor.forClass(ValidationReport.class);
        when(firebaseMapper.toHydrationPatch(any(), captor.capture(), any(Boolean.class), any(Boolean.class))).thenReturn(samplePatch());

        service.hydrateAll();

        ValidationReport composed = captor.getValue();
        assertThat(composed.lastFullFetchAt()).isNotNull();
        assertThat(composed.lastPartialFetchAt()).isNull();
    }

    @Test
    void composeReport_keepsLastFullFetchAtNullOnPartialRefreshWhenExistingIsNull() {
        // Regression for the bootstrap-then-partial edge case:
        // a doc has no existing validationReport (fresh bootstrap)
        // and the cadence decides to refresh only CheapShark. The
        // composed report must keep lastFullFetchAt=null instead
        // of fabricating 'now', because no full refresh has
        // actually happened. The old code used parseOrNow which
        // fell back to now, producing a misleading timestamp
        // (e.g. lastFullFetchAt=2031-06-15T12:00:00Z in
        // /games/portal after the cadence commit).
        GameDocumentDto doc = docWithNullReport();
        stubPending(doc);
        when(refreshPolicy.decide(doc, false))
                .thenReturn(new RefreshPolicy.RefreshDecision(true, false));
        when(gameLookup.lookupByTitle(eq("Portal"), any()))
                .thenReturn(new GameLookup.GameLookupResult(sampleDeals(), null));
        ArgumentCaptor<ValidationReport> captor = ArgumentCaptor.forClass(ValidationReport.class);
        when(firebaseMapper.toHydrationPatch(any(), captor.capture(), any(Boolean.class), any(Boolean.class))).thenReturn(samplePatch());

        service.hydrateAll();

        ValidationReport composed = captor.getValue();
        assertThat(composed.lastFullFetchAt())
                .as("partial refresh on a bootstrap doc must NOT fabricate a lastFullFetchAt")
                .isNull();
        assertThat(composed.lastPartialFetchAt())
                .as("lastPartialFetchAt is set to 'now' on a partial refresh")
                .isNotNull();
    }

    @Test
    void composeReport_carriesForwardNonRefreshedSourceFieldsOnCheapSharkOnlyRefresh() {
        // Doc says half-life-2 has 9 RAWG fields missing from a
        // previous full refresh. We now only refresh CheapShark.
        // The carried-forward set must keep the 9 RAWG fields and
        // discard them from the fresh evaluation (because we did
        // not look at RAWG this run). Status: PARTIAL.
        GameDocumentDto doc = docWithMissingFields("portal", "Portal",
                List.of("DESCRIPTION", "HEADER_IMAGE", "TRAILER", "GENRES", "TAGS",
                        "SCREENSHOTS", "RELEASED", "DEVELOPER", "PUBLISHER"));
        stubPending(doc);
        when(refreshPolicy.decide(doc, false))
                .thenReturn(new RefreshPolicy.RefreshDecision(true, false));
        when(gameLookup.lookupByTitle(eq("Portal"), any()))
                .thenReturn(new GameLookup.GameLookupResult(sampleDeals(), null));
        ArgumentCaptor<ValidationReport> captor = ArgumentCaptor.forClass(ValidationReport.class);
        when(firebaseMapper.toHydrationPatch(any(), captor.capture(), any(Boolean.class), any(Boolean.class))).thenReturn(samplePatch());

        service.hydrateAll();

        ValidationReport composed = captor.getValue();
        assertThat(composed.missingFields())
                .containsExactlyInAnyOrder(
                        GameField.DESCRIPTION, GameField.HEADER_IMAGE, GameField.TRAILER,
                        GameField.GENRES, GameField.TAGS, GameField.SCREENSHOTS,
                        GameField.RELEASED, GameField.DEVELOPER, GameField.PUBLISHER);
        assertThat(composed.missingFields()).doesNotContain(GameField.STORES);
        assertThat(composed.status()).isEqualTo(ValidationStatus.PARTIAL);
    }

    @Test
    void composeReport_carriesForwardNonRefreshedSourceFieldsOnRawgOnlyRefresh() {
        // Doc says STORES is missing from a previous full refresh.
        // We now only refresh RAWG. STORES (CheapShark field) must
        // be carried forward; the fresh RAWG-only evaluation must
        // not add or remove RAWG fields in this case because the
        // fresh RAWG is full. Status: PARTIAL (because STORES is
        // still in the merged set).
        GameDocumentDto doc = docWithMissingFields("portal", "Portal", List.of("STORES"));
        stubPending(doc);
        when(refreshPolicy.decide(doc, false))
                .thenReturn(new RefreshPolicy.RefreshDecision(false, true));
        when(gameLookup.lookupByTitle(eq("Portal"), any()))
                .thenReturn(new GameLookup.GameLookupResult(null, sampleRawgAgg()));
        ArgumentCaptor<ValidationReport> captor = ArgumentCaptor.forClass(ValidationReport.class);
        when(firebaseMapper.toHydrationPatch(any(), captor.capture(), any(Boolean.class), any(Boolean.class))).thenReturn(samplePatch());

        service.hydrateAll();

        ValidationReport composed = captor.getValue();
        assertThat(composed.missingFields()).containsExactly(GameField.STORES);
        assertThat(composed.status()).isEqualTo(ValidationStatus.PARTIAL);
    }

    @Test
    void composeReport_usesFreshForRefreshedSourceEvenIfDifferentFromExisting() {
        // Doc had STORES in missing from a previous full refresh.
        // We now refresh CheapShark and the lookup succeeds with
        // offers present. The fresh evaluation says STORES is not
        // missing, so the merged set must NOT contain STORES. No
        // other fields are missing either. Status: COMPLETE.
        GameDocumentDto doc = docWithMissingFields("portal", "Portal", List.of("STORES"));
        stubPending(doc);
        when(refreshPolicy.decide(doc, false))
                .thenReturn(new RefreshPolicy.RefreshDecision(true, false));
        when(gameLookup.lookupByTitle(eq("Portal"), any()))
                .thenReturn(new GameLookup.GameLookupResult(sampleDeals(), null));
        ArgumentCaptor<ValidationReport> captor = ArgumentCaptor.forClass(ValidationReport.class);
        when(firebaseMapper.toHydrationPatch(any(), captor.capture(), any(Boolean.class), any(Boolean.class))).thenReturn(samplePatch());

        service.hydrateAll();

        ValidationReport composed = captor.getValue();
        assertThat(composed.missingFields()).doesNotContain(GameField.STORES);
        assertThat(composed.missingFields()).isEmpty();
        assertThat(composed.status()).isEqualTo(ValidationStatus.COMPLETE);
    }

    @Test
    void composeReport_addsRefreshedSourceFailureToMissing() {
        // Doc was COMPLETE. We refresh CheapShark and the lookup
        // returns a deals with no offers. The fresh evaluation
        // discovers STORES is missing; the merged set picks it up
        // because the refreshed source's fields are evaluated from
        // fresh, not carried. Status: PARTIAL.
        GameDocumentDto doc = docWithMissingFields("portal", "Portal", List.of());
        stubPending(doc);
        when(refreshPolicy.decide(doc, false))
                .thenReturn(new RefreshPolicy.RefreshDecision(true, false));
        when(gameLookup.lookupByTitle(eq("Portal"), any()))
                .thenReturn(new GameLookup.GameLookupResult(emptyDeals(), null));
        ArgumentCaptor<ValidationReport> captor = ArgumentCaptor.forClass(ValidationReport.class);
        when(firebaseMapper.toHydrationPatch(any(), captor.capture(), any(Boolean.class), any(Boolean.class))).thenReturn(samplePatch());

        service.hydrateAll();

        ValidationReport composed = captor.getValue();
        assertThat(composed.missingFields()).containsExactly(GameField.STORES);
        assertThat(composed.status()).isEqualTo(ValidationStatus.PARTIAL);
    }

    @Test
    void composeReport_usesFreshAsIsOnFullRefresh() {
        // Full refresh (both sources). The fresh evaluation is the
        // truth for the whole doc, no merge. The existing report
        // had [DESCRIPTION, HEADER_IMAGE] as missing from a previous
        // run; this run re-fetched both sources. The fresh RAWG
        // data is now complete except for TRAILER (null trailerUrl).
        // The composed report is [TRAILER] - the previous RAWG
        // deficiencies are resolved and only the fresh deficiency
        // remains. STORES was not missing in the existing report and
        // the fresh deals are full, so STORES does not appear.
        RawgDetails rawgNoTrailer = RawgDetailsFixtures.full("portal", "Portal")
                .trailerUrl(null).build();
        AggregatedGame rawgAgg = new AggregatedGame("Portal", "Portal", "portal",
                null, rawgNoTrailer, T);
        GameDocumentDto doc = docWithMissingFields("portal", "Portal",
                List.of("DESCRIPTION", "HEADER_IMAGE"));
        stubPending(doc);
        when(refreshPolicy.decide(doc, false))
                .thenReturn(new RefreshPolicy.RefreshDecision(true, true));
        when(gameLookup.lookupByTitle(eq("Portal"), any()))
                .thenReturn(new GameLookup.GameLookupResult(sampleDeals(), rawgAgg));
        ArgumentCaptor<ValidationReport> captor = ArgumentCaptor.forClass(ValidationReport.class);
        when(firebaseMapper.toHydrationPatch(any(), captor.capture(), any(Boolean.class), any(Boolean.class))).thenReturn(samplePatch());

        service.hydrateAll();

        ValidationReport composed = captor.getValue();
        assertThat(composed.missingFields()).containsExactly(GameField.TRAILER);
        assertThat(composed.status()).isEqualTo(ValidationStatus.PARTIAL);
    }

    @Test
    void composeReport_preservesNonRefreshedMissingFieldAcrossFullRefresh() {
        // Regression for the half-life-2 scenario: the existing
        // report has 9 RAWG fields missing. A full refresh (both
        // sources) yields fresh data that is COMPLETE for both.
        // The composed report must reflect the fresh truth: empty
        // missingFields, status COMPLETE, lastFullFetchAt updated,
        // and STORES never appears because we re-fetched it too.
        GameDocumentDto doc = docWithMissingFields("portal", "Portal",
                List.of("DESCRIPTION", "HEADER_IMAGE", "TRAILER", "GENRES", "TAGS",
                        "SCREENSHOTS", "RELEASED", "DEVELOPER", "PUBLISHER"));
        stubPending(doc);
        when(refreshPolicy.decide(doc, false))
                .thenReturn(new RefreshPolicy.RefreshDecision(true, true));
        when(gameLookup.lookupByTitle(eq("Portal"), any()))
                .thenReturn(new GameLookup.GameLookupResult(sampleDeals(), sampleRawgAgg()));
        ArgumentCaptor<ValidationReport> captor = ArgumentCaptor.forClass(ValidationReport.class);
        when(firebaseMapper.toHydrationPatch(any(), captor.capture(), any(Boolean.class), any(Boolean.class))).thenReturn(samplePatch());

        service.hydrateAll();

        ValidationReport composed = captor.getValue();
        assertThat(composed.missingFields()).isEmpty();
        assertThat(composed.status()).isEqualTo(ValidationStatus.COMPLETE);
    }

    @Test
    void composeReport_handlesNullExistingReportOnPartialRefresh() {
        // validationReportDto is null in the doc (bootstrap
        // scenario where no full fetch has run yet). parseMissingFields
        // must accept the null without throwing and produce an
        // empty set. The composed report then reflects only the
        // fresh evaluation filtered to the refreshed source.
        GameDocumentDto doc = docWithNullReport();
        stubPending(doc);
        when(refreshPolicy.decide(doc, false))
                .thenReturn(new RefreshPolicy.RefreshDecision(true, false));
        when(gameLookup.lookupByTitle(eq("Portal"), any()))
                .thenReturn(new GameLookup.GameLookupResult(sampleDeals(), null));
        ArgumentCaptor<ValidationReport> captor = ArgumentCaptor.forClass(ValidationReport.class);
        when(firebaseMapper.toHydrationPatch(any(), captor.capture(), any(Boolean.class), any(Boolean.class))).thenReturn(samplePatch());

        service.hydrateAll();

        ValidationReport composed = captor.getValue();
        assertThat(composed.missingFields()).isEmpty();
        assertThat(composed.status()).isEqualTo(ValidationStatus.COMPLETE);
    }

    @Test
    void composeReport_ignoresUnknownMissingFieldNamesInExistingReport() {
        // Defensive: an old schema version may have a field name
        // that has been renamed. parseMissingFields must skip it
        // without aborting the hydration.
        GameDocumentDto doc = docWithMissingFields("portal", "Portal",
                List.of("STORES", "LEGACY_FIELD", "TRAILER"));
        stubPending(doc);
        when(refreshPolicy.decide(doc, false))
                .thenReturn(new RefreshPolicy.RefreshDecision(false, true));
        when(gameLookup.lookupByTitle(eq("Portal"), any()))
                .thenReturn(new GameLookup.GameLookupResult(null, sampleRawgAgg()));
        ArgumentCaptor<ValidationReport> captor = ArgumentCaptor.forClass(ValidationReport.class);
        when(firebaseMapper.toHydrationPatch(any(), captor.capture(), any(Boolean.class), any(Boolean.class))).thenReturn(samplePatch());

        service.hydrateAll();

        ValidationReport composed = captor.getValue();
        // STORES is in CHEAPSHARK and the decision is refreshRawg=true,
        // so STORES is carried forward. TRAILER is in RAWG and we
        // refreshed RAWG with full data, so fresh.missingFields
        // does not contain TRAILER and it is dropped. LEGACY_FIELD
        // is silently ignored.
        assertThat(composed.missingFields()).containsExactly(GameField.STORES);
    }

    private static GameDocumentDto sampleDoc(String slug, String title) {
        return GameDocumentDtoFixtures.emptyDoc(slug, title);
    }

    private static GameDocumentDto docWithReport(String lastFull, String lastPartial) {
        GameDocumentDto base = sampleDoc("portal", "Portal");
        return new GameDocumentDto(
                base.title(), base.slug(), base.originalLanguage(), base.active(), base.addedAt(),
                base.cheapshark(), base.rawg(), base.locales(),
                new ValidationReportDto("PARTIAL", List.of("TRAILER"), lastFull, lastPartial));
    }

    private static GameDocumentDto docWithMissingFields(String slug, String title, List<String> missingFields) {
        GameDocumentDto base = sampleDoc(slug, title);
        return new GameDocumentDto(
                base.title(), base.slug(), base.originalLanguage(), base.active(), base.addedAt(),
                base.cheapshark(), base.rawg(), base.locales(),
                new ValidationReportDto("PARTIAL", missingFields, T.toString(), null));
    }

    private static GameDocumentDto docWithNullReport() {
        return sampleDoc("portal", "Portal");
    }

    private static GameDeals emptyDeals() {
        return new GameDeals(
                "82", "Portal", "Portal", "PORTAL",
                "https://example.com/thumb.jpg", null, 0, null, List.of(), T);
    }

    private static HydrationPatch samplePatch() {
        return new HydrationPatch(
                "Portal",
                CheapsharkBlock.empty(),
                RawgBlock.empty(),
                null);
    }

    private static GameDeals sampleDeals() {
        return new GameDeals(
                "82", "Portal", "Portal", "PORTAL",
                "https://example.com/thumb.jpg",
                new BigDecimal("0.99"),
                1,
                new Offer("1", "Steam", null,
                        new BigDecimal("1.99"), new BigDecimal("9.99"),
                        new BigDecimal("80.080"), "https://example.com/deal", null),
                List.of(),
                T);
    }

    private static AggregatedGame sampleRawgAgg() {
        return new AggregatedGame("Portal", "Portal", "portal",
                null, sampleRawg(), T);
    }

    private static RawgDetails sampleRawg() {
        return RawgDetailsFixtures.full("portal", "Portal")
                .fetchedAt(T).build();
    }

    /**
     * Wire the {@code readPending} + {@code readOne} +
     * {@code removeFromPending} chain that
     * {@link GameHydrationService#hydrateAll} walks for every
     * pending entry. Replaces the previous
     * {@code when(readAll()).thenReturn(List.of(doc))} pattern:
     * the service no longer reads the whole collection, it reads
     * the pending queue and then loads the game doc per entry.
     *
     * <p>{@code removeFromPending} is stubbed as a no-op so the
     * test does not have to verify it on every case. Tests that
     * exercise the failure path can override
     * {@code recordPendingFailure} / {@code moveToFailed}
     * themselves.
     */
    private void stubPending(GameDocumentDto... docs) {
        List<PendingDoc> entries = new ArrayList<>();
        for (GameDocumentDto doc : docs) {
            String slug = doc.slug() == null ? "<null-slug>" : doc.slug();
            entries.add(new PendingDoc(slug, 0, null, null));
            when(firebaseClient.readOne(slug)).thenReturn(Optional.of(doc));
            org.mockito.Mockito.doNothing().when(firebaseClient).removeFromPending(slug);
        }
        when(firebaseClient.readPending()).thenReturn(entries);
    }

    @Test
    void recoverStalePending_resetsEntriesOlderThanThreshold() {
        // Two stale entries (1h and 5h ago) plus a fresh one
        // (1 minute ago). Only the stale ones are reset.
        Instant now = Instant.parse("2026-06-30T10:00:00Z");
        List<PendingDoc> pending = List.of(
                new PendingDoc("stale1", 2, now.minus(Duration.ofHours(1)), "blip 1"),
                new PendingDoc("stale2", 3, now.minus(Duration.ofHours(5)), "stuck"),
                new PendingDoc("fresh", 1, now.minus(Duration.ofMinutes(1)), null));
        when(firebaseClient.readPending()).thenReturn(pending);

        int recovered = service.recoverStalePending(Duration.ofMinutes(30));

        assertThat(recovered).isEqualTo(2);
        org.mockito.Mockito.verify(firebaseClient).replacePending(
                new PendingDoc("stale1", 0, null, null));
        org.mockito.Mockito.verify(firebaseClient).replacePending(
                new PendingDoc("stale2", 0, null, null));
        org.mockito.Mockito.verify(firebaseClient, org.mockito.Mockito.never())
                .replacePending(org.mockito.ArgumentMatchers.argThat(
                        p -> p != null && "fresh".equals(p.slug())));
    }

    @Test
    void recoverStalePending_leavesNeverAttemptedEntriesUntouched() {
        // Freshly-enqueued entries have lastAttemptAt == null;
        // recovery must not touch them even though they would
        // otherwise be considered "stale" by virtue of being old.
        Instant now = Instant.parse("2026-06-30T10:00:00Z");
        List<PendingDoc> pending = List.of(
                new PendingDoc("never_attempted", 0, null, null));
        when(firebaseClient.readPending()).thenReturn(pending);

        int recovered = service.recoverStalePending(Duration.ofMinutes(30));

        assertThat(recovered).isZero();
        org.mockito.Mockito.verify(firebaseClient, org.mockito.Mockito.never())
                .replacePending(any());
    }

    @Test
    void recoverStalePending_returnsZeroWhenQueueIsEmpty() {
        when(firebaseClient.readPending()).thenReturn(List.of());

        int recovered = service.recoverStalePending(Duration.ofMinutes(30));

        assertThat(recovered).isZero();
    }

    @Test
    void recoverStalePending_rejectsNonPositiveThreshold() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.recoverStalePending(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.recoverStalePending(Duration.ofMinutes(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }
}
