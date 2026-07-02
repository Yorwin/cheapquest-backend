package com.cheapquest.backend;

import com.cheapquest.backend.client.CheapSharkClient;
import com.cheapquest.backend.client.FirebaseClient;
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
import com.cheapquest.backend.dto.firebase.GameDocumentDto;
import com.cheapquest.backend.endpoint.AdminRefreshEndpoint;
import com.cheapquest.backend.endpoint.HealthEndpoint;
import com.cheapquest.backend.endpoint.HttpServerBootstrap;
import com.cheapquest.backend.endpoint.InMemoryRefreshLock;
import com.cheapquest.backend.endpoint.RefreshLock;
import com.cheapquest.backend.endpoint.RefreshService;
import com.cheapquest.backend.exception.GameNotFoundException;
import com.cheapquest.backend.fixtures.GameFixtures;
import com.cheapquest.backend.fixtures.HardcodedGame;
import com.cheapquest.backend.mapper.CheapSharkMapper;
import com.cheapquest.backend.mapper.FirebaseMapper;
import com.cheapquest.backend.mapper.RawgMapper;
import com.cheapquest.backend.service.GameAggregationService;
import com.cheapquest.backend.service.GameHydrationService;
import com.cheapquest.backend.service.GameLookup;
import com.cheapquest.backend.service.GameLookupService;
import com.cheapquest.backend.service.GameMerger;
import com.cheapquest.backend.service.RawgAggregationService;
import com.cheapquest.backend.service.RefreshPolicy;
import com.cheapquest.backend.service.ValidationConsistencyChecker;
import com.cheapquest.backend.service.ValidationService;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.FirebaseApp;
import com.google.firebase.cloud.FirestoreClient;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point of the backend. Wires up the HTTP layer, the JSON parser, the
 * CheapShark client, the RAWG client, and runs a manual smoke test against
 * the live APIs using the three games defined in {@link GameFixtures}.
 */
