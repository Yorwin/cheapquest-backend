package com.cheapquest.backend.scripts;

import com.cheapquest.backend.config.AppProperties;
import com.cheapquest.backend.config.FirebaseConfig;
import com.cheapquest.backend.dto.firebase.PendingDoc;
import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.WriteResult;
import com.google.firebase.cloud.FirestoreClient;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-shot re-enqueue: writes a {@code pending/{slug}} doc
 * with the same shape that {@code GameIngestService}
 * produces. Used as a workaround for cases where the
 * HTTP ingest endpoint is broken (e.g. a classpath issue
 * in the shaded JAR) and we still need the hydration
 * pipeline to re-pick a game up.
 *
 * <p>Idempotent: re-running replaces the pending entry
 * with a fresh zero-attempts one. The hydration pipeline
 * will pick it up on the next /admin/refresh call.
 *
 * <p>Run after a {@code CorrectGameTitles} step so the
 * hydration sees the corrected title in the game doc.
 */
public final class ReenqueueGame {

    private static final Logger log = LoggerFactory.getLogger(ReenqueueGame.class);

    private static final String SLUG = "baldurs-gate-3";

    private ReenqueueGame() {
    }

    public static void main(String[] args) throws ExecutionException, InterruptedException {
        AppProperties props = AppProperties.fromClasspath();
        if (!new FirebaseConfig(props).initialize()) {
            log.error("reenqueue_abort reason=firebase_init_failed");
            System.exit(1);
        }
        Firestore firestore = FirestoreClient.getFirestore();
        String pendingPath = props.firestoreCollectionPendingPath();

        DocumentReference ref = firestore.collection(pendingPath).document(SLUG);
        DocumentSnapshot existing = ref.get().get();
        if (existing.exists()) {
            log.info("reenqueue_overwrite slug={} previousAttempts={}",
                    SLUG, existing.getLong("attempts"));
        }
        PendingDoc pending = new PendingDoc(SLUG, 0, null, null);
        ApiFuture<WriteResult> future = ref.set(pending);
        WriteResult result = future.get();
        log.info("reenqueue_ok slug={} pendingPath={} updateTime={}",
                SLUG, pendingPath, result.getUpdateTime());
    }
}
