package com.cheapquest.backend.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import com.cheapquest.backend.domain.sections.SectionName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AppProperties {

    private static final Logger log = LoggerFactory.getLogger(AppProperties.class);
    private static final String DEFAULT_FILE = "application.properties";
    private static final String TEST_FILE = "application-test.properties";

    private final Properties props;

    private AppProperties(Properties props) {
        this.props = props;
    }

    public static AppProperties fromClasspath() {
        return fromClasspath(DEFAULT_FILE);
    }

    public static AppProperties fromClasspathForTests() {
        return fromClasspath(TEST_FILE);
    }

    public static AppProperties fromClasspath(String resourceName) {
        Properties p = new Properties();
        try (InputStream in = AppProperties.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (in == null) {
                throw new IllegalStateException("Classpath resource not found: " + resourceName);
            }
            p.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + resourceName, e);
        }
        resolvePlaceholders(p);
        return new AppProperties(p);
    }

    private static void resolvePlaceholders(Properties p) {
        for (String key : p.stringPropertyNames()) {
            String value = p.getProperty(key);
            String resolved = resolve(value);
            if (resolved != value) {
                p.setProperty(key, resolved);
            }
        }
    }

    private static String resolve(String value) {
        int start = value.indexOf("${");
        if (start < 0) {
            return value;
        }
        int end = value.indexOf('}', start);
        if (end < 0) {
            return value;
        }
        String varName = value.substring(start + 2, end);
        String envValue = System.getenv(varName);
        if (envValue != null) {
            log.debug("config_resolved var={} from=env", varName);
            return envValue;
        }
        return value;
    }

    public String cheapsharkBaseUrl() {
        return props.getProperty("cheapshark.base-url");
    }

    public int cheapsharkTimeoutSeconds() {
        return Integer.parseInt(props.getProperty("cheapshark.timeout-seconds", "10"));
    }

    public int cheapsharkRetryMaxAttempts() {
        return Integer.parseInt(props.getProperty("cheapshark.retry.max-attempts", "3"));
    }

    public long cheapsharkRetryBaseDelayMillis() {
        return Long.parseLong(props.getProperty("cheapshark.retry.base-delay-millis", "1000"));
    }

    public String rawgBaseUrl() {
        return props.getProperty("rawg.base-url");
    }

    public String rawgApiKey() {
        return props.getProperty("rawg.api-key");
    }

    public int rawgTimeoutSeconds() {
        return Integer.parseInt(props.getProperty("rawg.timeout-seconds", "10"));
    }

    public int rawgRetryMaxAttempts() {
        return Integer.parseInt(props.getProperty("rawg.retry.max-attempts", "3"));
    }

    public long rawgRetryBaseDelayMillis() {
        return Long.parseLong(props.getProperty("rawg.retry.base-delay-millis", "1000"));
    }

    public String deeplBaseUrl() {
        return props.getProperty("deepl.base-url");
    }

    public String deeplApiKey() {
        return props.getProperty("deepl.api-key");
    }

    public int deeplBatchSize() {
        return Integer.parseInt(props.getProperty("deepl.batch-size", "50"));
    }

    public String firebaseProjectId() {
        return props.getProperty("firebase.project-id");
    }

    public String firebaseCredentialsPath() {
        // Resolution order: env FIREBASE_CREDENTIALS_PATH
        // (so dev local can point at a real file without
        // rebuilding application.properties) -> the property
        // value (which carries the Cloud Run default
        // /var/secrets/firebase/credentials.json) -> null.
        // We re-read the env here on purpose: the resolver
        // in resolvePlaceholders only consults the env when
        // the property uses a ${...} placeholder, and the
        // default we ship in application.properties is a
        // literal path. Without this method-level check the
        // dev local could not override the Cloud Run default.
        String env = System.getenv("FIREBASE_CREDENTIALS_PATH");
        if (env != null && !env.isBlank()) {
            return env;
        }
        return props.getProperty("firebase.credentials.path");
    }

    public String firestoreCollectionGamesPath() {
        return props.getProperty("firestore.collection.games-path", "games");
    }

    public String firestoreCollectionPendingPath() {
        return props.getProperty("firestore.collection.pending-path", "pending");
    }

    public String firestoreCollectionFailedPath() {
        return props.getProperty("firestore.collection.failed-path", "failed");
    }

    public String firestoreCollectionTranslationPendingPath() {
        return props.getProperty("firestore.collection.translation-pending-path",
                "translations-pending");
    }

    public String firestoreCollectionTranslationFailedPath() {
        return props.getProperty("firestore.collection.translation-failed-path",
                "translations-failed");
    }

    public String firestoreCollectionSectionsPath() {
        return props.getProperty("firestore.collection.sections-path", "sections");
    }

    public int firestoreReadPageSize() {
        return Integer.parseInt(props.getProperty("firestore.read-page-size", "300"));
    }

    public String adminRefreshToken() {
        return props.getProperty("admin.refresh.token");
    }

    public int adminRefreshPort() {
        return Integer.parseInt(props.getProperty("admin.refresh.port", "8080"));
    }

    /**
     * Port the {@code serve} mode binds the {@code HttpServer}
     * to. Resolution order: {@code PORT} env var (the
     * Cloud Run contract — GCP injects it at container
     * start) → {@code admin.refresh.port} property → 8080.
     * Reading {@code PORT} explicitly avoids the silent
     * failure mode where someone changes the GCP-side port
     * and the JVM keeps listening on the property default.
     */
    public int effectivePort() {
        String env = System.getenv("PORT");
        if (env != null && !env.isBlank()) {
            try {
                int parsed = Integer.parseInt(env.trim());
                if (parsed > 0 && parsed < 65536) {
                    return parsed;
                }
                log.warn("app_properties_invalid_port_env value={} falling_back_to_property", env);
            } catch (NumberFormatException e) {
                log.warn("app_properties_unparseable_port_env value={} falling_back_to_property", env);
            }
        }
        return adminRefreshPort();
    }

    public int adminRefreshIntervalHours() {
        return Integer.parseInt(props.getProperty("admin.refresh.interval-hours", "6"));
    }

    public int adminRefreshLockTtlMinutes() {
        return Integer.parseInt(props.getProperty("admin.refresh.lock-ttl-minutes", "10"));
    }

    public String supportedLanguages() {
        return props.getProperty("supported.languages", "es,en,fr");
    }

    public int translationCacheTtlDays() {
        return Integer.parseInt(props.getProperty("translation.cache-ttl-days", "30"));
    }

    public String translationDefaultSource() {
        return props.getProperty("translation.default-source", "en");
    }

    public int refreshMaxRetries() {
        return Integer.parseInt(props.getProperty("refresh.max-retries", "3"));
    }

    public int refreshFailedCooldownDays() {
        return Integer.parseInt(props.getProperty("refresh.failed-cooldown-days", "7"));
    }

    public int refreshDealsMaxAgeHours() {
        return Integer.parseInt(props.getProperty("refresh.deals-max-age-hours", "24"));
    }

    public int refreshRawgMaxAgeDays() {
        return Integer.parseInt(props.getProperty("refresh.rawg-max-age-days", "180"));
    }

    /**
     * Max items kept in the section snapshot for the given
     * section. The default values match the per-section
     * quotas from AGENTS.md (populares=11, nuevas=8,
     * vintage=8, mejores-promos=5, bajos-historicos=5);
     * each can be overridden by a {@code sections.max-items.<slug>}
     * property in {@code application.properties}.
     */
    public int sectionsMaxItems(SectionName name) {
        int fallback = switch (name) {
            case POPULARES -> 11;
            case NUEVAS_OFERTAS -> 8;
            case VINTAGE -> 8;
            case MEJORES_PROMOS -> 5;
            case BAJOS_HISTORICOS -> 5;
        };
        return Integer.parseInt(
                props.getProperty("sections.max-items." + name.slug(),
                        Integer.toString(fallback)));
    }

    /**
     * How old a {@code bestDeal.firstSeenAt} can be for the
     * game to qualify for the "nuevas ofertas" section.
     * A deal that became the best more than this many days
     * ago is no longer "new" from the product's point of
     * view. Default 2 days matches the daily hydration
     * cadence: any deal that became the best in the most
     * recent refresh (and possibly the one before) is
     * considered fresh.
     */
    public int sectionsNewOffersWindowDays() {
        return Integer.parseInt(
                props.getProperty("sections.new-offers.window-days", "2"));
    }

    /**
     * How old a pending entry's {@code lastAttemptAt} can be
     * before the startup recovery resets its attempt counter.
     * Prevents chronic false-failures after a JVM crash: an entry
     * that was mid-flight when the JVM died is reprocessed with
     * a clean slate once the threshold elapses, not stuck
     * counting toward a fake third strike.
     */
    public int pendingStaleThresholdMinutes() {
        return Integer.parseInt(props.getProperty("app.pending.stale-threshold-minutes", "60"));
    }
}
