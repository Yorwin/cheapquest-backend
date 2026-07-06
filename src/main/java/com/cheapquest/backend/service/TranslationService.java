package com.cheapquest.backend.service;

import com.cheapquest.backend.client.DeepLClient;
import com.cheapquest.backend.client.FirebaseClient;
import com.cheapquest.backend.domain.rawg.RawgDetails;
import com.cheapquest.backend.dto.firebase.GameDocumentDto;
import com.cheapquest.backend.dto.firebase.RawgDocumentDto;
import com.cheapquest.backend.dto.firebase.TranslationFailedDoc;
import com.cheapquest.backend.dto.firebase.TranslationPendingDoc;
import com.cheapquest.backend.exception.TranslationFailedException;
import com.cheapquest.backend.mapper.FirebaseMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates the DeepL translation pipeline.
 *
 * <p>Reads the {@code translations-pending} collection one entry
 * at a time, fetches the source fields from the game document,
 * batches a single DeepL call with everything that needs to go
 * to the same target locale, and writes the result back into the
 * game document's {@code locales.{lang}} block. The 3-strike DLQ
 * pattern is the same as {@code pending} -> {@code failed} for
 * the hydration pipeline.
 *
 * <p>Idempotency: a single (slug, locale) entry covers a
 * translation for the source data whose
 * {@code rawg.fetchedAt == entry.sourceFetchedAt}. If a
 * concurrent hydration re-fetches the source and bumps
 * {@code rawg.fetchedAt}, the entry becomes stale: the
 * {@code LocaleBlock.sourceFetchedAt} written by this run will
 * be older than the new {@code rawg.fetchedAt}, and the
 * {@code GameHydrationService} will re-enqueue the entry on the
 * next hydration. No data is lost; the worst case is one wasted
 * DeepL call.
 */
public final class TranslationService {

    private static final Logger log = LoggerFactory.getLogger(TranslationService.class);

    /** Locales the pipeline will translate (target_lang list). */
    public static final List<String> DEFAULT_TARGET_LOCALES = List.of("es", "fr");

    private final FirebaseClient firebaseClient;
    private final DeepLClient deepLClient;
    private final FirebaseMapper firebaseMapper;
    private final Clock clock;
    private final int maxAttempts;
    private final List<String> targetLocales;

    public TranslationService(FirebaseClient firebaseClient, DeepLClient deepLClient,
            FirebaseMapper firebaseMapper, Clock clock, int maxAttempts) {
        this(firebaseClient, deepLClient, firebaseMapper, clock, maxAttempts,
                DEFAULT_TARGET_LOCALES);
    }

