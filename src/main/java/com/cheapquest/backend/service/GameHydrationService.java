package com.cheapquest.backend.service;

import com.cheapquest.backend.client.FirebaseClient;
import com.cheapquest.backend.domain.AggregatedGame;
import com.cheapquest.backend.domain.GameDeals;
import com.cheapquest.backend.domain.rawg.RawgDetails;
import com.cheapquest.backend.domain.validation.ValidationReport;
import com.cheapquest.backend.domain.validation.ValidationStatus;
import com.cheapquest.backend.dto.HydrationReport;
import com.cheapquest.backend.dto.firebase.GameDocumentDto;
import com.cheapquest.backend.dto.firebase.HydrationPatch;
import com.cheapquest.backend.exception.GameNotFoundException;
import com.cheapquest.backend.mapper.FirebaseMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates the read-then-enrich-then-write cycle for every
 * game document in Firestore:
 * <ol>
 *   <li>read all docs (or one by slug)</li>
 *   <li>for each: run CheapShark and RAWG pipelines</li>
 *   <li>merge and validate</li>
 *   <li>build a partial Firestore patch and {@code update} the
 *       document with the new cheapshark, rawg, locales.en and
 *       validationReport blocks</li>
 * </ol>
 *
 * <p>If both sources fail, the {@code ValidationReport} status
 * is {@code EMPTY} and the document is left untouched (a
 * subsequent retry will see it in the same state, the operator
 * can decide what to do — typically nothing until the upstream
 * APIs are reachable again).
 *
 * <p>The CheapShark and RAWG calls run sequentially in the same
 * thread for now. AGENTS.md §5 contemplates parallelising them
 * once the orchestration moves to a real scheduler; for the
 * smoke-test scale (3 games) sequential is faster and simpler.
 */
public final class GameHydrationService {

    private static final Logger log = LoggerFactory.getLogger(GameHydrationService.class);

    private final FirebaseClient firebaseClient;
    private final FirebaseMapper firebaseMapper;
    private final GameAggregationService csService;
    private final RawgAggregationService rawgService;
    private final GameMerger merger;
    private final ValidationService validator;
    private final Clock clock;

    public GameHydrationService(FirebaseClient firebaseClient, FirebaseMapper firebaseMapper,
            GameAggregationService csService, RawgAggregationService rawgService,
            GameMerger merger, ValidationService validator, Clock clock) {
        this.firebaseClient = Objects.requireNonNull(firebaseClient, "firebaseClient");
        this.firebaseMapper = Objects.requireNonNull(firebaseMapper, "firebaseMapper");
        this.csService = Objects.requireNonNull(csService, "csService");
        this.rawgService = Objects.requireNonNull(rawgService, "rawgService");
        this.merger = Objects.requireNonNull(merger, "merger");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public HydrationReport hydrateAll() {
        long start = clock.millis();
        List<GameDocumentDto> docs = firebaseClient.readAll();
        log.info("hydrate_all_start count={}", docs.size());

        int complete = 0;
        int partial = 0;
        int empty = 0;
        int failed = 0;
        List<String> failures = new ArrayList<>();

        for (GameDocumentDto doc : docs) {
            try {
                ValidationStatus status = hydrateInternal(doc);
                switch (status) {
                    case COMPLETE -> complete++;
                    case PARTIAL -> partial++;
                    case EMPTY -> empty++;
                }
            } catch (RuntimeException e) {
                log.error("hydrate_doc_failed slug={} err={}: {}",
                        doc.slug(), e.getClass().getSimpleName(), e.getMessage());
                failures.add(doc.slug() == null ? "<null-slug>" : doc.slug());
                failed++;
            }
        }

        long durationMs = clock.millis() - start;
        HydrationReport report = new HydrationReport(
                docs.size(), complete, partial, empty, failed, durationMs,
                List.copyOf(failures));
        log.info("hydrate_all_done processed={} complete={} partial={} empty={} failed={} durationMs={}",
                report.processed(), report.complete(), report.partial(),
                report.empty(), report.failed(), report.durationMs());
        return report;
    }

    public boolean hydrateOne(String slug) {
        GameDocumentDto doc = firebaseClient.readOne(slug).orElse(null);
        if (doc == null) {
            log.warn("hydrate_one_missing slug={}", slug);
            return false;
        }
        try {
            ValidationStatus status = hydrateInternal(doc);
            log.info("hydrate_one_done slug={} outcome={}", slug, status);
            return status != ValidationStatus.EMPTY;
        } catch (RuntimeException e) {
            log.error("hydrate_one_failed slug={} err={}: {}",
                    slug, e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
    }

    private ValidationStatus hydrateInternal(GameDocumentDto doc) {
        String slug = doc.slug();
        String title = doc.title();
        if (slug == null || title == null) {
            log.warn("hydrate_skip slug={} reason=missing_slug_or_title", slug);
            return ValidationStatus.EMPTY;
        }

        log.info("hydrate_doc_start slug={} title=\"{}\"", slug, title);
        GameDeals deals = null;
        try {
            deals = csService.aggregateByName(title);
            log.info("hydrate_doc_cheapshark slug={} gameId={} offers={}",
                    slug, deals.gameId(), deals.offerCount());
        } catch (GameNotFoundException e) {
            log.warn("hydrate_doc_cheapshark_not_found slug={}: {}", slug, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("hydrate_doc_cheapshark_failed slug={} err={}: {}",
                    slug, e.getClass().getSimpleName(), e.getMessage());
        }

        RawgDetails rawg = null;
        try {
            AggregatedGame rawgAgg = rawgService.aggregate(title);
            rawg = rawgAgg.rawg();
            log.info("hydrate_doc_rawg slug={} name=\"{}\" trailer_present={}",
                    slug, rawg.name(), rawg.trailerUrl() != null);
        } catch (GameNotFoundException e) {
            log.warn("hydrate_doc_rawg_not_found slug={}: {}", slug, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("hydrate_doc_rawg_failed slug={} err={}: {}",
                    slug, e.getClass().getSimpleName(), e.getMessage());
        }

        AggregatedGame rawgAgg = rawg == null
                ? new AggregatedGame(title, title, slug, deals, null, Instant.now(clock))
                : new AggregatedGame(title, rawg.name(), rawg.slug(), deals, rawg, Instant.now(clock));
        AggregatedGame merged = merger.merge(deals, rawgAgg);
        ValidationReport report = validator.evaluate(merged);

        if (report.status() == ValidationStatus.EMPTY) {
            log.warn("hydrate_doc_skip slug={} reason=both_sources_failed", slug);
            return ValidationStatus.EMPTY;
        }

        HydrationPatch patch = firebaseMapper.toHydrationPatch(merged, report);
        firebaseClient.update(slug, patch);
        log.info("hydrate_doc_ok slug={} status={} missing={}",
                slug, report.status(), report.missingFields().size());
        return report.status();
    }
}
