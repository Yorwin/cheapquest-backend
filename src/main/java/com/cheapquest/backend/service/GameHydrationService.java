package com.cheapquest.backend.service;

import com.cheapquest.backend.client.FirebaseClient;
import com.cheapquest.backend.domain.AggregatedGame;
import com.cheapquest.backend.domain.GameDeals;
import com.cheapquest.backend.domain.rawg.RawgDetails;
import com.cheapquest.backend.domain.validation.GameField;
import com.cheapquest.backend.domain.validation.ValidationReport;
import com.cheapquest.backend.domain.validation.ValidationStatus;
import com.cheapquest.backend.dto.HydrationReport;
import com.cheapquest.backend.dto.firebase.FailedDoc;
import com.cheapquest.backend.dto.firebase.GameDocumentDto;
import com.cheapquest.backend.dto.firebase.HydrationPatch;
import com.cheapquest.backend.dto.firebase.PendingDoc;
import com.cheapquest.backend.dto.firebase.ValidationReportDto;
import com.cheapquest.backend.mapper.FirebaseMapper;
import com.cheapquest.backend.util.InstantUtils;
import java.time.Clock;
import java.time.Instant;
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
 * game document in the {@code pending} queue:
 * <ol>
 *   <li>read the {@code pending} collection (one doc per slug
 *       the cron is expected to process);</li>
 *   <li>for each, read the corresponding {@code games/{slug}}
 *       document and ask {@link RefreshPolicy} which sources
 *       are stale;</li>
 *   <li>delegate the (subset of) source lookups to {@link GameLookup};</li>
 *   <li>merge and validate;</li>
 *   <li>build a partial Firestore patch (title, cheapshark,
 *       rawg, validationReport) and {@code update} the document;
 *       the {@code lastFullFetchAt} / {@code lastPartialFetchAt}
 *       timestamps in the report are updated per the cadence
 *       decision;</li>
 *   <li>on success, mark {@code locales.en} as synced via a
 *       separate partial update (the only locale write the
 *       hydration path performs; locales.es and locales.fr
 *       are owned by the future translation pipeline) and
 *       remove the slug from {@code pending};</li>
 *   <li>on failure, increment the attempt counter on the pending
 *       doc; when the counter reaches {@code maxAttempts} the
 *       slug is moved to the {@code failed} DLQ.</li>
 * </ol>
 *
 * <p>The {@code failed} move happens after the per-doc update
 * has been attempted (or skipped) so a slug that consistently
 * produces an empty report (e.g. the game no longer exists in
 * the upstream APIs) eventually lands in the DLQ rather than
 * re-running the lookup on every cron tick.
 *
 * <p>On startup, {@link #recoverStalePending(java.time.Duration)}
 * resets the attempt counter on any pending entry whose
 * {@code lastAttemptAt} is older than the configured threshold.
 * This is the recovery path for a JVM crash mid-run: an entry
 * that was in flight when the JVM died should not accumulate
 * false attempt counts toward the 3-strike DLQ on the next
 * boot.
 */
public final class GameHydrationService {

    private static final Logger log = LoggerFactory.getLogger(GameHydrationService.class);

    private final FirebaseClient firebaseClient;
    private final FirebaseMapper firebaseMapper;
    private final GameLookup gameLookup;
    private final GameMerger merger;
    private final ValidationService validator;
    private final RefreshPolicy refreshPolicy;
    private final TranslationService translationService;
    private final Clock clock;
    private final int maxAttempts;

    public GameHydrationService(FirebaseClient firebaseClient, FirebaseMapper firebaseMapper,
            GameLookup gameLookup,
            GameMerger merger, ValidationService validator,
            RefreshPolicy refreshPolicy, Clock clock) {
        this(firebaseClient, firebaseMapper, gameLookup, merger, validator,
                refreshPolicy, null, clock, 3);
    }

    public GameHydrationService(FirebaseClient firebaseClient, FirebaseMapper firebaseMapper,
            GameLookup gameLookup,
            GameMerger merger, ValidationService validator,
            RefreshPolicy refreshPolicy, TranslationService translationService,
            Clock clock, int maxAttempts) {
        this.firebaseClient = Objects.requireNonNull(firebaseClient, "firebaseClient");
        this.firebaseMapper = Objects.requireNonNull(firebaseMapper, "firebaseMapper");
        this.gameLookup = Objects.requireNonNull(gameLookup, "gameLookup");
        this.merger = Objects.requireNonNull(merger, "merger");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.refreshPolicy = Objects.requireNonNull(refreshPolicy, "refreshPolicy");
        this.translationService = translationService;  // may be null in tests that don't translate
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1, got " + maxAttempts);
        }
        this.maxAttempts = maxAttempts;
    }

    // Old 8-arg constructor preserved for backwards compatibility
    // with tests that don't exercise the translation trigger.
    public GameHydrationService(FirebaseClient firebaseClient, FirebaseMapper firebaseMapper,
            GameLookup gameLookup,
            GameMerger merger, ValidationService validator,
            RefreshPolicy refreshPolicy, Clock clock, int maxAttempts) {
        this(firebaseClient, firebaseMapper, gameLookup, merger, validator,
                refreshPolicy, null, clock, maxAttempts);
    }

    public HydrationReport hydrateAll() {
        return hydrateAll(false);
    }

    /**
     * Run the hydration pipeline over the {@code pending}
     * queue. When {@code force} is {@code true} the per-source
     * cadence is bypassed and every doc is fully re-fetched;
     * when false the per-source cadence decides which sources
     * to refresh per doc. See
     * {@link RefreshPolicy#decide(GameDocumentDto, boolean)}.
     *
     * <p>For each pending entry the corresponding
     * {@code games/{slug}} doc is loaded. The hydration is
     * treated as a "failure" (counts toward the 3-strike
     * move-to-failed) when the lookup throws, the update
     * throws, or the resulting report is
     * {@link ValidationStatus#EMPTY}. A "success" (COMPLETE,
     * PARTIAL, SKIPPED) removes the slug from the pending
     * queue; the next cron tick will not pick it up again
     * unless the operator re-enqueues it.
     */
    public HydrationReport hydrateAll(boolean force) {
        long start = clock.millis();
        List<PendingDoc> pending = firebaseClient.readPending();
        log.info("hydrate_all_start pending_count={} force={}", pending.size(), force);

        int processed = 0;
        int complete = 0;
        int partial = 0;
        int empty = 0;
        int skipped = 0;
        int failed = 0;
        int dealsRefreshed = 0;
        int rawgRefreshed = 0;
        int movedToFailed = 0;
        List<String> failures = new ArrayList<>();
        List<String> movedToFailedList = new ArrayList<>();

        for (PendingDoc entry : pending) {
            String slug = entry.slug();
            processed++;
            try {
                HydrationResult result = hydratePendingEntry(entry, force);
                switch (result.outcome.status()) {
                    case COMPLETE -> complete++;
                    case PARTIAL -> partial++;
                    case EMPTY -> empty++;
                    case SKIPPED -> skipped++;
                }
                if (result.outcome.dealsRefreshed()) dealsRefreshed++;
                if (result.outcome.rawgRefreshed()) rawgRefreshed++;
                if (result.movedToFailed) {
                    movedToFailed++;
                    movedToFailedList.add(slug);
                }
            } catch (RuntimeException e) {
                log.error("hydrate_doc_failed slug={} err={}: {}",
                        slug, e.getClass().getSimpleName(), e.getMessage(), e);
                HydrationResult r = recordFailureAndMaybeMoveToFailed(entry,
                        e.getClass().getSimpleName() + ": " + e.getMessage());
                if (r.movedToFailed) {
                    movedToFailed++;
                    movedToFailedList.add(slug);
                } else {
                    failures.add(slug == null ? "<null-slug>" : slug);
                    failed++;
                }
            }
        }

        long durationMs = clock.millis() - start;
        HydrationReport report = new HydrationReport(
                processed, complete, partial, empty, skipped, failed,
                dealsRefreshed, rawgRefreshed, movedToFailed, durationMs,
                List.copyOf(failures), List.copyOf(movedToFailedList));
        log.info("hydrate_all_done processed={} complete={} partial={} empty={} skipped={} "
                        + "failed={} deals_refreshed={} rawg_refreshed={} moved_to_failed={} durationMs={}",
                report.processed(), report.complete(), report.partial(),
                report.empty(), report.skipped(), report.failed(),
                report.dealsRefreshed(), report.rawgRefreshed(),
                report.movedToFailed(), report.durationMs());
        return report;
    }

    /**
     * Reset the attempt counter on every pending entry whose
     * {@code lastAttemptAt} is older than the threshold. Called
     * on startup to recover from a JVM crash that interrupted
     * a previous run: an entry that was mid-flight when the
     * process died would otherwise carry a false attempt count
     * toward the 3-strike DLQ, even though no actual retry was
     * attempted. Entries that have never been attempted
     * ({@code lastAttemptAt == null}, e.g. freshly enqueued by
     * the bootstrap) are left untouched.
     *
     * <p>Returns the number of entries that were reset. The
     * caller is expected to log this so the operator can spot
     * chronic instability (a non-zero number on every boot means
     * the JVM is dying often enough to matter).
     */
    public int recoverStalePending(java.time.Duration staleThreshold) {
        Objects.requireNonNull(staleThreshold, "staleThreshold");
        if (staleThreshold.isNegative() || staleThreshold.isZero()) {
            throw new IllegalArgumentException(
                    "staleThreshold must be positive, got " + staleThreshold);
        }
        List<PendingDoc> pending = firebaseClient.readPending();
        Instant cutoff = Instant.now(clock).minus(staleThreshold);
        int recovered = 0;
        for (PendingDoc entry : pending) {
            if (entry.lastAttemptAt() == null) {
                continue;
            }
            if (entry.lastAttemptAt().isAfter(cutoff)) {
                continue;
            }
            firebaseClient.replacePending(new PendingDoc(
                    entry.slug(), 0, null, null));
            log.warn("pending_recovered slug={} previous_attempts={} previous_last_error=\"{}\"",
                    entry.slug(), entry.attempts(), entry.lastError());
            recovered++;
        }
        if (recovered > 0) {
            log.info("pending_recovery_done recovered={} threshold={}s",
                    recovered, staleThreshold.toSeconds());
        }
        return recovered;
    }

    /**
     * Hydrate one pending entry: load the game doc, run the
     * pipeline, and update the pending/failed collections as
     * appropriate. The "is this a failure" decision lives here
     * for the EMPTY outcome (a logical failure, not an
     * exception); the outer {@code hydrateAll} loop handles the
     * exception case so the bookkeeping only runs once.
     */
    private HydrationResult hydratePendingEntry(PendingDoc entry, boolean force) {
        String slug = entry.slug();
        GameDocumentDto doc = firebaseClient.readOne(slug).orElse(null);
        if (doc == null) {
            log.warn("hydrate_pending_missing slug={} reason=game_doc_gone", slug);
            recordFailureAndMaybeMoveToFailed(entry, "game document gone");
            return new HydrationResult(HydrationOutcome.emptyOutcome(), false);
        }
        HydrationOutcome outcome = hydrateInternal(doc, force);
        if (outcome.status() == ValidationStatus.EMPTY) {
            log.warn("hydrate_doc_empty slug={} reason=both_sources_failed", slug);
            return recordFailureAndMaybeMoveToFailed(entry, "both sources returned empty");
        }
        firebaseClient.removeFromPending(slug);
        return new HydrationResult(outcome, false);
    }

    private HydrationResult recordFailureAndMaybeMoveToFailed(PendingDoc entry, String error) {
        int newAttempts = entry.attempts() + 1;
        if (newAttempts >= maxAttempts) {
            Instant now = Instant.now(clock);
            FailedDoc failed = new FailedDoc(
                    entry.slug(), newAttempts,
                    entry.lastAttemptAt() == null ? now : entry.lastAttemptAt(),
                    now, error);
            firebaseClient.moveToFailed(failed);
            log.warn("hydrate_doc_moved_to_failed slug={} attempts={} last_error=\"{}\"",
                    entry.slug(), newAttempts, error);
            return new HydrationResult(HydrationOutcome.emptyOutcome(), true);
        }
        firebaseClient.recordPendingFailure(entry.slug(), newAttempts, Instant.now(clock), error);
        return new HydrationResult(HydrationOutcome.emptyOutcome(), false);
    }

    private record HydrationResult(HydrationOutcome outcome, boolean movedToFailed) {
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
                    slug, e.getClass().getSimpleName(), e.getMessage(), e);
            return false;
        }
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
        // Mark the english locale as synced now that the english
        // content is fresh. Done as a separate partial update so
        // the (future) translation pipeline owns locales.es and
        // locales.fr without the hydration path clobbering them.
        firebaseClient.markLocaleSynced(slug, "en", Instant.now(clock));
        // Queue the other target locales for translation. Each
        // enqueue is idempotent (ALREADY_EXISTS is a no-op), so a
        // second hydration run that doesn't bump rawg.fetchedAt
        // will not re-enqueue.
        if (translationService != null && rawgAgg != null && rawgAgg.rawg() != null) {
            translationService.markStaleAndEnqueue(
                    slug, rawgAgg.rawg().fetchedAt());
        }
        log.info("hydrate_doc_ok slug={} status={} missing={} full_refresh={}",
                slug, composed.status(), composed.missingFields().size(), decision.isFullRefresh());
        return new HydrationOutcome(composed.status(), decision.refreshDeals(), decision.refreshRawg());
    }

    /**
     * Apply the per-source cadence to the validation report. A full
     * refresh (both sources) uses the fresh evaluation as-is and
     * sets {@code lastFullFetchAt = now}. A partial refresh (one
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
     * Timestamps: a full refresh sets
     * {@code lastFullFetchAt = now} and preserves the existing
     * {@code lastPartialFetchAt} (which may be null on the very
     * first full refresh). A partial refresh sets
     * {@code lastPartialFetchAt = now} and preserves the existing
     * {@code lastFullFetchAt} <b>verbatim</b> - including null when
     * the doc was bootstrapped and a full refresh has never
     * happened. The previous implementation used {@code now} as a
     * fallback for the partial path, which produced
     * {@code lastFullFetchAt = now} on a doc that had only ever
     * been partially refreshed, misleading the next reader into
     * thinking a full refresh had occurred.
     */
    private ValidationReport composeReport(
            ValidationReportDto existing, ValidationReport fresh,
            RefreshPolicy.RefreshDecision decision) {
        Instant now = Instant.now(clock);
        Instant previousPartial = InstantUtils.parseOrNull(existing == null ? null : existing.lastPartialFetchAt());

        if (decision.isFullRefresh()) {
            return new ValidationReport(
                    fresh.status(), fresh.missingFields(),
                    now, previousPartial);
        }
        Instant previousFull = InstantUtils.parseOrNull(existing == null ? null : existing.lastFullFetchAt());
        GameField.ParseResult parsed = GameField.parseAll(
                existing == null ? null : existing.missingFields());
        if (!parsed.unknown().isEmpty()) {
            log.warn("composeReport_unknownMissingField names={}", parsed.unknown());
        }
        Set<GameField> existingMissing = parsed.fields();
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

    private record HydrationOutcome(ValidationStatus status, boolean dealsRefreshed, boolean rawgRefreshed) {
        static HydrationOutcome emptyOutcome() {
            return new HydrationOutcome(ValidationStatus.EMPTY, false, false);
        }
        static HydrationOutcome skipped() {
            return new HydrationOutcome(ValidationStatus.SKIPPED, false, false);
        }
    }
}
