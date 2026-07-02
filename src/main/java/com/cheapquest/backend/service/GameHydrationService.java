package com.cheapquest.backend.service;

import com.cheapquest.backend.client.FirebaseClient;
import com.cheapquest.backend.domain.AggregatedGame;
import com.cheapquest.backend.domain.GameDeals;
import com.cheapquest.backend.domain.rawg.RawgDetails;
import com.cheapquest.backend.domain.validation.GameField;
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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
        return hydrateAll(false);
    }

    /**
     * Run the hydration pipeline over every game document. When
     * {@code force} is {@code true} the per-source cadence is
     * bypassed and every doc is fully re-fetched; when false the
     * per-source cadence decides which sources to refresh per doc.
     * See {@link RefreshPolicy#decide(GameDocumentDto, boolean)}.
     */
    public HydrationReport hydrateAll(boolean force) {
        long start = clock.millis();
        Iterable<GameDocumentDto> docs = firebaseClient.readAll();
        log.info("hydrate_all_start paginating=true force={}", force);

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
                HydrationOutcome outcome = hydrateInternal(doc, force);
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
        return hydrateOne(slug, false);
    }

    /**
     * Hydrate a single game document by its slug. When
     * {@code force} is {@code true} the per-source cadence is
     * bypassed; when false the cadence is consulted.
     */
    public boolean hydrateOne(String slug, boolean force) {
        GameDocumentDto doc = firebaseClient.readOne(slug).orElse(null);
        if (doc == null) {
            log.warn("hydrate_one_missing slug={}", slug);
            return false;
        }
        try {
            HydrationOutcome outcome = hydrateInternal(doc, force);
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
        return hydrateInternal(doc, false);
    }

    private HydrationOutcome hydrateInternal(GameDocumentDto doc, boolean force) {
        String slug = doc.slug();
        String title = doc.title();
        if (slug == null || title == null) {
            log.warn("hydrate_skip slug={} reason=missing_slug_or_title", slug);
            return HydrationOutcome.emptyOutcome();
        }

        RefreshPolicy.RefreshDecision decision = refreshPolicy.decide(doc, force);
        log.info("hydrate_doc_decision slug={} refresh_deals={} refresh_rawg={} full={} force={}",
                slug, decision.refreshDeals(), decision.refreshRawg(),
                decision.isFullRefresh(), force);

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
        HydrationPatch patch = firebaseMapper.toHydrationPatch(
                merged, composed, decision.refreshDeals(), decision.refreshRawg());
        firebaseClient.update(slug, patch);
        log.info("hydrate_doc_ok slug={} status={} missing={} full_refresh={}",
                slug, composed.status(), composed.missingFields().size(), decision.isFullRefresh());
        return new HydrationOutcome(composed.status(), decision.refreshDeals(), decision.refreshRawg());
    }

    /**
     * Apply the per-source cadence to the validation report. A full
     * refresh (both sources) uses the fresh evaluation as-is and
     * updates {@code lastFullFetchAt}. A partial refresh (one
     * source) merges the fresh evaluation with the existing report:
     * <ul>
     *   <li>fields belonging to a refreshed source are taken from
     *       the fresh evaluation - that source just looked at the
     *       game and is the authority for its own fields;</li>
     *   <li>fields belonging to a non-refreshed source are carried
     *       forward from the previous report - we have no new
     *       information about them, so we neither add them nor
     *       clear them;</li>
     *   <li>the status is derived from the merged set, not the
     *       fresh set, so a partial refresh over a previously
     *       complete doc can stay {@code COMPLETE} when the
     *       refreshed source also reports complete.</li>
     * </ul>
     * Timestamps: a partial refresh updates
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
        Set<GameField> existingMissing = parseMissingFields(
                existing == null ? null : existing.missingFields());
        Set<GameField> refreshedFields = refreshedSourceFields(decision);
        Set<GameField> nonRefreshedMissing = new HashSet<>();
        for (GameField f : existingMissing) {
            if (!refreshedFields.contains(f)) {
                nonRefreshedMissing.add(f);
            }
        }
        Set<GameField> refreshedMissing = new HashSet<>();
        for (GameField f : fresh.missingFields()) {
            if (refreshedFields.contains(f)) {
                refreshedMissing.add(f);
            }
        }
        Set<GameField> mergedMissing = new HashSet<>(nonRefreshedMissing);
        mergedMissing.addAll(refreshedMissing);
        ValidationStatus status = mergedMissing.isEmpty()
                ? ValidationStatus.COMPLETE
                : ValidationStatus.PARTIAL;
        return new ValidationReport(
                status, Set.copyOf(mergedMissing),
                previousFull, now);
    }

    /**
     * The set of fields that the refreshed sources are the authority
     * for. A field belongs to a source per {@link GameField#source()}.
     * When a source was not refreshed, its fields are not in this
     * set and the composer carries the previous report's value
     * forward for them.
     */
    private static Set<GameField> refreshedSourceFields(RefreshPolicy.RefreshDecision decision) {
        EnumSet<GameField> fields = EnumSet.noneOf(GameField.class);
        if (decision.refreshDeals()) {
            fields.add(GameField.STORES);
        }
        if (decision.refreshRawg()) {
            for (GameField f : GameField.values()) {
                if (f.source() == GameField.Source.RAWG) {
                    fields.add(f);
                }
            }
        }
        return fields;
    }

    /**
     * Parse the {@code ValidationReportDto.missingFields} string list
     * back to a {@code Set<GameField>}. Unknown names (e.g. from an
     * older schema version) are silently ignored so a single bad
     * value cannot break the hydration of an otherwise valid doc.
     */
    private static Set<GameField> parseMissingFields(List<String> names) {
        if (names == null || names.isEmpty()) {
            return Set.of();
        }
        Set<GameField> result = EnumSet.noneOf(GameField.class);
        for (String name : names) {
            if (name == null) {
                continue;
            }
            try {
                result.add(GameField.valueOf(name));
            } catch (IllegalArgumentException e) {
                log.warn("composeReport_unknownMissingField name=\"{}\"", name);
            }
        }
        return result;
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
