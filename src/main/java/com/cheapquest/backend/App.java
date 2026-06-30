package com.cheapquest.backend;

import com.cheapquest.backend.client.CheapSharkClient;
import com.cheapquest.backend.client.RawgClient;
import com.cheapquest.backend.config.AppProperties;
import com.cheapquest.backend.config.DefaultHttpFetcher;
import com.cheapquest.backend.config.FirebaseConfig;
import com.cheapquest.backend.config.HttpClientFactory;
import com.cheapquest.backend.config.HttpFetcher;
import com.cheapquest.backend.domain.AggregatedGame;
import com.cheapquest.backend.domain.GameDeals;
import com.cheapquest.backend.domain.Offer;
import com.cheapquest.backend.domain.rawg.RawgDetails;
import com.cheapquest.backend.domain.validation.ValidationReport;
import com.cheapquest.backend.dto.cheapshark.CheapSharkStoreDto;
import com.cheapquest.backend.exception.GameNotFoundException;
import com.cheapquest.backend.fixtures.GameFixtures;
import com.cheapquest.backend.fixtures.HardcodedGame;
import com.cheapquest.backend.mapper.CheapSharkMapper;
import com.cheapquest.backend.mapper.RawgMapper;
import com.cheapquest.backend.service.GameAggregationService;
import com.cheapquest.backend.service.GameMerger;
import com.cheapquest.backend.service.RawgAggregationService;
import com.cheapquest.backend.service.ValidationService;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.net.http.HttpClient;
import java.util.List;

/**
 * Entry point of the backend. Wires up the HTTP layer, the JSON parser, the
 * CheapShark client, the RAWG client, and runs a manual smoke test against
 * the live APIs using the three games defined in {@link GameFixtures}.
 */
public final class App {

    private App() {
    }

    public static void main(String[] args) {
        AppProperties props = AppProperties.fromClasspath();

        int cheapsharkTimeout = props.cheapsharkTimeoutSeconds();
        int rawgTimeout = props.rawgTimeoutSeconds();
        int sharedTimeout = Math.max(cheapsharkTimeout, rawgTimeout);
        HttpClient http = HttpClientFactory.create(sharedTimeout);
        HttpFetcher cheapsharkFetcher = new DefaultHttpFetcher(http, cheapsharkTimeout,
                props.cheapsharkRetryMaxAttempts(), props.cheapsharkRetryBaseDelayMillis());
        HttpFetcher rawgFetcher = new DefaultHttpFetcher(http, rawgTimeout,
                props.rawgRetryMaxAttempts(), props.rawgRetryBaseDelayMillis());
        Gson gson = new GsonBuilder().disableHtmlEscaping().create();

        CheapSharkClient client = new CheapSharkClient(cheapsharkFetcher, gson, props.cheapsharkBaseUrl());
        CheapSharkMapper mapper = new CheapSharkMapper();
        RawgClient rawgClient = new RawgClient(rawgFetcher, gson, props.rawgBaseUrl(), props.rawgApiKey());
        RawgMapper rawgMapper = new RawgMapper();
        RawgAggregationService rawgService = new RawgAggregationService(rawgClient, rawgMapper);
        ValidationService validator = new ValidationService();
        GameMerger merger = new GameMerger();

        System.out.println("[smoke] cheapshark.baseUrl=" + props.cheapsharkBaseUrl());
        System.out.println("[smoke] rawg.baseUrl=" + props.rawgBaseUrl());
        System.out.println("[smoke] running with " + GameFixtures.all().size() + " fixture games");

        boolean firebaseReady = new FirebaseConfig(props).initialize();
        System.out.println("[firebase] " + (firebaseReady ? "ready" : "skipped (missing FIREBASE_PROJECT_ID or FIREBASE_CREDENTIALS_PATH)"));

        List<CheapSharkStoreDto> stores = loadStoresOrAbort(client);
        if (stores == null) {
            return;
        }
        System.out.println("[stores] loaded " + stores.size() + " (" + countActive(stores) + " active)");

        GameAggregationService service = new GameAggregationService(client, mapper, stores);

        for (HardcodedGame game : GameFixtures.all()) {
            runCombinedAggregation(service, rawgService, validator, merger, game);
        }

        System.out.println("[smoke] end");
    }

    private static List<CheapSharkStoreDto> loadStoresOrAbort(CheapSharkClient client) {
        try {
            return client.getStores();
        } catch (Exception e) {
            System.out.println("[smoke] ABORT: could not load /stores: " + e.getMessage());
            return null;
        }
    }

