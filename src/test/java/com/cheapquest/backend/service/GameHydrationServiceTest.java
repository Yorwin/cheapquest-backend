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
import com.cheapquest.backend.dto.firebase.RawgBlock;
import com.cheapquest.backend.dto.firebase.ValidationReportDto;
import com.cheapquest.backend.exception.FirebaseUnavailableException;
import com.cheapquest.backend.fixtures.GameDocumentDtoFixtures;
import com.cheapquest.backend.fixtures.RawgDetailsFixtures;
import com.cheapquest.backend.mapper.FirebaseMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
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
        when(firebaseClient.readAll()).thenReturn(List.of(doc));
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
    void hydrateAll_countsPartialWhenSomeFieldsMissing() {
        GameDocumentDto doc = sampleDoc("portal", "Portal");
        when(firebaseClient.readAll()).thenReturn(List.of(doc));
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
        when(firebaseClient.readAll()).thenReturn(List.of(doc));
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
        when(firebaseClient.readAll()).thenReturn(List.of(doc));
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
        when(firebaseClient.readAll()).thenReturn(List.of(doc));
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
        when(firebaseClient.readAll()).thenReturn(List.of(doc));
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
        when(firebaseClient.readAll()).thenReturn(List.of(doc));
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
    void hydrateAll_processesMultipleDocs() {
        GameDocumentDto portal = sampleDoc("portal", "Portal");
        GameDocumentDto hl2 = sampleDoc("half-life-2", "Half-Life 2");
        GameDocumentDto stardew = sampleDoc("stardew-valley", "Stardew Valley");
        when(firebaseClient.readAll()).thenReturn(List.of(portal, hl2, stardew));
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
        when(firebaseClient.readAll()).thenReturn(List.of(docNoSlug, docNoTitle));

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
        when(firebaseClient.readAll()).thenReturn(List.of(doc));
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
        when(firebaseClient.readAll()).thenReturn(List.of(doc));
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
        when(firebaseClient.readAll()).thenReturn(List.of(doc));
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
        when(firebaseClient.readAll()).thenReturn(List.of(doc));
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
        when(firebaseClient.readAll()).thenReturn(List.of(doc));
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
        when(firebaseClient.readAll()).thenReturn(List.of(doc));
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
        when(firebaseClient.readAll()).thenReturn(List.of(doc));
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
        when(firebaseClient.readAll()).thenReturn(List.of(doc));
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
        when(firebaseClient.readAll()).thenReturn(List.of(doc));
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
        when(firebaseClient.readAll()).thenReturn(List.of(doc));
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
        when(firebaseClient.readAll()).thenReturn(List.of(doc));
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
        when(firebaseClient.readAll()).thenReturn(List.of(doc));
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
        when(firebaseClient.readAll()).thenReturn(List.of(doc));
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
                Map.of("es", LocaleBlock.unsynced(),
                        "en", LocaleBlock.unsynced(),
                        "fr", LocaleBlock.unsynced()),
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
                        new BigDecimal("80.080"), "https://example.com/deal"),
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
}
