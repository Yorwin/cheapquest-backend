package com.cheapquest.backend.config;

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
        String credentialsPath = props.firebaseCredentialsPath();
        String projectId = props.firebaseProjectId();
        if (isBlank(credentialsPath) || isBlank(projectId)) {
            log.warn("firebase_init_skipped reason=missing_config projectId_present={} credentials_path_present={}",
                    !isBlank(projectId), !isBlank(credentialsPath));
            return false;
        }
        Path path = Paths.get(credentialsPath);
        if (!Files.isRegularFile(path)) {
            log.warn("firebase_init_skipped reason=credentials_file_not_found path={}", credentialsPath);
            return false;
        }
        try (InputStream in = Files.newInputStream(path)) {
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(in))
                    .setProjectId(projectId)
                    .build();
            FirebaseApp.initializeApp(options);
            log.info("firebase_initialized projectId={}", projectId);
            return true;
        } catch (Exception e) {
            log.error("firebase_init_failed path={} error={}: {}",
                    credentialsPath, e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
    }

    public boolean isInitialized() {
        try {
            FirebaseApp.getInstance();
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
