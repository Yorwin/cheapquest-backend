package com.cheapquest.backend.scripts;

import com.cheapquest.backend.config.AppProperties;
import com.cheapquest.backend.config.FirebaseConfig;
import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.WriteResult;
import com.google.firebase.cloud.FirestoreClient;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-shot data-correction script. Updates the
 * {@code title} field of a single game document so the
 * next hydration pass can find the correct CheapShark
 * catalog entry.
 *
 * <p>Originally the catalog had a doc with slug
 * {@code baldurs-gate-3} but the {@code title} field
 * stored was {@code "Baldur's Gate"} (the 1998 game).
 * The hydration pipeline uses the title to look up the
 * game on CheapShark, so the lookup returned the 1998
 * entry (which has no current offers) and the
 * {@code cheapshark.bestDeal} was left null. Updating
 * the title to {@code "Baldur's Gate 3"} (the 2023
 * game) makes the next hydration succeed.
 *
 * <p>Idempotent: re-running with the correct title is a
 * no-op (the title is already correct).
 */
public final class CorrectGameTitles {

    private static final Logger log = LoggerFactory.getLogger(CorrectGameTitles.class);

    private static final String SLUG = "baldurs-gate-3";
    private static final String CORRECT_TITLE = "Baldur's Gate 3";

    private CorrectGameTitles() {
    }

    public static void main(String[] args) throws Exception {
        AppProperties props = AppProperties.fromClasspath();
        if (!new FirebaseConfig(props).initialize()) {
            log.error("correct_titles_abort reason=firebase_init_failed");
            System.exit(1);
        }
        Firestore firestore = FirestoreClient.getFirestore();

        DocumentReference ref = firestore.collection("games").document(SLUG);
        Map<String, Object> update = new HashMap<>();
        update.put("title", CORRECT_TITLE);
        ApiFuture<WriteResult> future = ref.update(update);
        WriteResult result = future.get();
        log.info("correct_title_ok slug={} newTitle=\"{}\" updateTime={}",
                SLUG, CORRECT_TITLE, result.getUpdateTime());
    }
}
