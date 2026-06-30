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
import com.cheapquest.backend.dto.firebase.LocaleBlock;
import com.cheapquest.backend.dto.firebase.OfferDto;
import com.cheapquest.backend.dto.firebase.RawgBlock;
import com.cheapquest.backend.dto.firebase.ValidationReportDto;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.math.BigDecimal;
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

    private final Gson gson;
    private final Clock clock;

    public FirebaseMapper() {
        this(buildDefaultGson(), Clock.systemUTC());
    }

    public FirebaseMapper(Gson gson, Clock clock) {
        this.gson = gson;
        this.clock = clock;
    }

    private static Gson buildDefaultGson() {
        return new GsonBuilder()
                .disableHtmlEscaping()
                .registerTypeAdapter(Instant.class, new InstantTypeAdapter())
                .create();
    }

    public GameDocumentDto toBootstrapDocument(String title, String slug) {
        Instant now = Instant.now(clock);
        Map<String, LocaleBlock> locales = new HashMap<>();
        locales.put(LOCALE_ES, new LocaleBlock(false, null));
        locales.put(LOCALE_EN, new LocaleBlock(false, null));
        locales.put(LOCALE_FR, new LocaleBlock(false, null));
        return new GameDocumentDto(
                title,
                slug,
                "en",
                true,
                now.toString(),
                new CheapsharkBlock(false, null, null, null, 0, List.of()),
                new RawgBlock(false, null, null, null),
                locales,
                null);
    }

    public Map<String, Object> toHydrationPatch(AggregatedGame game, ValidationReport report) {
        Instant now = Instant.now(clock);
        Map<String, Object> patch = new HashMap<>();

        patch.put("title", game.canonicalName());
        patch.put("cheapshark", toCheapsharkBlock(game.cheapShark()));
        patch.put("rawg", toRawgBlock(game.rawg()));
        patch.put("validationReport", toValidationReportDto(report));

        Map<String, LocaleBlock> locales = new HashMap<>();
        if (game.rawg() != null) {
            locales.put(LOCALE_EN, new LocaleBlock(true, now.toString()));
        } else {
            locales.put(LOCALE_EN, new LocaleBlock(false, null));
        }
        locales.put(LOCALE_ES, new LocaleBlock(false, null));
        locales.put(LOCALE_FR, new LocaleBlock(false, null));
        patch.put("locales", locales);

        return patch;
    }

    public CheapsharkBlock toCheapsharkBlock(GameDeals deals) {
        if (deals == null) {
            return new CheapsharkBlock(false, null, null, null, 0, List.of());
        }
        OfferDto bestDto = deals.bestDeal() == null ? null : toOfferDto(deals.bestDeal());
        List<OfferDto> restDtos = new ArrayList<>(deals.offers().size());
        for (Offer o : deals.offers()) {
            restDtos.add(toOfferDto(o));
        }
        return new CheapsharkBlock(
                true,
                deals.gameId(),
                deals.cheapestEver(),
                bestDto,
                deals.offerCount(),
                List.copyOf(restDtos));
    }

    public RawgBlock toRawgBlock(RawgDetails rawg) {
        if (rawg == null) {
            return new RawgBlock(false, null, null, null);
        }
        Map<String, Object> dataMap = rawgDetailsToMap(rawg);
        Long id = parseLongOrNull(rawg.slug());
        return new RawgBlock(true, id, rawg.fetchedAt().toString(), dataMap);
    }

    public ValidationReportDto toValidationReportDto(ValidationReport report) {
        Set<GameField> missing = report.missingFields();
        List<String> missingNames = new ArrayList<>(missing.size());
        for (GameField f : missing) {
            missingNames.add(f.name());
        }
        String lastPartial = report.lastPartialFetchAt() == null
                ? null
                : report.lastPartialFetchAt().toString();
        return new ValidationReportDto(
                report.status().name(),
                List.copyOf(missingNames),
                report.lastFullFetchAt().toString(),
                lastPartial);
    }

    public static String toSlug(String title) {
        if (title == null) {
            throw new IllegalArgumentException("title");
        }
        String lower = title.toLowerCase(Locale.ROOT).trim();
        StringBuilder out = new StringBuilder(lower.length());
        boolean lastWasHyphen = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (isAsciiAlnum(c)) {
                out.append(c);
                lastWasHyphen = false;
            } else if (c == ' ' || c == '-' || c == '_') {
                if (out.length() > 0 && !lastWasHyphen) {
                    out.append('-');
                    lastWasHyphen = true;
                }
            }
        }
        while (out.length() > 0 && out.charAt(out.length() - 1) == '-') {
            out.deleteCharAt(out.length() - 1);
        }
        if (out.length() == 0) {
            throw new IllegalArgumentException("title produces empty slug: " + title);
        }
        return out.toString();
    }

    private OfferDto toOfferDto(Offer o) {
        return new OfferDto(
                o.storeId(),
                o.storeName(),
                o.storeIconUrl(),
                o.price(),
                o.retailPrice(),
                o.savings(),
                o.dealUrl());
    }

    private Map<String, Object> rawgDetailsToMap(RawgDetails rawg) {
        JsonObject tree = gson.toJsonTree(rawg).getAsJsonObject();
        Map<String, Object> out = new HashMap<>();
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : tree.entrySet()) {
            out.put(e.getKey(), gson.fromJson(e.getValue(), Object.class));
        }
        return out;
    }

    private static Long parseLongOrNull(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isAsciiAlnum(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    static ValidationStatus parseStatusOrNull(String s) {
        if (s == null) {
            return null;
        }
        try {
            return ValidationStatus.valueOf(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static BigDecimal nullToZero(BigDecimal bd) {
        return bd == null ? BigDecimal.ZERO : bd;
    }

    private static final class InstantTypeAdapter extends TypeAdapter<Instant> {
        @Override
        public void write(JsonWriter out, Instant value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(value.toString());
            }
        }

        @Override
        public Instant read(JsonReader in) throws IOException {
            if (in.peek() == com.google.gson.stream.JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            return Instant.parse(in.nextString());
        }
    }
}
