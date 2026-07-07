package com.cheapquest.backend.scripts;

import com.cheapquest.backend.config.AppProperties;
import com.cheapquest.backend.config.FirebaseConfig;
import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldPath;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.cloud.firestore.WriteResult;
import com.google.firebase.FirebaseApp;
import com.google.firebase.cloud.FirestoreClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-shot operational script that backfills
 * {@code cheapshark.bestDeal.firstSeenAt} on every game
 * document that is missing it, so the "nuevas ofertas"
 * section is meaningful from the first recompute after the
 * field was added to the domain.
 *
 * <p><b>Run it once</b>, after the deploy that introduced
 * the {@code firstSeenAt} field. The script is idempotent:
 * docs that already carry a non-null {@code firstSeenAt}
 * are left untouched, so a second run is a no-op.
 *
 * <h2>What it writes</h2>
 * For each game document where
 * {@code cheapshark.bestDeal.firstSeenAt} is null and
 * {@code cheapshark.bestDeal} is present, the script sets
 * the field to:
 * <ol>
 *   <li>{@code cheapshark.fetchedAt} when present — the
 *       most recent time we observed the current best
 *       deal, which is the strongest "we know this deal
 *       was the best at least up to this date" signal
 *       the persisted data carries;</li>
 *   <li>{@code addedAt} as a fallback — the document was
 *       added to the catalog at this date, which is the
 *       weakest possible signal but still better than
 *       null (which would exclude the game from the
 *       section forever).</li>
 * </ol>
 * Games without a bestDeal are skipped entirely: there
 * is no "new best offer" to surface, so the field is
 * irrelevant.
 *
 * <h2>How to run it</h2>
 * After the deploy, with the standard Firebase env vars
 * set ({@code FIREBASE_PROJECT_ID},
 * {@code FIREBASE_CREDENTIALS_PATH}), invoke from the
 * project root:
 *
 * <pre>
 * mvn -q -DskipTests=true package
 * CP="target/classes:$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout -DincludeScope=runtime)"
 * java -cp "$CP" com.cheapquest.backend.scripts.BackfillFirstSeenAt
 * </pre>
 *
 * <p>Alternatively, add the {@code exec-maven-plugin} and
 * run {@code mvn exec:java -Dexec.mainClass=...}. The
 * script returns exit code {@code 0} on success and
 * {@code 1} on a fatal init failure (Firestore
 * unreachable, Firebase not initialised, etc.). Per-doc
 * errors are logged at WARN and counted in the summary
 * so a single bad doc does not abort the whole run.
 */
public final class BackfillFirstSeenAt {

    private static final Logger log = LoggerFactory.getLogger(BackfillFirstSeenAt.class);

    private static final int PAGE_SIZE = 300;

    private BackfillFirstSeenAt() {
    }

    public static void main(String[] args) {
        AppProperties props = AppProperties.fromClasspath();
        FirebaseConfig config = new FirebaseConfig(props);
        if (!config.initialize()) {
            log.error("backfill_abort reason=firebase_init_failed");
            System.exit(1);
        }
        Firestore firestore = FirestoreClient.getFirestore();
        String gamesPath = props.firestoreCollectionGamesPath();

        log.info("backfill_start collection={} pageSize={}", gamesPath, PAGE_SIZE);
        Summary summary = new Summary();
        Query query = firestore.collection(gamesPath).limit(PAGE_SIZE);
        while (query != null) {
            QuerySnapshot page;
            try {
                page = query.get().get();
            } catch (Exception e) {
                log.error("backfill_page_fetch_failed error={}: {}",
                        e.getClass().getSimpleName(), e.getMessage(), e);
                System.exit(1);
                return;
            }
            List<QueryDocumentSnapshot> docs = page.getDocuments();
            if (docs.isEmpty()) {
                break;
            }
            DocumentSnapshot last = docs.get(docs.size() - 1);
            for (QueryDocumentSnapshot doc : docs) {
                summary.scanned++;
                if (!backfillOne(firestore.collection(gamesPath).document(doc.getId()), doc)) {
                    summary.failed++;
                }
            }
            if (docs.size() < PAGE_SIZE) {
                break;
            }
            query = firestore.collection(gamesPath)
                    .startAfter(last)
                    .limit(PAGE_SIZE);
        }
        log.info("backfill_done scanned={} updated={} skipped_already_set={} failed={}",
                summary.scanned, summary.updated, summary.skipped, summary.failed);
    }

    /**
     * Backfill a single document. Returns {@code true} on
     * success (whether or not an update was needed), so the
     * caller can keep a per-doc failure counter without
     * conflating "did nothing" with "error".
     */
    private static boolean backfillOne(DocumentReference ref, DocumentSnapshot doc) {
        Map<String, Object> cheapshark = readMap(doc, "cheapshark");
        if (cheapshark == null) {
            log.debug("backfill_skip slug={} reason=no_cheapshark", doc.getId());
            return true;
        }
        Object bestObj = cheapshark.get("bestDeal");
        if (!(bestObj instanceof Map<?, ?> best)) {
            log.debug("backfill_skip slug={} reason=no_best_deal", doc.getId());
            return true;
        }
        if (best.get("firstSeenAt") != null) {
            log.debug("backfill_skip slug={} reason=already_set", doc.getId());
            return true;
        }
        String firstSeen = pickFirstSeen(doc, cheapshark);
        try {
            Map<String, Object> update = new HashMap<>();
            update.put(FieldPath.of("cheapshark", "bestDeal", "firstSeenAt").toString(), firstSeen);
            ApiFuture<WriteResult> future = ref.update(update);
            WriteResult result = future.get();
            log.info("backfill_update slug={} firstSeenAt={} updateTime={}",
                    doc.getId(), firstSeen, result.getUpdateTime());
            return true;
        } catch (Exception e) {
            log.warn("backfill_doc_failed slug={} error={}: {}",
                    doc.getId(), e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
    }

    private static String pickFirstSeen(DocumentSnapshot doc, Map<String, Object> cheapshark) {
        Object fetched = cheapshark.get("fetchedAt");
        if (fetched instanceof String s && !s.isBlank()) {
            return s;
        }
        Object added = doc.get("addedAt");
        if (added instanceof String s && !s.isBlank()) {
            return s;
        }
        return "1970-01-01T00:00:00Z";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readMap(DocumentSnapshot doc, String field) {
        Object value = doc.get(field);
        if (value instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return null;
    }

    private static final class Summary {
        int scanned;
        int updated;
        int skipped;
        int failed;
    }
}
