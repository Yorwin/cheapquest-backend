package com.cheapquest.backend.config;

import com.cheapquest.backend.util.StringUtils;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Initialises the Firebase Admin SDK exactly once per process.
 *
 * <p>The init is best-effort: if the credentials path or the project id are
 * missing, the call returns {@code false} and logs a WARN. Other source
 * pipelines (CheapShark, RAWG) keep working. The {@link #isInitialized()}
 * guard makes repeated invocations safe — the second call returns
 * {@code true} without touching the SDK.
 *
 * <p>No Firestore reads or writes are issued from this class. That is the
 * responsibility of the (not yet implemented) {@code FirebaseClient}.
 */
public final class FirebaseConfig {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    private final AppProperties props;

    public FirebaseConfig(AppProperties props) {
        this.props = props;
    }

    public boolean initialize() {
        if (isInitialized()) {
            log.debug("firebase_already_initialized");
            return true;
        }
        String projectId = props.firebaseProjectId();
        if (StringUtils.isBlank(projectId)) {
            log.warn("firebase_init_skipped reason=missing_project_id");
            return false;
        }

        CredentialsSource source = resolveCredentials(props.firebaseCredentialsPath());
        if (source == null) {
            log.warn("firebase_init_skipped reason=no_credentials_resolved");
            return false;
        }

        try {
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(source.credentials())
                    .setProjectId(projectId)
                    .build();
            FirebaseApp.initializeApp(options);
            log.info("firebase_initialized projectId={} source={}", projectId, source.label());
            return true;
        } catch (Exception e) {
            log.error("firebase_init_failed source={} error={}: {}",
                    source.label(), e.getClass().getSimpleName(), e.getMessage(), e);
            return false;
        }
    }

    /**
     * Resolves the credentials in this order:
     * <ol>
     *   <li>If {@code credentialsPath} points to an existing
     *       regular file, load it as a service-account JSON
     *       (dev local: the operator runs the binary with
     *       {@code -e FIREBASE_CREDENTIALS_PATH=...}).</li>
     *   <li>Otherwise, fall back to
     *       {@link GoogleCredentials#getApplicationDefault()},
     *       which reads {@code GOOGLE_APPLICATION_CREDENTIALS}
     *       if present and otherwise consults the metadata
     *       server. Cloud Run injects a service account via
     *       the metadata server when the service is bound
     *       to one in the deploy flags.</li>
     *   <li>Returns {@code null} if both paths fail to
     *       produce credentials (so the caller can log a
     *       clear "init skipped" message).</li>
     * </ol>
     */
    private static CredentialsSource resolveCredentials(String credentialsPath) {
        if (!StringUtils.isBlank(credentialsPath)) {
            Path path = Paths.get(credentialsPath);
            if (Files.isRegularFile(path)) {
                try (InputStream in = Files.newInputStream(path)) {
                    return new CredentialsSource(
                            "file:" + credentialsPath,
                            GoogleCredentials.fromStream(in));
                } catch (Exception e) {
                    log.warn("firebase_credentials_file_read_failed path={} error={}: {}",
                            credentialsPath, e.getClass().getSimpleName(), e.getMessage());
                }
            } else {
                log.info("firebase_credentials_file_not_found path={} trying_adc",
                        credentialsPath);
            }
        }
        try {
            GoogleCredentials adc = GoogleCredentials.getApplicationDefault();
            if (adc != null) {
                return new CredentialsSource("application-default", adc);
            }
        } catch (Exception e) {
            log.warn("firebase_adc_resolve_failed error={}: {}",
                    e.getClass().getSimpleName(), e.getMessage());
        }
        return null;
    }

    private record CredentialsSource(String label, GoogleCredentials credentials) {
    }

    public boolean isInitialized() {
        try {
            FirebaseApp.getInstance();
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }
}
