package com.cheapquest.backend.mapper;

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
import com.cheapquest.backend.dto.firebase.RawgDocumentDto;
import com.cheapquest.backend.dto.firebase.ValidationReportDto;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Converts between the domain layer ({@link AggregatedGame},
 * {@link GameDeals}, {@link RawgDetails}, {@link ValidationReport}) and
 * the Firestore document layer ({@link GameDocumentDto} and friends).
 *
 * <p>The hydration path produces a partial-update map (only the fields
 * that change), not a full document; the {@code slug}, {@code addedAt}
 * and {@code active} flags set at bootstrap are never touched.
 */
public final class FirebaseMapper {

    private static final String LOCALE_EN = "en";
    private static final String LOCALE_ES = "es";
    private static final String LOCALE_FR = "fr";

    private static final Map<String, LocaleBlock> UNSYNCED_LOCALES = Map.of(
            LOCALE_ES, LocaleBlock.unsynced(),
            LOCALE_EN, LocaleBlock.unsynced(),
            LOCALE_FR, LocaleBlock.unsynced());

    private final Clock clock;

    public FirebaseMapper() {
        this(Clock.systemUTC());
    }

    public FirebaseMapper(Clock clock) {
        this.clock = clock;
    }

    /**
     * Canonical {@link Gson} instance used by the external-API
     * clients (CheapShark, RAWG). HTML escaping is disabled so
     * URLs and HTML fragments are preserved verbatim. The
     * Firestore mapper does not use this {@link Gson} directly
     * anymore: DTOs are produced by record constructors and
     * deserialised by the Firestore SDK's own GSON-backed
     * {@code toObject}, so the round-trip is fully typed.
     */
    public static Gson newGson() {
        return new GsonBuilder()
                .disableHtmlEscaping()
                .create();
    }

    public GameDocumentDto toBootstrapDocument(String title, String slug) {
        Instant now = Instant.now(clock);
        return new GameDocumentDto(
                title,
                slug,
                "en",
                true,
                now.toString(),
                CheapsharkBlock.empty(),
                RawgBlock.empty(),
                Map.copyOf(UNSYNCED_LOCALES),
                null);
    }

    public HydrationPatch toHydrationPatch(AggregatedGame game, ValidationReport report,
            boolean dealsStale, boolean rawgStale) {
        return new HydrationPatch(
                game.canonicalName(),
                dealsStale ? toCheapsharkBlock(game.cheapShark()) : null,
                rawgStale ? toRawgBlock(game.rawg()) : null,
                toValidationReportDto(report));
    }

    public CheapsharkBlock toCheapsharkBlock(GameDeals deals) {
        if (deals == null) {
            return CheapsharkBlock.empty();
        }
        OfferDto bestDto = deals.bestDeal() == null ? null : OfferConverter.toDto(deals.bestDeal());
        List<OfferDto> restDtos = new ArrayList<>(deals.offers().size());
        for (Offer o : deals.offers()) {
            restDtos.add(OfferConverter.toDto(o));
        }
        return new CheapsharkBlock(
                true,
                deals.gameId(),
                deals.cheapestEver(),
                bestDto,
                deals.offerCount(),
                List.copyOf(restDtos),
                deals.fetchedAt().toString());
    }

    public RawgBlock toRawgBlock(RawgDetails rawg) {
        if (rawg == null) {
            return new RawgBlock(false, null, null);
        }
        return new RawgBlock(true, rawg.fetchedAt().toString(), toDocumentDto(rawg));
    }

    private static RawgDocumentDto toDocumentDto(RawgDetails rawg) {
        return new RawgDocumentDto(
                rawg.slug(),
                rawg.name(),
                rawg.nameOriginal(),
                rawg.released(),
                rawg.description(),
                rawg.descriptionRaw(),
                rawg.headerImage(),
                rawg.trailerUrl(),
                rawg.website(),
                rawg.rating(),
                rawg.ratingTop(),
                rawg.metacritic(),
                rawg.additionsCount(),
                rawg.creatorsCount(),
                rawg.moviesCount(),
                rawg.screenshotsCount(),
                rawg.developers(),
                rawg.publishers(),
                rawg.genres(),
                rawg.tags(),
                rawg.platforms(),
                rawg.parentPlatforms(),
                rawg.dlcs(),
                rawg.creators(),
                rawg.screenshots(),
                rawg.fetchedAt().toString());
    }

    public ValidationReportDto toValidationReportDto(ValidationReport report) {
        Set<GameField> missing = report.missingFields();
        List<String> missingNames = new ArrayList<>(missing.size());
        for (GameField f : missing) {
            missingNames.add(f.name());
        }
        String lastFull = report.lastFullFetchAt() == null
                ? null
                : report.lastFullFetchAt().toString();
        String lastPartial = report.lastPartialFetchAt() == null
                ? null
                : report.lastPartialFetchAt().toString();
        return new ValidationReportDto(
                report.status().name(),
                List.copyOf(missingNames),
                lastFull,
                lastPartial);
    }

    public static String toSlug(String title) {
        if (title == null) {
            throw new IllegalArgumentException("title");
        }
        String slug = title.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 _-]+", "")
                .replaceAll("[\\s_-]+", "-")
                .replaceAll("^-|-$", "");
        if (slug.isEmpty()) {
            throw new IllegalArgumentException("title produces empty slug: " + title);
        }
        return slug;
    }
}
