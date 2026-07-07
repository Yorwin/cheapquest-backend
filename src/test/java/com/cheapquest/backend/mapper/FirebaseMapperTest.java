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
import org.mockito.ArgumentCaptor;

class FirebaseMapperTest {

    private static final Instant T = Instant.parse("2026-06-30T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-06-30T10:05:00Z");

    private final Gson gson = new GsonBuilder()
            .disableHtmlEscaping()
            .create();
    private final Clock clock = Clock.fixed(T, ZoneOffset.UTC);
    private final FirebaseMapper mapper = new FirebaseMapper(clock);

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
                "https://deal/1", null);
        Offer other = new Offer("7", "GOG", null,
                new BigDecimal("2.50"), new BigDecimal("9.99"), new BigDecimal("74.977"),
                "https://deal/2", null);
        GameDeals deals = new GameDeals("82", "Portal", "Portal", "PORTAL",
                "https://thumb.jpg", new BigDecimal("0.99"), 2, best, List.of(other), T);

        CheapsharkBlock block = mapper.toCheapsharkBlock(deals);

        assertThat(block.synced()).isTrue();
        assertThat(block.gameId()).isEqualTo("82");
        assertThat(block.cheapestEver()).isEqualByComparingTo("0.99");
        assertThat(block.offerCount()).isEqualTo(2);
        assertThat(block.bestDeal()).isEqualTo(new OfferDto("1", "Steam", "https://steam.png",
                new BigDecimal("1.99"), new BigDecimal("9.99"), new BigDecimal("80.080"),
                "https://deal/1", T.toString()));
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
    void toCheapsharkBlock_withPreviousBestNull_setsFirstSeenAtToNow() {
        Offer best = new Offer("1", "Steam", null,
                new BigDecimal("1.99"), new BigDecimal("9.99"), new BigDecimal("80.080"),
                "https://deal/1", null);
        GameDeals deals = new GameDeals("82", "Portal", "Portal", "PORTAL",
                "https://thumb.jpg", null, 1, best, List.of(), T);

        CheapsharkBlock block = mapper.toCheapsharkBlock(deals, null);

        assertThat(block.bestDeal().firstSeenAt()).isEqualTo(T.toString());
    }

    @Test
    void toCheapsharkBlock_withSameDeal_preservesFirstSeenAt() {
        Offer best = new Offer("1", "Steam", null,
                new BigDecimal("1.99"), new BigDecimal("9.99"), new BigDecimal("80.080"),
                "https://deal/1", null);
        GameDeals deals = new GameDeals("82", "Portal", "Portal", "PORTAL",
                "https://thumb.jpg", null, 1, best, List.of(), T);
        OfferDto previousBest = new OfferDto("1", "Steam", null,
                new BigDecimal("1.99"), new BigDecimal("9.99"), new BigDecimal("80.080"),
                "https://deal/1",
                "2026-06-29T10:00:00Z");

        CheapsharkBlock block = mapper.toCheapsharkBlock(deals, previousBest);

        assertThat(block.bestDeal().firstSeenAt()).isEqualTo("2026-06-29T10:00:00Z");
    }

    @Test
    void toCheapsharkBlock_withSameDealButDifferentUrl_preservesFirstSeenAt() {
        Offer best = new Offer("1", "Steam", null,
                new BigDecimal("1.99"), new BigDecimal("9.99"), new BigDecimal("80.080"),
                "https://deal/1-ROTATED", null);
        GameDeals deals = new GameDeals("82", "Portal", "Portal", "PORTAL",
                "https://thumb.jpg", null, 1, best, List.of(), T);
        OfferDto previousBest = new OfferDto("1", "Steam", null,
                new BigDecimal("1.99"), new BigDecimal("9.99"), new BigDecimal("80.080"),
                "https://deal/1",
                "2026-06-29T10:00:00Z");

        CheapsharkBlock block = mapper.toCheapsharkBlock(deals, previousBest);

        assertThat(block.bestDeal().firstSeenAt()).isEqualTo("2026-06-29T10:00:00Z");
    }

    @Test
    void toCheapsharkBlock_withDifferentStore_resetsFirstSeenAt() {
        Offer best = new Offer("2", "GOG", null,
                new BigDecimal("1.99"), new BigDecimal("9.99"), new BigDecimal("80.080"),
                "https://deal/2", null);
        GameDeals deals = new GameDeals("82", "Portal", "Portal", "PORTAL",
                "https://thumb.jpg", null, 1, best, List.of(), T);
        OfferDto previousBest = new OfferDto("1", "Steam", null,
                new BigDecimal("1.99"), new BigDecimal("9.99"), new BigDecimal("80.080"),
                "https://deal/1",
                "2026-06-29T10:00:00Z");

        CheapsharkBlock block = mapper.toCheapsharkBlock(deals, previousBest);

        assertThat(block.bestDeal().firstSeenAt()).isEqualTo(T.toString());
    }

    @Test
    void toCheapsharkBlock_withDifferentPrice_resetsFirstSeenAt() {
        Offer best = new Offer("1", "Steam", null,
                new BigDecimal("0.99"), new BigDecimal("9.99"), new BigDecimal("90.000"),
                "https://deal/1", null);
        GameDeals deals = new GameDeals("82", "Portal", "Portal", "PORTAL",
                "https://thumb.jpg", null, 1, best, List.of(), T);
        OfferDto previousBest = new OfferDto("1", "Steam", null,
                new BigDecimal("1.99"), new BigDecimal("9.99"), new BigDecimal("80.080"),
                "https://deal/1",
                "2026-06-29T10:00:00Z");

        CheapsharkBlock block = mapper.toCheapsharkBlock(deals, previousBest);

        assertThat(block.bestDeal().firstSeenAt()).isEqualTo(T.toString());
    }

    @Test
    void toCheapsharkBlock_withDifferentSavings_resetsFirstSeenAt() {
        Offer best = new Offer("1", "Steam", null,
                new BigDecimal("1.99"), new BigDecimal("9.99"), new BigDecimal("90.000"),
                "https://deal/1", null);
        GameDeals deals = new GameDeals("82", "Portal", "Portal", "PORTAL",
                "https://thumb.jpg", null, 1, best, List.of(), T);
        OfferDto previousBest = new OfferDto("1", "Steam", null,
                new BigDecimal("1.99"), new BigDecimal("9.99"), new BigDecimal("80.080"),
                "https://deal/1",
                "2026-06-29T10:00:00Z");

        CheapsharkBlock block = mapper.toCheapsharkBlock(deals, previousBest);

        assertThat(block.bestDeal().firstSeenAt()).isEqualTo(T.toString());
    }

    @Test
    void toCheapsharkBlock_withSameDealButNullPreviousFirstSeen_resetsFirstSeenAt() {
        Offer best = new Offer("1", "Steam", null,
                new BigDecimal("1.99"), new BigDecimal("9.99"), new BigDecimal("80.080"),
                "https://deal/1", null);
        GameDeals deals = new GameDeals("82", "Portal", "Portal", "PORTAL",
                "https://thumb.jpg", null, 1, best, List.of(), T);
        OfferDto previousBest = new OfferDto("1", "Steam", null,
                new BigDecimal("1.99"), new BigDecimal("9.99"), new BigDecimal("80.080"),
                "https://deal/1", null);

        CheapsharkBlock block = mapper.toCheapsharkBlock(deals, previousBest);

        assertThat(block.bestDeal().firstSeenAt()).isEqualTo(T.toString());
    }

    @Test
    void toCheapsharkBlock_withNullBestDeal_keepsNullFirstSeenAt() {
        GameDeals deals = new GameDeals("82", "Portal", "Portal", "PORTAL",
                "https://thumb.jpg", null, 0, null, List.of(), T);
        OfferDto previousBest = new OfferDto("1", "Steam", null,
                new BigDecimal("1.99"), new BigDecimal("9.99"), new BigDecimal("80.080"),
                "https://deal/1",
                "2026-06-29T10:00:00Z");

        CheapsharkBlock block = mapper.toCheapsharkBlock(deals, previousBest);

        assertThat(block.bestDeal()).isNull();
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
        assertThat(block.data().slug()).isEqualTo("portal");
        assertThat(block.data().name()).isEqualTo("Portal");
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

        assertThat(firestoreMap).containsOnlyKeys("title", "cheapshark", "rawg", "validationReport");
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
    void toHydrationPatch_omitsLocales() {
        // The patch must NOT carry locales: a future translation
        // service writes locales.es and locales.fr; the hydration
        // path would clobber them on every refresh if locales were
        // bundled in. The en locale is updated separately via
        // FirebaseClient.markLocaleSynced.
        AggregatedGame game = new AggregatedGame("Portal", "Portal", "portal",
                fullDeals(), fullRawg(), T);
        ValidationReport report = new ValidationReport(
                ValidationStatus.PARTIAL, EnumSet.of(GameField.TRAILER), T, null);

        HydrationPatch patch = mapper.toHydrationPatch(game, report, true, true);

        assertThat(patch.toFirestoreMap()).doesNotContainKey("locales");
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

        assertThat(firestoreMap).containsOnlyKeys("title", "cheapshark", "rawg", "validationReport");
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
                .containsKey("validationReport");
    }

    @Test
    void toRawgBlock_emits_typed_document_dto() {
        // The data block on Firestore is now a RawgDocumentDto (typed
        // record), not a free-form map. Every field the
        // ValidationConsistencyChecker needs to evaluate the
        // missing-fields set is present by construction.
        RawgDetails rawg = RawgDetailsFixtures.full("portal", "Portal").build();
        RawgBlock block = mapper.toRawgBlock(rawg);

        assertThat(block.data()).isNotNull();
        assertThat(block.data().description()).isEqualTo(rawg.description());
        assertThat(block.data().descriptionRaw()).isEqualTo(rawg.descriptionRaw());
        assertThat(block.data().headerImage()).isEqualTo(rawg.headerImage());
        assertThat(block.data().trailerUrl()).isEqualTo(rawg.trailerUrl());
        assertThat(block.data().released()).isEqualTo(rawg.released());
        assertThat(block.data().genres()).hasSize(rawg.genres().size());
        assertThat(block.data().tags()).hasSize(rawg.tags().size());
        assertThat(block.data().screenshots()).hasSize(rawg.screenshots().size());
        assertThat(block.data().developers()).hasSize(rawg.developers().size());
        assertThat(block.data().publishers()).hasSize(rawg.publishers().size());
        assertThat(block.data().fetchedAt()).isEqualTo(rawg.fetchedAt().toString());
    }

    @Test
    void rawgBlock_data_survives_gson_round_trip() {
        // Sanity check: the typed record round-trips through GSON
        // with the field names a Firestore document would carry. A
        // rename of any field in RawgDetails is now a compile error
        // (this test only catches GSON coercion surprises like int
        // turning into double).
        RawgDetails rawg = RawgDetailsFixtures.full("portal", "Portal").build();
        RawgBlock block = mapper.toRawgBlock(rawg);
        com.cheapquest.backend.dto.firebase.RawgDocumentDto back = gson.fromJson(
                gson.toJsonTree(block.data()),
                com.cheapquest.backend.dto.firebase.RawgDocumentDto.class);
        assertThat(back).isEqualTo(block.data());
    }

    private static GameDeals fullDeals() {
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

    private static RawgDetails fullRawg() {
        return RawgDetailsFixtures.full("portal", "Portal").build();
    }
}