public final class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    private App() {
    }

    public static void main(String[] args) {
        String mode = System.getProperty("app.mode", "smoke");
        AppProperties props = AppProperties.fromClasspath();

        int cheapsharkTimeout = props.cheapsharkTimeoutSeconds();
        int rawgTimeout = props.rawgTimeoutSeconds();
        int sharedTimeout = Math.max(cheapsharkTimeout, rawgTimeout);
        HttpClient http = HttpClientFactory.create(sharedTimeout);
        HttpFetcher cheapsharkFetcher = new DefaultHttpFetcher(http, cheapsharkTimeout,
                props.cheapsharkRetryMaxAttempts(), props.cheapsharkRetryBaseDelayMillis());
        HttpFetcher rawgFetcher = new DefaultHttpFetcher(http, rawgTimeout,
                props.rawgRetryMaxAttempts(), props.rawgRetryBaseDelayMillis());
        Gson gson = FirebaseMapper.newGson();

        Clock clock = Clock.systemUTC();

        CheapSharkClient client = new CheapSharkClient(cheapsharkFetcher, gson, props.cheapsharkBaseUrl());
        CheapSharkMapper mapper = new CheapSharkMapper();
        RawgClient rawgClient = new RawgClient(rawgFetcher, gson, props.rawgBaseUrl(), props.rawgApiKey());
        RawgMapper rawgMapper = new RawgMapper();
        RawgAggregationService rawgService = new RawgAggregationService(rawgClient, rawgMapper, clock);
        ValidationService validator = new ValidationService(clock);
        GameMerger merger = new GameMerger(clock);

        log.info("smoke_init source=cheapshark baseUrl={}", props.cheapsharkBaseUrl());
        log.info("smoke_init source=rawg baseUrl={}", props.rawgBaseUrl());
        log.info("smoke_init fixtures={}", GameFixtures.all().size());

        boolean firebaseReady = new FirebaseConfig(props).initialize();
        if (firebaseReady) {
            log.info("firebase_init status=ready");
        } else {
            log.warn("firebase_init status=skipped reason=missing_config");
        }

        FirebaseClient firebaseClient = firebaseReady
                ? new FirebaseClient(FirestoreClient.getFirestore(FirebaseApp.getInstance()), props)
                : null;

        List<CheapSharkStoreDto> stores = loadStoresOrAbort(client);
        if (stores == null) {
            return;
        }
        log.info("stores_loaded total={} active={}", stores.size(), countActive(stores));

        GameAggregationService service = new GameAggregationService(client, mapper, stores, clock);

        if ("bootstrap".equals(mode)) {
            runBootstrap(firebaseClient, new FirebaseMapper(clock));
            log.info("bootstrap_end");
            return;
        }

		if ("hydrate".equals(mode)) {
			GameLookup gameLookup = new GameLookupService(service, rawgService);
			boolean force = Boolean.parseBoolean(System.getProperty("app.refresh.force", "false"));
			RefreshPolicy refreshPolicy = new RefreshPolicy(props, clock);
			if (force) {
				log.info("hydrate_init force=true reason=ignoring_cadence");
			}
			runHydrate(firebaseClient, new FirebaseMapper(clock), gameLookup, validator, merger, refreshPolicy, force, props);
			log.info("hydrate_end");
			return;
		}

		if ("validate".equals(mode)) {
			runValidate(firebaseClient);
			log.info("validate_end");
			return;
		}

		if ("serve".equals(mode)) {
			runServe(firebaseClient, service, rawgService, validator, merger, props, gson, clock);
			return;
		}

        for (HardcodedGame game : GameFixtures.all()) {
            runCombinedAggregation(service, rawgService, validator, merger, game);
        }

        log.info("smoke_end");
    }

    private static void runBootstrap(FirebaseClient firebaseClient, FirebaseMapper firebaseMapper) {
        if (firebaseClient == null) {
            log.warn("bootstrap_abort reason=firebase_not_ready");
            return;
        }
        log.info("bootstrap_start count={}", GameFixtures.all().size());
        int created = 0;
        int skipped = 0;
        int failed = 0;
        for (HardcodedGame game : GameFixtures.all()) {
            String slug = FirebaseMapper.toSlug(game.name());
            GameDocumentDto doc = firebaseMapper.toBootstrapDocument(game.name(), slug);
            try {
                boolean wasCreated = firebaseClient.createIfNotExists(slug, doc);
                if (wasCreated) {
                    log.info("bootstrap_doc_created slug={} title=\"{}\"", slug, game.name());
                    // Freshly bootstrapped docs go straight into the
                    // pending queue so the next cron tick (or the
                    // explicit /admin/refresh call) hydrates them.
                    firebaseClient.addToPending(slug);
                    created++;
                } else {
                    log.info("bootstrap_doc_skipped slug={} reason=already_exists", slug);
                    skipped++;
                }
            } catch (Exception e) {
                log.error("bootstrap_doc_failed slug={} error={}: {}",
                        slug, e.getClass().getSimpleName(), e.getMessage());
                failed++;
            }
        }
        log.info("bootstrap_done created={} skipped={} failed={}", created, skipped, failed);
    }

    private static void runHydrate(FirebaseClient firebaseClient,
            FirebaseMapper firebaseMapper,
            GameLookup gameLookup,
            ValidationService validator, GameMerger merger,
            RefreshPolicy refreshPolicy, boolean force, AppProperties props) {
        if (firebaseClient == null) {
            log.warn("hydrate_abort reason=firebase_not_ready");
            return;
        }
        GameHydrationService hydration = new GameHydrationService(
                firebaseClient, firebaseMapper,
                gameLookup, merger, validator, refreshPolicy, Clock.systemUTC(),
                props.refreshMaxRetries());
        com.cheapquest.backend.dto.HydrationReport report = hydration.hydrateAll(force);
        log.info("hydrate_done processed={} complete={} partial={} empty={} skipped={} "
                        + "failed={} deals_refreshed={} rawg_refreshed={} moved_to_failed={} durationMs={}",
                report.processed(), report.complete(), report.partial(),
                report.empty(), report.skipped(), report.failed(),
                report.dealsRefreshed(), report.rawgRefreshed(),
                report.movedToFailed(), report.durationMs());
        if (!report.failures().isEmpty()) {
            log.warn("hydrate_failures count={} list={}", report.failures().size(), report.failures());
        }
        if (!report.movedToFailedList().isEmpty()) {
            log.warn("hydrate_moved_to_failed count={} list={}",
                    report.movedToFailedList().size(), report.movedToFailedList());
        }
    }

    private static void runValidate(FirebaseClient firebaseClient) {
        if (firebaseClient == null) {
            log.warn("validate_abort reason=firebase_not_ready");
            return;
        }
        ValidationConsistencyChecker checker = new ValidationConsistencyChecker();
        java.util.List<com.cheapquest.backend.dto.firebase.GameDocumentDto> docs = new java.util.ArrayList<>();
        for (var d : firebaseClient.readAll()) {
            docs.add(d);
        }
        log.info("validate_read count={}", docs.size());
        List<ValidationConsistencyChecker.Inconsistency> incs = checker.check(docs);
        int ok = docs.size() - incs.size();
        log.info("validate_done consistent={} inconsistent={}", ok, incs.size());
        for (ValidationConsistencyChecker.Inconsistency inc : incs) {
            log.warn("validate_inconsistent slug={} stored_missing={} actual_missing={} expected_status={} stored_status={}",
                    inc.slug(), inc.storedMissing(), inc.actualMissing(), inc.expectedStatus(), inc.storedStatus());
        }
    }

    private static void runServe(FirebaseClient firebaseClient,
            GameAggregationService service, RawgAggregationService rawgService,
            ValidationService validator, GameMerger merger,
            AppProperties props, Gson gson, Clock clock) {
        if (firebaseClient == null) {
            log.warn("serve_abort reason=firebase_not_ready");
            return;
        }
        GameLookup gameLookup = new GameLookupService(service, rawgService);
        RefreshPolicy refreshPolicy = new RefreshPolicy(props, clock);
        GameHydrationService hydration = new GameHydrationService(
                firebaseClient, new FirebaseMapper(clock),
                gameLookup, merger, validator, refreshPolicy, clock,
                props.refreshMaxRetries());
        RefreshLock lock = new InMemoryRefreshLock();
        RefreshService refreshService = new RefreshService(lock, hydration, clock);

        java.util.Map<String, com.sun.net.httpserver.HttpHandler> routes = new java.util.LinkedHashMap<>();
        routes.put("/health", new HealthEndpoint(clock));
        routes.put("/admin/refresh", new AdminRefreshEndpoint(
                props.adminRefreshToken(), refreshService, gson));

        try {
            com.sun.net.httpserver.HttpServer server = HttpServerBootstrap.start(
                    props.adminRefreshPort(), routes);
            log.info("serve_endless port={} note=block_until_shutdown", props.adminRefreshPort());
            Thread.currentThread().join();
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("serve_failed error={}: {}", e.getClass().getSimpleName(), e.getMessage(), e);
        }
    }

    private static List<CheapSharkStoreDto> loadStoresOrAbort(CheapSharkClient client) {
        try {
            return client.getStores();
        } catch (Exception e) {
            log.warn("smoke_abort reason=stores_load_failed message={}", e.getMessage());
            return null;
        }
    }

    private static void runCombinedAggregation(GameAggregationService csService,
            RawgAggregationService rawgService, ValidationService validator, GameMerger merger,
            HardcodedGame game) {
        log.info("smoke_game_start name=\"{}\"", game.name());
        GameDeals deals = tryCheapShark(csService, game.name());
        AggregatedGame rawgAgg = tryRawg(rawgService, game.name());
        if (deals == null && rawgAgg == null) {
            log.warn("smoke_validation_skipped name=\"{}\" reason=both_sources_failed", game.name());
            return;
        }
        AggregatedGame merged = merger.merge(deals, rawgAgg);
        ValidationReport report = validator.evaluate(merged);
        printSourceFetchedAt(deals, rawgAgg);
        printValidation(report);
    }

    private static GameDeals tryCheapShark(GameAggregationService service, String name) {
        log.info("smoke_source_start name=\"{}\" source=cheapshark", name);
        try {
            GameDeals deals = service.aggregateByName(name);
            printGameDeals(deals);
            return deals;
        } catch (GameNotFoundException e) {
            log.warn("smoke_source_not_found name=\"{}\" source=cheapshark message=\"{}\"", name, e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("smoke_source_error name=\"{}\" source=cheapshark error={}: {}",
                    name, e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    private static AggregatedGame tryRawg(RawgAggregationService service, String name) {
        log.info("smoke_source_start name=\"{}\" source=rawg", name);
        try {
            AggregatedGame agg = service.aggregate(name);
            printRawg(agg);
            return agg;
        } catch (GameNotFoundException e) {
            log.warn("smoke_source_not_found name=\"{}\" source=rawg message=\"{}\"", name, e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("smoke_source_error name=\"{}\" source=rawg error={}: {}",
                    name, e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    private static void printGameDeals(GameDeals gd) {
        log.info("smoke_game gameId={} searchTitle=\"{}\" name=\"{}\" internalName={}",
                gd.gameId(), gd.searchTitle(), gd.name(), gd.internalName());
        log.info("smoke_thumb url={}", gd.thumb());
        log.info("smoke_price cheapestEver={} totalOffers={}", gd.cheapestEver(), gd.offerCount());

        if (gd.bestDeal() != null) {
            Offer b = gd.bestDeal();
            log.info("smoke_best_deal storeName=\"{}\" storeId={} price={} retailPrice={} savings_pct={} storeIconUrl={}",
                    b.storeName(), b.storeId(), b.price(), b.retailPrice(), b.savings(), b.storeIconUrl());
        }
        if (!gd.offers().isEmpty()) {
            log.info("smoke_other_offers count={}", gd.offers().size());
            for (Offer o : gd.offers()) {
                log.info("smoke_offer storeName=\"{}\" storeId={} price={} retailPrice={} savings_pct={} storeIconUrl={}",
                        o.storeName(), o.storeId(), o.price(), o.retailPrice(), o.savings(), o.storeIconUrl());
            }
        }
    }

    private static void printRawg(AggregatedGame agg) {
        RawgDetails r = agg.rawg();
        log.info("smoke_rawg searchTitle=\"{}\" name=\"{}\" slug={} released={}",
                agg.cheapSharkTitle(), r.name(), r.slug(), r.released());
        log.info("smoke_trailer url={}", r.trailerUrl() == null ? "(none)" : r.trailerUrl());
        log.info("smoke_header url={}", r.headerImage());
        if (r.description() != null) {
            String desc = r.description().replaceAll("<[^>]+>", "").trim();
            if (desc.length() > 160) {
                desc = desc.substring(0, 160) + "...";
            }
            log.info("smoke_description text=\"{}\"", desc);
        }
        log.info("smoke_counts genres={} tags={} platforms={} dlcs={} creators={} screenshots={}",
                r.genres().size(), r.tags().size(), r.platforms().size(),
                r.dlcs().size(), r.creators().size(), r.screenshots().size());
        if (!r.dlcs().isEmpty()) {
            for (var d : r.dlcs()) {
                log.info("smoke_dlc name=\"{}\" released={} slug={}", d.name(), d.released(), d.slug());
            }
        }
        if (!r.creators().isEmpty()) {
            for (var c : r.creators()) {
                log.info("smoke_creator name=\"{}\" slug={} position=\"{}\"", c.name(), c.slug(), c.position());
            }
        }
    }

    private static long countActive(List<CheapSharkStoreDto> stores) {
        return stores.stream().filter(s -> s.isActive() == 1).count();
    }

    private static void printValidation(ValidationReport report) {
        log.info("smoke_validation status={} missing_count={} lastFullFetch={}",
                report.status(), report.missingFields().size(), report.lastFullFetchAt());
    }

    private static void printSourceFetchedAt(GameDeals deals, AggregatedGame rawgAgg) {
        Instant now = Instant.now();
        if (deals != null) {
            Duration age = Duration.between(deals.fetchedAt(), now);
            log.info("smoke_fetched_at source=cheapshark at={} age={}", deals.fetchedAt(), age);
        } else {
            log.info("smoke_fetched_at source=cheapshark status=not_fetched");
        }
        if (rawgAgg != null) {
            Duration age = Duration.between(rawgAgg.rawg().fetchedAt(), now);
            log.info("smoke_fetched_at source=rawg at={} age={}", rawgAgg.rawg().fetchedAt(), age);
        } else {
            log.info("smoke_fetched_at source=rawg status=not_fetched");
        }
    }
}
