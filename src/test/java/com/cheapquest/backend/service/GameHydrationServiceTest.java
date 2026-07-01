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
import com.cheapquest.backend.dto.firebase.GameDocumentDto;
import com.cheapquest.backend.dto.firebase.HydrationPatch;
import com.cheapquest.backend.dto.firebase.LocaleBlock;
import com.cheapquest.backend.exception.FirebaseUnavailableException;
import com.cheapquest.backend.exception.GameNotFoundException;
import com.cheapquest.backend.fixtures.GameDocumentDtoFixtures;
import com.cheapquest.backend.fixtures.RawgDetailsFixtures;
import com.cheapquest.backend.mapper.FirebaseMapper;
import java.math.BigDecimal;
import java.time.Clock;
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
    private GameAggregationService csService;
    private RawgAggregationService rawgService;
    private GameMerger merger;
    private ValidationService validator;
    private GameHydrationService service;

    @BeforeEach
    void setUp() {
        firebaseClient = mock(FirebaseClient.class);
        firebaseMapper = mock(FirebaseMapper.class);
        csService = mock(GameAggregationService.class);
        rawgService = mock(RawgAggregationService.class);
        merger = new GameMerger(Clock.fixed(T, ZoneOffset.UTC));
        validator = new ValidationService(Clock.fixed(T, ZoneOffset.UTC));
        service = new GameHydrationService(
                firebaseClient, firebaseMapper, csService, rawgService, merger, validator,
                Clock.fixed(T, ZoneOffset.UTC));
    }

    @Test
    void constructor_rejectsNullDependencies() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new GameHydrationService(null, firebaseMapper, csService, rawgService,
                                merger, validator, Clock.systemUTC()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void hydrateAll_writesPatchWhenBothSourcesSucceed() {
        GameDocumentDto doc = sampleDoc("portal", "Portal");
        when(firebaseClient.readAll()).thenReturn(List.of(doc));
        GameDeals deals = sampleDeals();
        when(csService.aggregateByName("Portal")).thenReturn(deals);
        AggregatedGame rawgAgg = new AggregatedGame("Portal", "Portal", "portal",
                null, sampleRawg(), T);
        when(rawgService.aggregate("Portal")).thenReturn(rawgAgg);
        when(firebaseMapper.toHydrationPatch(any(), any())).thenReturn(samplePatch());

        HydrationReport report = service.hydrateAll();

        assertThat(report.processed()).isEqualTo(1);
        assertThat(report.complete()).isEqualTo(1);
        assertThat(report.partial()).isZero();
        assertThat(report.empty()).isZero();
        assertThat(report.failed()).isZero();
        assertThat(report.failures()).isEmpty();
        verify(firebaseClient).update(eq("portal"), any(HydrationPatch.class));
    }

    @Test
    void hydrateAll_countsPartialWhenSomeFieldsMissing() {
        GameDocumentDto doc = sampleDoc("portal", "Portal");
        when(firebaseClient.readAll()).thenReturn(List.of(doc));
        when(csService.aggregateByName("Portal")).thenReturn(sampleDeals());
        RawgDetails rawgNoTrailer = RawgDetailsFixtures.full("portal", "Portal")
                .trailerUrl(null).build();
        AggregatedGame rawgAgg = new AggregatedGame("Portal", "Portal", "portal",
                null, rawgNoTrailer, T);
        when(rawgService.aggregate("Portal")).thenReturn(rawgAgg);
        when(firebaseMapper.toHydrationPatch(any(), any())).thenReturn(samplePatch());

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
        when(csService.aggregateByName("Portal"))
                .thenThrow(new GameNotFoundException("no match"));
        when(rawgService.aggregate("Portal"))
                .thenThrow(new GameNotFoundException("no match"));

        HydrationReport report = service.hydrateAll();

        assertThat(report.processed()).isEqualTo(1);
        assertThat(report.empty()).isEqualTo(1);
        assertThat(report.complete()).isZero();
        assertThat(report.partial()).isZero();
        assertThat(report.failed()).isZero();
        verify(firebaseClient, never()).update(anyString(), any(HydrationPatch.class));
    }

    @Test
    void hydrateAll_writesPartialWhenOnlyCheapSharkSucceeds() {
        GameDocumentDto doc = sampleDoc("portal", "Portal");
        when(firebaseClient.readAll()).thenReturn(List.of(doc));
        when(csService.aggregateByName("Portal")).thenReturn(sampleDeals());
        when(rawgService.aggregate("Portal"))
                .thenThrow(new GameNotFoundException("no rawg"));
        when(firebaseMapper.toHydrationPatch(any(), any())).thenReturn(samplePatch());

        HydrationReport report = service.hydrateAll();

        assertThat(report.processed()).isEqualTo(1);
        assertThat(report.partial()).isEqualTo(1);
        verify(firebaseClient).update(eq("portal"), any(HydrationPatch.class));
    }

    @Test
    void hydrateAll_writesPartialWhenOnlyRawgSucceeds() {
        GameDocumentDto doc = sampleDoc("portal", "Portal");
        when(firebaseClient.readAll()).thenReturn(List.of(doc));
        when(csService.aggregateByName("Portal"))
                .thenThrow(new GameNotFoundException("no cheapshark"));
        AggregatedGame rawgAgg = new AggregatedGame("Portal", "Portal", "portal",
                null, sampleRawg(), T);
        when(rawgService.aggregate("Portal")).thenReturn(rawgAgg);
        when(firebaseMapper.toHydrationPatch(any(), any())).thenReturn(samplePatch());

        HydrationReport report = service.hydrateAll();

        assertThat(report.processed()).isEqualTo(1);
        assertThat(report.partial()).isEqualTo(1);
        verify(firebaseClient).update(eq("portal"), any(HydrationPatch.class));
    }

    @Test
    void hydrateAll_countsFailureWhenFirestoreUpdateThrows() {
        GameDocumentDto doc = sampleDoc("portal", "Portal");
        when(firebaseClient.readAll()).thenReturn(List.of(doc));
        when(csService.aggregateByName("Portal")).thenReturn(sampleDeals());
        AggregatedGame rawgAgg = new AggregatedGame("Portal", "Portal", "portal",
                null, sampleRawg(), T);
        when(rawgService.aggregate("Portal")).thenReturn(rawgAgg);
        when(firebaseMapper.toHydrationPatch(any(), any())).thenReturn(samplePatch());
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
        when(csService.aggregateByName("Portal")).thenReturn(sampleDeals());
        when(csService.aggregateByName("Half-Life 2"))
                .thenThrow(new GameNotFoundException("no match"));
        when(csService.aggregateByName("Stardew Valley")).thenReturn(sampleDeals());
        AggregatedGame rawgAgg = new AggregatedGame("X", "X", "x",
                null, sampleRawg(), T);
        when(rawgService.aggregate(anyString())).thenReturn(rawgAgg);
        when(firebaseMapper.toHydrationPatch(any(), any())).thenReturn(samplePatch());

        HydrationReport report = service.hydrateAll();

        assertThat(report.processed()).isEqualTo(3);
        assertThat(report.partial()).isEqualTo(1);
        assertThat(report.complete()).isEqualTo(2);
        verify(firebaseClient, times(3)).update(anyString(), any(HydrationPatch.class));
    }

    @Test
    void hydrateAll_skipsDocsWithMissingSlugOrTitle() {
        GameDocumentDto docNoSlug = new GameDocumentDto(
                "Title", null, "en", true, T.toString(),
                com.cheapquest.backend.dto.firebase.CheapsharkBlock.empty(),
                com.cheapquest.backend.dto.firebase.RawgBlock.empty(),
                Map.of("es", LocaleBlock.unsynced()), null);
        GameDocumentDto docNoTitle = new GameDocumentDto(
                null, "slug", "en", true, T.toString(),
                com.cheapquest.backend.dto.firebase.CheapsharkBlock.empty(),
                com.cheapquest.backend.dto.firebase.RawgBlock.empty(),
                Map.of("es", LocaleBlock.unsynced()), null);
        when(firebaseClient.readAll()).thenReturn(List.of(docNoSlug, docNoTitle));

        HydrationReport report = service.hydrateAll();

        assertThat(report.empty()).isEqualTo(2);
        verify(csService, never()).aggregateByName(anyString());
        verify(rawgService, never()).aggregate(anyString());
    }

    @Test
    void hydrateOne_returnsFalseWhenDocMissing() {
        when(firebaseClient.readOne("missing")).thenReturn(java.util.Optional.empty());

        assertThat(service.hydrateOne("missing")).isFalse();
    }

    @Test
    void hydrateOne_returnsFalseWhenBothSourcesFail() {
        when(firebaseClient.readOne("portal")).thenReturn(java.util.Optional.of(sampleDoc("portal", "Portal")));
        when(csService.aggregateByName("Portal"))
                .thenThrow(new GameNotFoundException("no match"));
        when(rawgService.aggregate("Portal"))
                .thenThrow(new GameNotFoundException("no match"));

        assertThat(service.hydrateOne("portal")).isFalse();
    }

    @Test
    void hydrateOne_returnsTrueAndWritesPatchOnSuccess() {
        when(firebaseClient.readOne("portal")).thenReturn(java.util.Optional.of(sampleDoc("portal", "Portal")));
        when(csService.aggregateByName("Portal")).thenReturn(sampleDeals());
        AggregatedGame rawgAgg = new AggregatedGame("Portal", "Portal", "portal",
                null, sampleRawg(), T);
        when(rawgService.aggregate("Portal")).thenReturn(rawgAgg);
        when(firebaseMapper.toHydrationPatch(any(), any())).thenReturn(samplePatch());

        assertThat(service.hydrateOne("portal")).isTrue();
        verify(firebaseClient).update(eq("portal"), any(HydrationPatch.class));
    }

    @Test
    void hydrateAll_passesCorrectTitleToServices() {
        GameDocumentDto doc = sampleDoc("portal", "Portal");
        when(firebaseClient.readAll()).thenReturn(List.of(doc));
        when(csService.aggregateByName("Portal")).thenReturn(sampleDeals());
        AggregatedGame rawgAgg = new AggregatedGame("Portal", "Portal", "portal",
                null, sampleRawg(), T);
        when(rawgService.aggregate("Portal")).thenReturn(rawgAgg);
        when(firebaseMapper.toHydrationPatch(any(), any())).thenReturn(samplePatch());

        service.hydrateAll();

        verify(csService).aggregateByName("Portal");
        verify(rawgService).aggregate("Portal");
    }

    private static GameDocumentDto sampleDoc(String slug, String title) {
        return GameDocumentDtoFixtures.emptyDoc(slug, title);
    }

    private static HydrationPatch samplePatch() {
        return new HydrationPatch(
                "Portal",
                com.cheapquest.backend.dto.firebase.CheapsharkBlock.empty(),
                com.cheapquest.backend.dto.firebase.RawgBlock.empty(),
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

    private static RawgDetails sampleRawg() {
        return RawgDetailsFixtures.full("portal", "Portal")
                .fetchedAt(T).build();
    }
}
