package com.cheapquest.backend.scripts;

import com.cheapquest.backend.client.FirestoreRetrier;
import com.cheapquest.backend.config.AppProperties;
import com.cheapquest.backend.config.FirebaseConfig;
import com.cheapquest.backend.config.HttpClientFactory;
import com.cheapquest.backend.dao.GameDao;
import com.cheapquest.backend.dao.firestore.FirestoreGameDao;
import com.cheapquest.backend.domain.sections.GameView;
import com.cheapquest.backend.domain.sections.SectionName;
import com.cheapquest.backend.mapper.GameViewMapper;
import com.cheapquest.backend.mapper.PublicSectionMapper;
import com.cheapquest.backend.mapper.SectionSnapshotMapper;
import com.cheapquest.backend.service.sections.BuildResult;
import com.cheapquest.backend.service.sections.FirestoreSectionStore;
import com.cheapquest.backend.service.sections.InMemorySectionsLock;
import com.cheapquest.backend.service.sections.SectionBuilder;
import com.cheapquest.backend.service.sections.SectionsLock;
import com.cheapquest.backend.service.sections.SectionsService;
import com.cheapquest.backend.service.sections.SectionStore;
import com.cheapquest.backend.service.sections.builders.BajosHistoricosBuilder;
import com.cheapquest.backend.service.sections.builders.MejoresPromosBuilder;
import com.cheapquest.backend.service.sections.builders.NuevasOfertasBuilder;
import com.cheapquest.backend.service.sections.builders.PopularesBuilder;
import com.cheapquest.backend.service.sections.builders.VintageBuilder;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.FirebaseApp;
import com.google.firebase.cloud.FirestoreClient;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-shot recompute of the five section snapshots. Wires
 * up the same dependency graph that {@code App.runServe}
 * builds for the HTTP server, then calls
 * {@link SectionsService#recomputeAll()} and prints a
 * one-line summary per section.
 *
 * <p>Workaround for the case where the HTTP admin endpoint
 * {@code POST /admin/sections} is unreachable (e.g. the
 * shaded JAR is missing a logback class on the
 * http-handler thread) but the Firestore writer is still
 * healthy. Bypasses the HTTP layer entirely so the cron
 * 00:00 UTC daily recompute can still run on the operator's
 * machine when the server is degraded.
 */
public final class ComputeSections {

    private static final Logger log = LoggerFactory.getLogger(ComputeSections.class);

    private ComputeSections() {
    }

    public static void main(String[] args) {
        AppProperties props = AppProperties.fromClasspath();
        if (!new FirebaseConfig(props).initialize()) {
            log.error("compute_sections_abort reason=firebase_init_failed");
            System.exit(1);
        }

        Clock clock = Clock.systemUTC();
        HttpClient http = HttpClientFactory.create(Math.max(
                props.cheapsharkTimeoutSeconds(), props.rawgTimeoutSeconds()));

        SectionSnapshotMapper sectionSnapshotMapper = new SectionSnapshotMapper();
        GameViewMapper gameViewMapper = new GameViewMapper();
        PublicSectionMapper publicSectionMapper = new PublicSectionMapper();

        Firestore firestore = FirestoreClient.getFirestore(FirebaseApp.getInstance());
        SectionStore sectionStore = new FirestoreSectionStore(
                firestore,
                props.firestoreCollectionSectionsPath(),
                sectionSnapshotMapper);
        SectionsLock sectionsLock = new InMemorySectionsLock();

        List<SectionBuilder> sectionBuilders = List.of(
                new MejoresPromosBuilder(
                        props.sectionsMaxItems(SectionName.MEJORES_PROMOS)),
                new VintageBuilder(
                        props.sectionsMaxItems(SectionName.VINTAGE), clock),
                new BajosHistoricosBuilder(
                        props.sectionsMaxItems(SectionName.BAJOS_HISTORICOS)),
                new PopularesBuilder(
                        props.sectionsMaxItems(SectionName.POPULARES)),
                new NuevasOfertasBuilder(
                        props.sectionsMaxItems(SectionName.NUEVAS_OFERTAS),
                        props.sectionsNewOffersWindowDays(), clock));

        GameDao gameDao = new FirestoreGameDao(
                firestore, props.firestoreCollectionGamesPath(),
                props.firestoreReadPageSize(), new FirestoreRetrier());

        Supplier<List<GameView>> catalogSupplier =
                () -> gameViewMapper.toGameViews(gameDao.readAll());

        SectionsService sectionsService = new SectionsService(
                sectionStore, sectionsLock, sectionBuilders,
                catalogSupplier, clock);

        log.info("compute_sections_start sections={}",
                sectionBuilders.stream().map(b -> b.name().slug()).toList());
        long start = System.nanoTime();
        List<SectionsService.Report> reports = sectionsService.recomputeAll();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        int completed = 0;
        int failed = 0;
        for (SectionsService.Report r : reports) {
            switch (r.status()) {
                case COMPLETED -> {
                    completed++;
                    log.info("compute_sections_section name={} status=COMPLETED totalCandidates={} itemsKept={} durationMs={}",
                            r.name().slug(), r.totalCandidates(), r.itemsKept(), r.durationMs());
                }
                case SKIPPED_NO_BUILDER -> log.info("compute_sections_section name={} status=SKIPPED_NO_BUILDER durationMs={}",
                            r.name().slug(), r.durationMs());
                case FAILED -> {
                    failed++;
                    log.error("compute_sections_section name={} status=FAILED durationMs={} error={}",
                            r.name().slug(), r.durationMs(), r.error());
                }
            }
        }
        log.info("compute_sections_done completed={} failed={} totalDurationMs={}",
                completed, failed, elapsedMs);
        if (failed > 0) {
            System.exit(2);
        }
    }
}