    public TranslationService(FirebaseClient firebaseClient, DeepLClient deepLClient,
            FirebaseMapper firebaseMapper, Clock clock, int maxAttempts,
            List<String> targetLocales) {
        this.firebaseClient = Objects.requireNonNull(firebaseClient, "firebaseClient");
        this.deepLClient = Objects.requireNonNull(deepLClient, "deepLClient");
        this.firebaseMapper = Objects.requireNonNull(firebaseMapper, "firebaseMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1, got " + maxAttempts);
        }
        this.maxAttempts = maxAttempts;
        this.targetLocales = List.copyOf(targetLocales);
    }

    /**
     * Mark {@code es} and {@code fr} locales as stale and enqueue
     * them for translation. Called by
     * {@code GameHydrationService} after a successful hydration
     * so the next {@code app mode=translate} run picks them up.
     */
    public void markStaleAndEnqueue(String slug, Instant rawgFetchedAt) {
        if (slug == null || rawgFetchedAt == null) {
            return;
        }
        for (String locale : targetLocales) {
            firebaseClient.enqueueTranslation(slug, locale, rawgFetchedAt);
        }
        log.info("translation_enqueued slug={} locales={} source_fetched_at={}",
                slug, targetLocales, rawgFetchedAt);
    }

    /**
     * Process the entire {@code translations-pending} queue.
     * Returns the number of (slug, locale) entries successfully
     * translated. Failures are recorded against the per-entry
     * attempt counter; entries that exhaust the budget move to
     * {@code translations-failed}.
     */
    public int translateAll() {
        List<TranslationPendingDoc> pending = firebaseClient.readTranslationPending();
        if (pending.isEmpty()) {
            log.info("translate_all pending_count=0 done=0");
            return 0;
        }
        log.info("translate_all_start pending_count={}", pending.size());
        int done = 0;
        for (TranslationPendingDoc entry : pending) {
            if (translateOne(entry)) {
                done++;
            }
        }
        log.info("translate_all_done pending_count={} done={}", pending.size(), done);
        return done;
    }

    /**
     * Translate a single (slug, locale) entry. Returns true on
     * success (the entry was removed and the locale updated),
     * false on failure (the attempt counter was bumped, or the
     * entry was moved to {@code translations-failed}).
     */
    public boolean translateOne(TranslationPendingDoc entry) {
        String slug = entry.slug();
        String locale = entry.locale();
        log.info("translate_doc_start slug={} locale={} attempts={} source_fetched_at={}",
                slug, locale, entry.attempts(), entry.sourceFetchedAt());
        try {
            GameDocumentDto doc = firebaseClient.readOne(slug).orElse(null);
            if (doc == null) {
                log.warn("translate_doc_missing slug={} locale={} reason=game_doc_gone",
                        slug, locale);
                recordFailureAndMaybeMoveToFailed(entry, "game document gone");
                return false;
            }
            if (doc.rawg() == null || doc.rawg().data() == null) {
                log.warn("translate_doc_missing slug={} locale={} reason=no_rawg_data",
                        slug, locale);
                recordFailureAndMaybeMoveToFailed(entry, "no rawg data");
                return false;
            }
            RawgDocumentDto rawg = doc.rawg().data();
            List<String> sourceTags = rawg.tags().stream()
                    .map(t -> t.name())
                    .toList();
            List<String> inputs = new java.util.ArrayList<>(1 + sourceTags.size());
            inputs.add(rawg.description() == null ? "" : rawg.description());
            inputs.addAll(sourceTags);

            List<String> outputs = deepLClient.translate(inputs, locale);
            String translatedDescription = outputs.get(0);
            List<String> translatedTags = outputs.subList(1, outputs.size());

            firebaseClient.writeLocaleTranslation(
                    slug, locale,
                    translatedDescription, translatedTags,
                    entry.sourceFetchedAt(), Instant.now(clock));
            firebaseClient.removeFromTranslationPending(slug, locale);
            log.info("translate_doc_ok slug={} locale={} chars_in={} chars_out={}",
                    slug, locale, sumLength(inputs), sumLength(outputs));
            return true;
        } catch (TranslationFailedException e) {
            log.error("translate_doc_failed slug={} locale={} err={}: {}",
                    slug, locale, e.getClass().getSimpleName(), e.getMessage(), e);
            recordFailureAndMaybeMoveToFailed(entry, e.getMessage());
            return false;
        } catch (RuntimeException e) {
            log.error("translate_doc_failed slug={} locale={} err={}: {}",
                    slug, locale, e.getClass().getSimpleName(), e.getMessage(), e);
            recordFailureAndMaybeMoveToFailed(entry,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    private void recordFailureAndMaybeMoveToFailed(TranslationPendingDoc entry, String error) {
        int newAttempts = entry.attempts() + 1;
        if (newAttempts >= maxAttempts) {
            Instant now = Instant.now(clock);
            TranslationFailedDoc failed = new TranslationFailedDoc(
                    entry.slug(), entry.locale(), newAttempts,
                    entry.lastAttemptAt() == null ? now : entry.lastAttemptAt(),
                    now, error);
            firebaseClient.moveToTranslationFailed(failed);
            log.warn("translate_doc_moved_to_failed slug={} locale={} attempts={} last_error=\"{}\"",
                    entry.slug(), entry.locale(), newAttempts, error);
            return;
        }
        firebaseClient.recordTranslationFailure(
                entry.slug(), entry.locale(), newAttempts, Instant.now(clock), error);
    }

    private static int sumLength(List<String> strings) {
        return strings.stream().mapToInt(s -> s == null ? 0 : s.length()).sum();
    }
}
