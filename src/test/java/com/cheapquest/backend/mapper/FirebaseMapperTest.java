package com.cheapquest.backend.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cheapquest.backend.domain.AggregatedGame;
import com.cheapquest.backend.domain.GameDeals;
import com.cheapquest.backend.domain.Offer;
import com.cheapquest.backend.domain.rawg.RawgDetails;
import com.cheapquest.backend.domain.validation.GameField;
import com.cheapquest.backend.domain.validation.ValidationReport;
import com.cheapquest.backend.domain.validation.ValidationStatus;
import com.cheapquest.backend.dto.firebase.CheapsharkBlock;
import com.cheapquest.backend.dto.firebase.GameDocumentDto;
import com.cheapquest.backend.dto.firebase.HydrationPatch;
import com.cheapquest.backend.dto.firebase.LocaleBlock;
import com.cheapquest.backend.dto.firebase.OfferDto;
import com.cheapquest.backend.dto.firebase.RawgBlock;
import com.cheapquest.backend.dto.firebase.ValidationReportDto;
import com.cheapquest.backend.fixtures.RawgDetailsFixtures;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FirebaseMapperTest {

    private static final Instant T = Instant.parse("2026-06-30T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-06-30T10:05:00Z");

    private final Gson gson = new GsonBuilder()
            .disableHtmlEscaping()
            .registerTypeAdapter(java.time.Instant.class, new FirebaseMapper.InstantTypeAdapter())
            .create();
    private final Clock clock = Clock.fixed(T, ZoneOffset.UTC);
    private final FirebaseMapper mapper = new FirebaseMapper(gson, clock);

    @Test
    void toSlug_simpleTitle() {
        assertThat(FirebaseMapper.toSlug("Portal")).isEqualTo("portal");
    }

    @Test
    void toSlug_hyphenatedTitle() {
        assertThat(FirebaseMapper.toSlug("Half-Life 2")).isEqualTo("half-life-2");
    }

    @Test
    void toSlug_multiWordTitle() {
        assertThat(FirebaseMapper.toSlug("Stardew Valley")).isEqualTo("stardew-valley");
    }

    @Test
    void toSlug_collapsesRepeatedSeparators() {
        assertThat(FirebaseMapper.toSlug("  Far  Cry  ")).isEqualTo("far-cry");
    }

    @Test
    void toSlug_stripsSpecialCharacters() {
        assertThat(FirebaseMapper.toSlug("Don't Starve!")).isEqualTo("dont-starve");
    }

    @Test
    void toSlug_keepsUnderscores() {
        assertThat(FirebaseMapper.toSlug("hello_world")).isEqualTo("hello-world");
    }

    @Test
    void toSlug_rejectsNull() {
        assertThatThrownBy(() -> FirebaseMapper.toSlug(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title");
    }

    @Test
    void toSlug_rejectsEmptyAfterCleaning() {
        assertThatThrownBy(() -> FirebaseMapper.toSlug("!!!"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty slug");
    }

    @Test
    void toBootstrapDocument_buildsBaseStructure() {
        GameDocumentDto doc = mapper.toBootstrapDocument("Portal", "portal");

        assertThat(doc.title()).isEqualTo("Portal");
        assertThat(doc.slug()).isEqualTo("portal");
        assertThat(doc.originalLanguage()).isEqualTo("en");
        assertThat(doc.active()).isTrue();
        assertThat(doc.addedAt()).isEqualTo(T.toString());
        assertThat(doc.cheapshark().synced()).isFalse();
        assertThat(doc.cheapshark().gameId()).isNull();
        assertThat(doc.cheapshark().deals()).isEmpty();
        assertThat(doc.cheapshark().offerCount()).isZero();
        assertThat(doc.rawg().synced()).isFalse();
        assertThat(doc.rawg().data()).isNull();
        assertThat(doc.locales()).hasSize(3);
        assertThat(doc.locales().get("es").synced()).isFalse();
        assertThat(doc.locales().get("en").synced()).isFalse();
        assertThat(doc.locales().get("fr").synced()).isFalse();
        assertThat(doc.validationReport()).isNull();
    }

    @Test
    void toCheapsharkBlock_returnsAllFalseWhenDealsIsNull() {
        CheapsharkBlock block = mapper.toCheapsharkBlock(null);
        assertThat(block.synced()).isFalse();
        assertThat(block.gameId()).isNull();
        assertThat(block.cheapestEver()).isNull();
        assertThat(block.bestDeal()).isNull();
        assertThat(block.offerCount()).isZero();
        assertThat(block.deals()).isEmpty();
    }

    @Test
    void toCheapsharkBlock_mapsDealsFields() {
        Offer best = new Offer("1", "Steam", "https://steam.png",
                new BigDecimal("1.99"), new BigDecimal("9.99"), new BigDecimal("80.080"),
                "https://deal/1");
        Offer other = new Offer("7", "GOG", null,
                new BigDecimal("2.50"), new BigDecimal("9.99"), new BigDecimal("74.977"),
                "https://deal/2");
        GameDeals deals = new GameDeals("82", "Portal", "Portal", "PORTAL",
                "https://thumb.jpg", new BigDecimal("0.99"), 2, best, List.of(other), T);

        CheapsharkBlock block = mapper.toCheapsharkBlock(deals);

        assertThat(block.synced()).isTrue();
        assertThat(block.gameId()).isEqualTo("82");
        assertThat(block.cheapestEver()).isEqualByComparingTo("0.99");
        assertThat(block.offerCount()).isEqualTo(2);
        assertThat(block.bestDeal()).isEqualTo(new OfferDto("1", "Steam", "https://steam.png",
                new BigDecimal("1.99"), new BigDecimal("9.99"), new BigDecimal("80.080"),
                "https://deal/1"));
        assertThat(block.deals()).hasSize(1);
        assertThat(block.deals().get(0).storeId()).isEqualTo("7");
    }

    @Test
    void toCheapsharkBlock_dealsListIsUnmodifiable() {
        GameDeals deals = new GameDeals("82", "Portal", "Portal", "PORTAL",
                "https://thumb.jpg", null, 0, null, List.of(), T);
        CheapsharkBlock block = mapper.toCheapsharkBlock(deals);
        assertThat(block.deals()).isUnmodifiable();
    }

    @Test
    void toRawgBlock_returnsAllFalseWhenRawgIsNull() {
        RawgBlock block = mapper.toRawgBlock(null);
        assertThat(block.synced()).isFalse();
        assertThat(block.fetchedAt()).isNull();
        assertThat(block.data()).isNull();
    }

    @Test
    void toRawgBlock_mapsRawgFields() {
        RawgDetails rawg = RawgDetailsFixtures.full("portal", "Portal")
                .fetchedAt(T).build();
        RawgBlock block = mapper.toRawgBlock(rawg);

        assertThat(block.synced()).isTrue();
        assertThat(block.fetchedAt()).isEqualTo(T.toString());
        assertThat(block.data()).isNotNull();
        assertThat(block.data()).containsKey("slug");
        assertThat(block.data().get("slug")).isEqualTo("portal");
        assertThat(block.data().get("name")).isEqualTo("Portal");
    }

    @Test
    void toValidationReportDto_mapsAllFields() {
        ValidationReport report = new ValidationReport(
                ValidationStatus.PARTIAL,
                EnumSet.of(GameField.TRAILER, GameField.TAGS),
                T,
                null);

        ValidationReportDto dto = mapper.toValidationReportDto(report);

        assertThat(dto.status()).isEqualTo("PARTIAL");
        assertThat(dto.missingFields()).containsExactlyInAnyOrder("TRAILER", "TAGS");
        assertThat(dto.lastFullFetchAt()).isEqualTo(T.toString());
        assertThat(dto.lastPartialFetchAt()).isNull();
    }

    @Test
    void toValidationReportDto_completeReportHasEmptyMissingFields() {
        ValidationReport report = new ValidationReport(
                ValidationStatus.COMPLETE,
                EnumSet.noneOf(GameField.class),
                T,
                T2);

        ValidationReportDto dto = mapper.toValidationReportDto(report);

        assertThat(dto.status()).isEqualTo("COMPLETE");
        assertThat(dto.missingFields()).isEmpty();
        assertThat(dto.lastPartialFetchAt()).isEqualTo(T2.toString());
    }

    @Test
    void toValidationReportDto_writesNullWhenLastFullFetchAtIsNull() {
        // A partial refresh on a freshly bootstrapped doc produces
        // a report with lastFullFetchAt=null. The mapper must
        // serialise that as a null string (Firestore stores it as
        // a null field) rather than throwing on toString().
        ValidationReport report = new ValidationReport(
                ValidationStatus.PARTIAL,
                EnumSet.of(GameField.TRAILER),
                null,
                T);

        ValidationReportDto dto = mapper.toValidationReportDto(report);

        assertThat(dto.lastFullFetchAt()).isNull();
        assertThat(dto.lastPartialFetchAt()).isEqualTo(T.toString());
        assertThat(dto.missingFields()).containsExactly("TRAILER");
    }

    @Test
    void toHydrationPatch_containsExpectedFields() {
        AggregatedGame game = new AggregatedGame("Portal", "Portal", "portal",
                fullDeals(), fullRawg(), T);
        ValidationReport report = new ValidationReport(
                ValidationStatus.PARTIAL,
                EnumSet.of(GameField.TRAILER),
                T,
                null);

        HydrationPatch patch = mapper.toHydrationPatch(game, report, true, true);

        assertThat(patch.title()).isEqualTo("Portal");
        assertThat(patch.cheapshark().synced()).isTrue();
        assertThat(patch.rawg().synced()).isTrue();
        assertThat(patch.locales()).containsKey("en");
        assertThat(patch.validationReport()).isNotNull();
    }

    @Test
    void toHydrationPatch_doesNotCarryMetadataFields() {
        AggregatedGame game = new AggregatedGame("Portal", "Portal", "portal",
                fullDeals(), fullRawg(), T);
        ValidationReport report = new ValidationReport(
                ValidationStatus.PARTIAL, EnumSet.of(GameField.TRAILER), T, null);

        HydrationPatch patch = mapper.toHydrationPatch(game, report, true, true);
        Map<String, Object> firestoreMap = patch.toFirestoreMap();

        assertThat(firestoreMap).containsOnlyKeys("title", "cheapshark", "rawg", "locales", "validationReport");
    }

    @Test
    void toHydrationPatch_titleIsCanonicalName() {
        AggregatedGame game = new AggregatedGame("Portal", "Portal (Canonical)", "portal",
                fullDeals(), fullRawg(), T);
        ValidationReport report = new ValidationReport(
                ValidationStatus.PARTIAL, EnumSet.of(GameField.TRAILER), T, null);

        HydrationPatch patch = mapper.toHydrationPatch(game, report, true, true);

        assertThat(patch.title()).isEqualTo("Portal (Canonical)");
    }

    @Test
    void toHydrationPatch_enLocaleIsSyncedWhenRawgPresent() {
        AggregatedGame game = new AggregatedGame("Portal", "Portal", "portal",
                fullDeals(), fullRawg(), T);
        ValidationReport report = new ValidationReport(
                ValidationStatus.PARTIAL, EnumSet.of(GameField.TRAILER), T, null);

        HydrationPatch patch = mapper.toHydrationPatch(game, report, true, true);

        assertThat(patch.locales().get("en").synced()).isTrue();
        assertThat(patch.locales().get("en").updatedAt()).isEqualTo(T.toString());
        assertThat(patch.locales().get("es").synced()).isFalse();
        assertThat(patch.locales().get("fr").synced()).isFalse();
    }

    @Test
    void toHydrationPatch_enLocaleNotSyncedWhenRawgMissing() {
        AggregatedGame game = new AggregatedGame("Portal", "Portal", "portal",
                fullDeals(), null, T);
        ValidationReport report = new ValidationReport(
                ValidationStatus.PARTIAL, EnumSet.of(GameField.DESCRIPTION), T, null);

        HydrationPatch patch = mapper.toHydrationPatch(game, report, true, true);

        assertThat(patch.locales().get("en").synced()).isFalse();
    }

    @Test
    void toHydrationPatch_validationReportIsIncluded() {
        AggregatedGame game = new AggregatedGame("Portal", "Portal", "portal",
                fullDeals(), fullRawg(), T);
        ValidationReport report = new ValidationReport(
                ValidationStatus.PARTIAL, EnumSet.of(GameField.TRAILER, GameField.STORES), T, null);

        HydrationPatch patch = mapper.toHydrationPatch(game, report, true, true);

        assertThat(patch.validationReport().status()).isEqualTo("PARTIAL");
        assertThat(patch.validationReport().missingFields()).containsExactlyInAnyOrder("TRAILER", "STORES");
    }

    @Test
    void hydrationPatch_toFirestoreMap_round_trip() {
        HydrationPatch patch = mapper.toHydrationPatch(
                new AggregatedGame("Portal", "Portal", "portal", fullDeals(), fullRawg(), T),
                new ValidationReport(ValidationStatus.PARTIAL, EnumSet.of(GameField.TRAILER), T, null),
                true, true);

        Map<String, Object> firestoreMap = patch.toFirestoreMap();

        assertThat(firestoreMap).containsOnlyKeys("title", "cheapshark", "rawg", "locales", "validationReport");
        assertThat(firestoreMap.get("title")).isEqualTo("Portal");
    }

    @Test
    void toHydrationPatch_omitsCheapsharkWhenDealsFresh() {
        AggregatedGame game = new AggregatedGame("Portal", "Portal", "portal",
                fullDeals(), fullRawg(), T);
        ValidationReport report = new ValidationReport(
                ValidationStatus.PARTIAL, EnumSet.of(GameField.TRAILER), T, null);

        HydrationPatch patch = mapper.toHydrationPatch(game, report, false, true);

        assertThat(patch.cheapshark()).isNull();
        assertThat(patch.rawg()).isNotNull();
        assertThat(patch.toFirestoreMap())
                .doesNotContainKey("cheapshark")
                .containsKey("rawg");
    }

    @Test
    void toHydrationPatch_omitsRawgWhenRawgFresh() {
        AggregatedGame game = new AggregatedGame("Portal", "Portal", "portal",
                fullDeals(), fullRawg(), T);
        ValidationReport report = new ValidationReport(
                ValidationStatus.PARTIAL, EnumSet.of(GameField.TRAILER), T, null);

        HydrationPatch patch = mapper.toHydrationPatch(game, report, true, false);

        assertThat(patch.cheapshark()).isNotNull();
        assertThat(patch.rawg()).isNull();
        assertThat(patch.toFirestoreMap())
                .containsKey("cheapshark")
                .doesNotContainKey("rawg");
    }

    @Test
    void toHydrationPatch_omitsBothWhenBothFresh() {
        AggregatedGame game = new AggregatedGame("Portal", "Portal", "portal",
                fullDeals(), fullRawg(), T);
        ValidationReport report = new ValidationReport(
                ValidationStatus.PARTIAL, EnumSet.of(GameField.TRAILER), T, null);

        HydrationPatch patch = mapper.toHydrationPatch(game, report, false, false);

        assertThat(patch.cheapshark()).isNull();
        assertThat(patch.rawg()).isNull();
        assertThat(patch.toFirestoreMap())
                .doesNotContainKey("cheapshark")
                .doesNotContainKey("rawg")
                .containsEntry("title", "Portal")
                .containsKey("locales")
                .containsKey("validationReport");
    }

    private static GameDeals fullDeals() {
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

    private static RawgDetails fullRawg() {
        return RawgDetailsFixtures.full("portal", "Portal").build();
    }
}
