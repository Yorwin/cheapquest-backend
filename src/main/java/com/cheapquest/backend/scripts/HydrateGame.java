package com.cheapquest.backend.scripts;

import com.cheapquest.backend.client.CheapSharkClient;
import com.cheapquest.backend.client.FirestoreRetrier;
import com.cheapquest.backend.client.RawgClient;
import com.cheapquest.backend.dao.GameDao;
import com.cheapquest.backend.dao.HydrationQueueDao;
import com.cheapquest.backend.dao.firestore.FirestoreGameDao;
import com.cheapquest.backend.dao.firestore.FirestoreHydrationQueueDao;
import com.cheapquest.backend.config.AppProperties;
import com.cheapquest.backend.config.DefaultHttpFetcher;
import com.cheapquest.backend.config.FirebaseConfig;
import com.cheapquest.backend.config.HttpClientFactory;
import com.cheapquest.backend.domain.AggregatedGame;
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
import com.cheapquest.backend.service.ValidationService;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.FirebaseApp;
import com.google.firebase.cloud.FirestoreClient;
import com.google.gson.Gson;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-shot re-hydration script. Wires up the same
 * dependency graph that {@code App.runServe} builds for
 * the HTTP server, then calls
 * {@link GameHydrationService#hydrateOne(String, boolean)}
 * for a single slug with {@code force=true} so the
 * per-source cadence is bypassed.
 *
 * <p>Workaround for the case where the HTTP admin
 * endpoints are unreachable (e.g. the shaded JAR is
 * missing a logback class on the http-handler thread,
 * or any other transient issue that prevents
 * {@code POST /admin/refresh} from succeeding) but the
 * main pipeline is still healthy. Bypasses the HTTP
 * layer entirely.
 */
public final class HydrateGame {

    private static final Logger log = LoggerFactory.getLogger(HydrateGame.class);

    private HydrateGame() {
    }

    public static void main(String[] args) {
        AppProperties props = AppProperties.fromClasspath();
        if (!new FirebaseConfig(props).initialize()) {
            log.error("hydrate_game_abort reason=firebase_init_failed");
            System.exit(1);
        }

        Clock clock = Clock.systemUTC();
        int cheapTimeout = props.cheapsharkTimeoutSeconds();
        int rawgTimeout = props.rawgTimeoutSeconds();
        int sharedTimeout = Math.max(cheapTimeout, rawgTimeout);
        HttpClient http = HttpClientFactory.create(sharedTimeout);
        Gson gson = FirebaseMapper.newGson();

        CheapSharkClient cheapshark = new CheapSharkClient(
                new DefaultHttpFetcher(http, cheapTimeout,
                        props.cheapsharkRetryMaxAttempts(), props.cheapsharkRetryBaseDelayMillis()),
                gson, props.cheapsharkBaseUrl());
        RawgClient rawgClient = new RawgClient(
                new DefaultHttpFetcher(http, rawgTimeout,
                        props.rawgRetryMaxAttempts(), props.rawgRetryBaseDelayMillis()),
                gson, props.rawgBaseUrl(), props.rawgApiKey());

        try {
            log.info("hydrate_game_loading_stores");
            cheapshark.getStores();
        } catch (Exception e) {
            log.error("hydrate_game_abort reason=stores_load_failed error={}",
                    e.getMessage());
            System.exit(1);
        }

        CheapSharkMapper csMapper = new CheapSharkMapper();
        GameAggregationService service = new GameAggregationService(
                cheapshark, csMapper, List.of(), clock);
        RawgMapper rawgMapper = new RawgMapper();
        RawgAggregationService rawgService = new RawgAggregationService(rawgClient, rawgMapper, clock);
        GameMerger merger = new GameMerger(clock);
        ValidationService validator = new ValidationService(clock);
        RefreshPolicy refreshPolicy = new RefreshPolicy(props, clock);

        Firestore firestore = FirestoreClient.getFirestore(FirebaseApp.getInstance());
        FirestoreRetrier retrier = new FirestoreRetrier();
        GameDao gameDao = new FirestoreGameDao(
                firestore, props.firestoreCollectionGamesPath(),
                props.firestoreReadPageSize(), retrier);
        HydrationQueueDao hydrationQueueDao = new FirestoreHydrationQueueDao(
                firestore, props.firestoreCollectionPendingPath(),
                props.firestoreCollectionFailedPath(), retrier);
        FirebaseMapper firebaseMapper = new FirebaseMapper(clock);

        GameLookup gameLookup = new GameLookupService(service, rawgService);
        GameHydrationService hydration = new GameHydrationService(
                gameDao, hydrationQueueDao, firebaseMapper, gameLookup, merger,
                validator, refreshPolicy, clock, props.refreshMaxRetries());

        String slug = args.length > 0 ? args[0] : "baldurs-gate-3";
        boolean force = args.length <= 1 || !"false".equalsIgnoreCase(args[1]);
        log.info("hydrate_game_start slug={} force={}", slug, force);
        boolean ok = hydration.hydrateOne(slug, force);
        log.info("hydrate_game_done slug={} ok={}", slug, ok);
        if (!ok) {
            System.exit(2);
        }
    }
}
