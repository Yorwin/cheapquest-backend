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
import com.cheapquest.backend.dto.firebase.ValidationReportDto;
import com.cheapquest.backend.mapper.FirebaseMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates the read-then-enrich-then-write cycle for every
 * game document in Firestore:
 * <ol>
 *   <li>read all docs (or one by slug)</li>
 *   <li>for each: ask {@link RefreshPolicy} which sources are stale</li>
 *   <li>delegate the (subset of) source lookups to {@link GameLookup}</li>
 *   <li>merge and validate</li>
 *   <li>build a partial Firestore patch and {@code update} the
 *       document with the new cheapshark, rawg, locales.en and
 *       validationReport blocks; the {@code lastFullFetchAt} /
 *       {@code lastPartialFetchAt} timestamps in the report are
 *       updated per the cadence decision</li>
 * </ol>
 *
 * <p>If both sources are fresh the doc is left untouched and the
 * outcome is {@link ValidationStatus#SKIPPED}. If both sources fail
 * the report is {@code EMPTY} and the document is also left
 * untouched. A subsequent retry will see the same state and the
 * operator can decide what to do (typically nothing until the
 * upstream APIs are reachable again).
 */
public final class GameHydrationService {

    private static final Logger log = LoggerFactory.getLogger(GameHydrationService.class);

    private final FirebaseClient firebaseClient;
    private final FirebaseMapper firebaseMapper;
    private final GameLookup gameLookup;
    private final GameMerger merger;
    private final ValidationService validator;
    private final RefreshPolicy refreshPolicy;
    private final Clock clock;

    public GameHydrationService(FirebaseClient firebaseClient, FirebaseMapper firebaseMapper,
            GameLookup gameLookup,
            GameMerger merger, ValidationService validator,
            RefreshPolicy refreshPolicy, Clock clock) {
        this.firebaseClient = Objects.requireNonNull(firebaseClient, "firebaseClient");
        this.firebaseMapper = Objects.requireNonNull(firebaseMapper, "firebaseMapper");
        this.gameLookup = Objects.requireNonNull(gameLookup, "gameLookup");
        this.merger = Objects.requireNonNull(merger, "merger");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.refreshPolicy = Objects.requireNonNull(refreshPolicy, "refreshPolicy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public HydrationReport hydrateAll() {
        long start = clock.millis();
        Iterable<GameDocumentDto> docs = firebaseClient.readAll();
        log.info("hydrate_all_start paginating=true");

        int processed = 0;
        int complete = 0;
        int partial = 0;
        int empty = 0;
        int skipped = 0;
        int failed = 0;
        int dealsRefreshed = 0;
        int rawgRefreshed = 0;
        List<String> failures = new ArrayList<>();

        for (GameDocumentDto doc : docs) {
            processed++;
            try {
                HydrationOutcome outcome = hydrateInternal(doc);
                switch (outcome.status()) {
                    case COMPLETE -> complete++;
                    case PARTIAL -> partial++;
                    case EMPTY -> empty++;
                    case SKIPPED -> skipped++;
                }
                if (outcome.dealsRefreshed()) dealsRefreshed++;
                if (outcome.rawgRefreshed()) rawgRefreshed++;
            } catch (RuntimeException e) {
                log.error("hydrate_doc_failed slug={} err={}: {}",
                        doc.slug(), e.getClass().getSimpleName(), e.getMessage());
                failures.add(doc.slug() == null ? "<null-slug>" : doc.slug());
                failed++;
            }
        }

        long durationMs = clock.millis() - start;
        HydrationReport report = new HydrationReport(
                processed, complete, partial, empty, skipped, failed,
                dealsRefreshed, rawgRefreshed, durationMs,
                List.copyOf(failures));
        log.info("hydrate_all_done processed={} complete={} partial={} empty={} skipped={} "
                        + "failed={} deals_refreshed={} rawg_refreshed={} durationMs={}",
                report.processed(), report.complete(), report.partial(),
                report.empty(), report.skipped(), report.failed(),
                report.dealsRefreshed(), report.rawgRefreshed(), report.durationMs());
        return report;
    }

    public boolean hydrateOne(String slug) {
        GameDocumentDto doc = firebaseClient.readOne(slug).orElse(null);
        if (doc == null) {
            log.warn("hydrate_one_missing slug={}", slug);
            return false;
        }
        try {
            HydrationOutcome outcome = hydrateInternal(doc);
            log.info("hydrate_one_done slug={} outcome={} deals_refreshed={} rawg_refreshed={}",
                    slug, outcome.status(), outcome.dealsRefreshed(), outcome.rawgRefreshed());
            return outcome.status() != ValidationStatus.EMPTY;
        } catch (RuntimeException e) {
            log.error("hydrate_one_failed slug={} err={}: {}",
                    slug, e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
    }

    private HydrationOutcome hydrateInternal(GameDocumentDto doc) {
        String slug = doc.slug();
        String title = doc.title();
        if (slug == null || title == null) {
            log.warn("hydrate_skip slug={} reason=missing_slug_or_title", slug);
            return HydrationOutcome.emptyOutcome();
        }

        RefreshPolicy.RefreshDecision decision = refreshPolicy.decide(doc);
        log.info("hydrate_doc_decision slug={} refresh_deals={} refresh_rawg={} full={}",
                slug, decision.refreshDeals(), decision.refreshRawg(), decision.isFullRefresh());

        if (decision.nothingToDo()) {
            log.info("hydrate_doc_skip slug={} reason=fresh", slug);
            return HydrationOutcome.skipped();
        }

        EnumSet<GameLookup.Source> sources = EnumSet.noneOf(GameLookup.Source.class);
        if (decision.refreshDeals()) sources.add(GameLookup.Source.CHEAPSHARK);
        if (decision.refreshRawg()) sources.add(GameLookup.Source.RAWG);
        GameLookup.GameLookupResult result = gameLookup.lookupByTitle(title, sources);
        GameDeals deals = result.deals();
        AggregatedGame rawgAgg = result.rawgAgg();

        if (deals != null) {
            log.info("hydrate_doc_cheapshark slug={} gameId={} offers={}",
                    slug, deals.gameId(), deals.offerCount());
        }
        if (rawgAgg != null && rawgAgg.rawg() != null) {
            RawgDetails rawg = rawgAgg.rawg();
            log.info("hydrate_doc_rawg slug={} name=\"{}\" trailer_present={}",
                    slug, rawg.name(), rawg.trailerUrl() != null);
        }

        AggregatedGame rawgForMerge = rawgAgg != null
                ? rawgAgg
                : new AggregatedGame(title, title, slug, deals, null, Instant.now(clock));
        AggregatedGame merged = merger.merge(deals, rawgForMerge);
        ValidationReport fresh = validator.evaluate(merged);

        if (fresh.status() == ValidationStatus.EMPTY) {
            log.warn("hydrate_doc_skip slug={} reason=both_sources_failed", slug);
            return HydrationOutcome.emptyOutcome();
        }

        ValidationReport composed = composeReport(doc.validationReport(), fresh, decision);
        HydrationPatch patch = firebaseMapper.toHydrationPatch(merged, composed);
        firebaseClient.update(slug, patch);
        log.info("hydrate_doc_ok slug={} status={} missing={} full_refresh={}",
                slug, composed.status(), composed.missingFields().size(), decision.isFullRefresh());
        return new HydrationOutcome(composed.status(), decision.refreshDeals(), decision.refreshRawg());
    }

    /**
     * Apply the per-source cadence to the validation report timestamps.
     * A full refresh (both sources) updates {@code lastFullFetchAt};
     * a partial refresh (only one source) updates
     * {@code lastPartialFetchAt} and preserves the existing
     * {@code lastFullFetchAt}. The previous values come from the
     * document we just read; if they are missing (bootstrap case)
     * we fall back to the current instant so the field is never null.
     */
    private ValidationReport composeReport(
            ValidationReportDto existing, ValidationReport fresh,
            RefreshPolicy.RefreshDecision decision) {
        Instant now = Instant.now(clock);
        Instant previousFull = parseOrNow(existing == null ? null : existing.lastFullFetchAt(), now);
        Instant previousPartial = parseOrNull(existing == null ? null : existing.lastPartialFetchAt());

        if (decision.isFullRefresh()) {
            return new ValidationReport(
                    fresh.status(), fresh.missingFields(),
                    now, previousPartial);
        }
        return new ValidationReport(
                fresh.status(), fresh.missingFields(),
                previousFull, now);
    }

    private static Instant parseOrNow(String iso, Instant fallback) {
        if (iso == null) {
            return fallback;
        }
        try {
            return Instant.parse(iso);
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }

    private static Instant parseOrNull(String iso) {
        if (iso == null) {
            return null;
        }
        try {
            return Instant.parse(iso);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private record HydrationOutcome(ValidationStatus status, boolean dealsRefreshed, boolean rawgRefreshed) {
        static HydrationOutcome emptyOutcome() {
            return new HydrationOutcome(ValidationStatus.EMPTY, false, false);
        }
        static HydrationOutcome skipped() {
            return new HydrationOutcome(ValidationStatus.SKIPPED, false, false);
        }
    }
}
