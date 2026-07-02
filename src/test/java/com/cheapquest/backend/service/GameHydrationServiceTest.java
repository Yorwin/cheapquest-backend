package com.cheapquest.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
        when(refreshPolicy.decide(doc))
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
        when(refreshPolicy.decide(doc))
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
        when(refreshPolicy.decide(doc))
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
    void hydrateAll_writesPartialWhenOnlyCheapSharkRefreshed() {
        GameDocumentDto doc = sampleDoc("portal", "Portal");
        when(firebaseClient.readAll()).thenReturn(List.of(doc));
        when(refreshPolicy.decide(doc))
                .thenReturn(new RefreshPolicy.RefreshDecision(true, false));
        when(gameLookup.lookupByTitle(eq("Portal"), eq(EnumSet.of(GameLookup.Source.CHEAPSHARK))))
                .thenReturn(new GameLookup.GameLookupResult(sampleDeals(), null));
        when(firebaseMapper.toHydrationPatch(any(), any(), any(Boolean.class), any(Boolean.class))).thenReturn(samplePatch());

        HydrationReport report = service.hydrateAll();

        assertThat(report.processed()).isEqualTo(1);
        assertThat(report.partial()).isEqualTo(1);
        assertThat(report.dealsRefreshed()).isEqualTo(1);
        assertThat(report.rawgRefreshed()).isZero();
        verify(firebaseClient).update(eq("portal"), any(HydrationPatch.class));
    }

    @Test
    void hydrateAll_writesPartialWhenOnlyRawgRefreshed() {
        GameDocumentDto doc = sampleDoc("portal", "Portal");
        when(firebaseClient.readAll()).thenReturn(List.of(doc));
        when(refreshPolicy.decide(doc))
                .thenReturn(new RefreshPolicy.RefreshDecision(false, true));
        when(gameLookup.lookupByTitle(eq("Portal"), eq(EnumSet.of(GameLookup.Source.RAWG))))
                .thenReturn(new GameLookup.GameLookupResult(null, sampleRawgAgg()));
        when(firebaseMapper.toHydrationPatch(any(), any(), any(Boolean.class), any(Boolean.class))).thenReturn(samplePatch());

        HydrationReport report = service.hydrateAll();

        assertThat(report.processed()).isEqualTo(1);
        assertThat(report.partial()).isEqualTo(1);
        assertThat(report.dealsRefreshed()).isZero();
        assertThat(report.rawgRefreshed()).isEqualTo(1);
        verify(firebaseClient).update(eq("portal"), any(HydrationPatch.class));
    }

    @Test
    void hydrateAll_skipsFreshDoc() {
        GameDocumentDto doc = sampleDoc("portal", "Portal");
        when(firebaseClient.readAll()).thenReturn(List.of(doc));
        when(refreshPolicy.decide(doc))
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
        when(refreshPolicy.decide(doc))
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
        when(refreshPolicy.decide(portal))
                .thenReturn(new RefreshPolicy.RefreshDecision(true, true));
        when(refreshPolicy.decide(hl2))
                .thenReturn(new RefreshPolicy.RefreshDecision(false, true));
        when(refreshPolicy.decide(stardew))
                .thenReturn(new RefreshPolicy.RefreshDecision(true, true));
        when(gameLookup.lookupByTitle(eq("Portal"), any()))
                .thenReturn(new GameLookup.GameLookupResult(sampleDeals(), sampleRawgAgg()));
        when(gameLookup.lookupByTitle(eq("Half-Life 2"), any()))
                .thenReturn(new GameLookup.GameLookupResult(null, sampleRawgAgg()));
        when(gameLookup.lookupByTitle(eq("Stardew Valley"), any()))
                .thenReturn(new GameLookup.GameLookupResult(sampleDeals(), sampleRawgAgg()));
        when(firebaseMapper.toHydrationPatch(any(), any(), any(Boolean.class), any(Boolean.class))).thenReturn(samplePatch());

        HydrationReport report = service.hydrateAll();

        assertThat(report.processed()).isEqualTo(3);
        assertThat(report.partial()).isEqualTo(1);
        assertThat(report.complete()).isEqualTo(2);
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
        when(refreshPolicy.decide(any()))
                .thenReturn(new RefreshPolicy.RefreshDecision(true, true));
        when(gameLookup.lookupByTitle(eq("Portal"), any()))
                .thenReturn(GameLookup.GameLookupResult.empty());

        assertThat(service.hydrateOne("portal")).isFalse();
    }

    @Test
    void hydrateOne_returnsTrueAndWritesPatchOnSuccess() {
        when(firebaseClient.readOne("portal")).thenReturn(java.util.Optional.of(sampleDoc("portal", "Portal")));
        when(refreshPolicy.decide(any()))
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
        when(refreshPolicy.decide(doc))
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
        when(refreshPolicy.decide(doc))
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
        when(refreshPolicy.decide(doc))
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
        when(refreshPolicy.decide(doc))
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