    private static void runCombinedAggregation(GameAggregationService csService,
            RawgAggregationService rawgService, ValidationService validator, GameMerger merger,
            HardcodedGame game) {
        System.out.println("--- " + game.name() + " ---");
        GameDeals deals = tryCheapShark(csService, game.name());
        AggregatedGame rawgAgg = tryRawg(rawgService, game.name());
        if (deals == null && rawgAgg == null) {
            System.out.println("  [validation] both sources failed, nothing to validate");
            return;
        }
        AggregatedGame merged = merger.merge(deals, rawgAgg);
        ValidationReport report = validator.evaluate(merged);
        printValidation(report);
    }

    private static GameDeals tryCheapShark(GameAggregationService service, String name) {
        System.out.println("  [cheapshark]");
        try {
            GameDeals deals = service.aggregateByName(name);
            printGameDeals(deals);
            return deals;
        } catch (GameNotFoundException e) {
            System.out.println("    " + e.getMessage());
            return null;
        } catch (Exception e) {
            System.out.println("    ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    private static AggregatedGame tryRawg(RawgAggregationService service, String name) {
        System.out.println("  [rawg]");
        try {
            AggregatedGame agg = service.aggregate(name);
            printRawg(agg);
            return agg;
        } catch (GameNotFoundException e) {
            System.out.println("    " + e.getMessage());
            return null;
        } catch (Exception e) {
            System.out.println("    ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    private static void printGameDeals(GameDeals gd) {
        System.out.println("  [game]   gameId=%s searchTitle=\"%s\" name=\"%s\" internalName=%s".formatted(gd.gameId(),
                gd.searchTitle(), gd.name(), gd.internalName()));
        System.out.println("  [thumb]  %s".formatted(gd.thumb()));
        System.out.println("  [price]  cheapestEver=%s | totalOffers=%d".formatted(gd.cheapestEver(), gd.offerCount()));

        if (gd.bestDeal() != null) {
            Offer b = gd.bestDeal();
            System.out.println("  [best]   %s (id=%s) @ %s (was %s, -%s%%) | icon=%s".formatted(b.storeName(),
                    b.storeId(), b.price(), b.retailPrice(), b.savings(), b.storeIconUrl()));
        }
        if (!gd.offers().isEmpty()) {
            System.out.println("  [other offers] (%d)".formatted(gd.offers().size()));
            for (Offer o : gd.offers()) {
                System.out.println("    - %s (id=%s) @ %s (was %s, -%s%%) | icon=%s".formatted(o.storeName(),
                        o.storeId(), o.price(), o.retailPrice(), o.savings(), o.storeIconUrl()));
            }
        }
    }

    private static void printRawg(AggregatedGame agg) {
        RawgDetails r = agg.rawg();
        System.out.println("  [rawg]   searchTitle=\"%s\" name=\"%s\" slug=%s released=%s".formatted(
                agg.cheapSharkTitle(), r.name(), r.slug(), r.released()));
        System.out.println("  [trailer] %s".formatted(r.trailerUrl() == null ? "(none)" : r.trailerUrl()));
        System.out.println("  [header]  %s".formatted(r.headerImage()));
        if (r.description() != null) {
            String desc = r.description().replaceAll("<[^>]+>", "").trim();
            if (desc.length() > 160) {
                desc = desc.substring(0, 160) + "...";
            }
            System.out.println("  [desc]    %s".formatted(desc));
        }
        System.out.println("  [genres]  %s".formatted(r.genres().size()));
        System.out.println("  [tags]    %s".formatted(r.tags().size()));
        System.out.println("  [platforms] %s".formatted(r.platforms().size()));
        System.out.println("  [dlcs]    %s".formatted(r.dlcs().size()));
        System.out.println("  [creators] %s".formatted(r.creators().size()));
        System.out.println("  [screenshots] %s".formatted(r.screenshots().size()));
        if (!r.dlcs().isEmpty()) {
            for (var d : r.dlcs()) {
                System.out.println("    - dlc: %s (%s) [%s]".formatted(d.name(), d.released(), d.slug()));
            }
        }
        if (!r.creators().isEmpty()) {
            for (var c : r.creators()) {
                System.out.println("    - creator: %s [%s] (%s)".formatted(c.name(), c.slug(), c.position()));
            }
        }
    }

    private static long countActive(List<CheapSharkStoreDto> stores) {
        return stores.stream().filter(s -> s.isActive() == 1).count();
    }

    private static void printValidation(ValidationReport report) {
        System.out.println("  [validation] status=%s missing=%s lastFullFetch=%s".formatted(
                report.status(),
                report.missingFields(),
                report.lastFullFetchAt()));
    }
}
