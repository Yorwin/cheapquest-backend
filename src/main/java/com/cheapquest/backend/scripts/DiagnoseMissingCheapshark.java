package com.cheapquest.backend.scripts;

import com.cheapquest.backend.client.CheapSharkClient;
import com.cheapquest.backend.config.AppProperties;
import com.cheapquest.backend.config.DefaultHttpFetcher;
import com.cheapquest.backend.config.FirebaseConfig;
import com.cheapquest.backend.config.HttpClientFactory;
import com.cheapquest.backend.dto.cheapshark.CheapSharkGameSummaryDto;
import com.cheapquest.backend.mapper.FirebaseMapper;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.cloud.FirestoreClient;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Diagnostic script: for every game in the catalog whose
 * {@code cheapshark.bestDeal} is null, prints the local
 * state (what we have in Firestore) and the CheapShark
 * lookup result (what the upstream API returns). Lets the
 * operator classify each missing case as:
 *
 * <ul>
 *   <li><b>lookup-miss</b>: the game is not in CheapShark's
 *       catalog under any reasonable title variant. No
 *       fix possible at the API level; the section will
 *       never surface this game.</li>
 *   <li><b>title-mismatch</b>: the game is in CheapShark
 *       but under a different title than what we have.
 *       Fix: re-ingest with a corrected title (or open a
 *       ticket to investigate the upstream naming).</li>
 *   <li><b>hydration-failed</b>: the game IS in CheapShark
 *       under our title, the prior hydration just failed.
 *       Fix: re-enqueue and re-run /admin/refresh.</li>
 *   <li><b>hydration-empty</b>: the game is in CheapShark
 *       with offers, but the persisted block has bestDeal=null
 *       for some other reason (e.g. the prior run errored
 *       mid-write). Fix: re-enqueue.</li>
 * </ul>
 *
 * <p>Read-only. Never writes to Firestore.
 */
public final class DiagnoseMissingCheapshark {

    private static final Logger log = LoggerFactory.getLogger(DiagnoseMissingCheapshark.class);

    private static final List<String> SLUGS = List.of(
            "baldurs-gate-3",
            "grand-theft-auto-v",
            "meccha-chameleon",
            "resident-evil-requiem");

    private DiagnoseMissingCheapshark() {
    }

    public static void main(String[] args) throws Exception {
        AppProperties props = AppProperties.fromClasspath();
        if (!new FirebaseConfig(props).initialize()) {
            log.error("diagnose_abort reason=firebase_init_failed");
            System.exit(1);
        }
        Firestore firestore = FirestoreClient.getFirestore();
        CheapSharkClient cheapshark = new CheapSharkClient(
                new DefaultHttpFetcher(
                        HttpClientFactory.create(props.cheapsharkTimeoutSeconds()),
                        props.cheapsharkTimeoutSeconds(),
                        props.cheapsharkRetryMaxAttempts(),
                        props.cheapsharkRetryBaseDelayMillis()),
                FirebaseMapper.newGson(),
                props.cheapsharkBaseUrl());

        for (String slug : SLUGS) {
            diagnose(firestore, cheapshark, slug);
        }
    }

    @SuppressWarnings("unchecked")
    private static void diagnose(Firestore firestore, CheapSharkClient cheapshark, String slug)
            throws Exception {
        log.info("=== diagnose slug={} ===", slug);
        DocumentSnapshot doc = firestore.collection("games").document(slug).get().get();
        if (!doc.exists()) {
            log.warn("  game_doc_missing");
            return;
        }
        String title = doc.getString("title");
        log.info("  title={}", title);
        log.info("  addedAt={}", doc.getString("addedAt"));
        Map<String, Object> cheapsharkBlock = (Map<String, Object>) doc.get("cheapshark");
        if (cheapsharkBlock == null) {
            log.warn("  cheapshark=ABSENT");
        } else {
            log.info("  cheapshark.synced={}", cheapsharkBlock.get("synced"));
            log.info("  cheapshark.gameId={}", cheapsharkBlock.get("gameId"));
            log.info("  cheapshark.cheapestEver={}", cheapsharkBlock.get("cheapestEver"));
            log.info("  cheapshark.offerCount={}", cheapsharkBlock.get("offerCount"));
            log.info("  cheapshark.fetchedAt={}", cheapsharkBlock.get("fetchedAt"));
            log.info("  cheapshark.deals={}", cheapsharkBlock.get("deals"));
            log.info("  cheapshark.bestDeal={}", cheapsharkBlock.get("bestDeal"));
        }
        Map<String, Object> report = (Map<String, Object>) doc.get("validationReport");
        if (report != null) {
            log.info("  validationReport={}", report);
        }
        if (title == null) {
            log.warn("  skip_cheapshark_probe reason=no_title");
            return;
        }
        try {
            List<CheapSharkGameSummaryDto> matches = cheapshark.findByTitle(title);
            if (matches.isEmpty()) {
                log.warn("  cheapshark_probe=NO_MATCHES (game likely not in CheapShark catalog)");
            } else {
                log.info("  cheapshark_probe matches={}", matches.size());
                for (int i = 0; i < Math.min(3, matches.size()); i++) {
                    CheapSharkGameSummaryDto m = matches.get(i);
                    log.info("    [{}] external='{}' internalName={} gameId={}",
                            i, m.external(), m.internalName(), m.gameId());
                }
            }
        } catch (Exception e) {
            log.warn("  cheapshark_probe=FAILED error={}: {}",
                    e.getClass().getSimpleName(), e.getMessage());
        }
    }
}
