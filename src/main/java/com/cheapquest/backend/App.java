package com.cheapquest.backend;

import com.cheapquest.backend.client.CheapSharkClient;
import com.cheapquest.backend.config.AppProperties;
import com.cheapquest.backend.config.DefaultHttpFetcher;
import com.cheapquest.backend.config.HttpClientFactory;
import com.cheapquest.backend.config.HttpFetcher;
import com.cheapquest.backend.domain.GameDeals;
import com.cheapquest.backend.domain.Offer;
import com.cheapquest.backend.dto.cheapshark.CheapSharkStoreDto;
import com.cheapquest.backend.exception.GameNotFoundException;
import com.cheapquest.backend.fixtures.GameFixtures;
import com.cheapquest.backend.fixtures.HardcodedGame;
import com.cheapquest.backend.mapper.CheapSharkMapper;
import com.cheapquest.backend.service.GameAggregationService;
import com.google.gson.Gson;
import java.net.http.HttpClient;
import java.util.List;

/**
 * Entry point of the backend. Wires up the HTTP layer, the JSON parser, the
 * CheapShark client, and runs a manual smoke test against the live API using
 * the three games defined in {@link GameFixtures}.
 */
public final class App {

    private App() {
    }

    public static void main(String[] args) {
        AppProperties props = AppProperties.fromClasspath();

        HttpClient http = HttpClientFactory.create(props.cheapsharkTimeoutSeconds());
        HttpFetcher fetcher = new DefaultHttpFetcher(
                http,
                props.cheapsharkTimeoutSeconds(),
                props.cheapsharkRetryMaxAttempts(),
                props.cheapsharkRetryBaseDelayMillis());
        Gson gson = new Gson();

        CheapSharkClient client = new CheapSharkClient(fetcher, gson, props.cheapsharkBaseUrl());
        CheapSharkMapper mapper = new CheapSharkMapper();

        System.out.println("[smoke] baseUrl=" + props.cheapsharkBaseUrl());
        System.out.println("[smoke] running with " + GameFixtures.all().size() + " fixture games");

        List<CheapSharkStoreDto> stores = loadStoresOrAbort(client);
        if (stores == null) {
            return;
        }
        System.out.println("[stores] loaded " + stores.size()
                + " (" + countActive(stores) + " active)");

        GameAggregationService service = new GameAggregationService(client, mapper, stores);

        for (HardcodedGame game : GameFixtures.all()) {
            runOneGame(service, game);
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

    private static void runOneGame(GameAggregationService service, HardcodedGame game) {
        System.out.println("--- " + game.name() + " ---");
        try {
            GameDeals deals = service.aggregateByName(game.name());
            printGameDeals(deals);
        } catch (GameNotFoundException e) {
            System.out.println("  " + e.getMessage());
        } catch (Exception e) {
            System.out.println("  ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static void printGameDeals(GameDeals gd) {
        System.out.println("  [game]   gameId=%s searchTitle=\"%s\" name=\"%s\" internalName=%s"
                .formatted(gd.gameId(), gd.searchTitle(), gd.name(), gd.internalName()));
        System.out.println("  [thumb]  %s".formatted(gd.thumb()));
        System.out.println("  [price]  cheapestEver=%s | totalOffers=%d"
                .formatted(gd.cheapestEver(), gd.offerCount()));

        if (gd.bestDeal() != null) {
            Offer b = gd.bestDeal();
            System.out.println("  [best]   %s (id=%s) @ %s (was %s, -%s%%) | icon=%s"
                    .formatted(b.storeName(), b.storeId(), b.price(),
                            b.retailPrice(), b.savings(), b.storeIconUrl()));
        }
        if (!gd.offers().isEmpty()) {
            System.out.println("  [other offers] (%d)".formatted(gd.offers().size()));
            for (Offer o : gd.offers()) {
                System.out.println("    - %s (id=%s) @ %s (was %s, -%s%%) | icon=%s"
                        .formatted(o.storeName(), o.storeId(), o.price(),
                                o.retailPrice(), o.savings(), o.storeIconUrl()));
            }
        }
    }

    private static long countActive(List<CheapSharkStoreDto> stores) {
        return stores.stream().filter(s -> s.isActive() == 1).count();
    }
}
