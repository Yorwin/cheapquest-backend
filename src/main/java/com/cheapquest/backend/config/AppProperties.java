package com.cheapquest.backend.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
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
        return props.getProperty("firebase.credentials.path");
    }

    public String adminRefreshToken() {
        return props.getProperty("admin.refresh.token");
    }

    public int adminRefreshPort() {
        return Integer.parseInt(props.getProperty("admin.refresh.port", "8080"));
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
}
