package com.cheapquest.backend.service;

import com.cheapquest.backend.client.FirebaseClient;
import com.cheapquest.backend.dto.firebase.GameDocumentDto;
import com.cheapquest.backend.mapper.FirebaseMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ingest pipeline: turns a list of human-readable game titles
 * into Firestore documents enqueued for hydration. For each name:
 *
 * <ol>
 *   <li>Normalise whitespace and validate non-empty.</li>
 *   <li>Derive a slug via {@link FirebaseMapper#toSlug(String)}.</li>
 *   <li>Create the game document via
 *       {@link FirebaseClient#createIfNotExists(String, GameDocumentDto)}
 *       (atomic, idempotent).</li>
 *   <li>Enqueue the slug via
 *       {@link FirebaseClient#addToPending(String)} (idempotent
 *       no-op if a pending entry already exists).</li>
 * </ol>
 *
 * <p>The result is a per-item classification: each name ends up
 * either in {@link IngestOutcome#accepted()} with the action
 * taken ({@code CREATED} or {@code ALREADY_EXISTED}) or in
 * {@link IngestOutcome#failed()} with a short reason. A failure
 * on one name does not abort the batch: the rest of the names
 * are processed and the caller receives a complete report.
 *
 * <p><b>Idempotency</b>: re-submitting the same batch is safe.
 * Names whose doc was already in {@code games/{slug}} on the
 * previous call come back as {@code ALREADY_EXISTED} and the
 * {@code addToPending} call is a no-op when a pending entry
 * already exists.
 *
 * <p><b>Language</b>: titles must be provided in English; that
 * is the source of truth RAWG and CheapShark use to find the
 * game. The {@code language} parameter is validated up-front
 * and a non-{@code "en"} value is rejected with
 * {@link IllegalArgumentException}, which the HTTP layer maps
 * to 400. Default is {@code "en"}.
 */
public final class GameIngestService {

    private static final Logger log = LoggerFactory.getLogger(GameIngestService.class);

    /** Hard cap on the number of names accepted per call. */
    public static final int MAX_BATCH_SIZE = 100;

    /** Default and only supported title language. */
    public static final String DEFAULT_LANGUAGE = "en";
    public static final Set<String> SUPPORTED_LANGUAGES = Set.of("en");

    private final FirebaseClient firebaseClient;
    private final FirebaseMapper firebaseMapper;
    private final Clock clock;

    public GameIngestService(FirebaseClient firebaseClient,
            FirebaseMapper firebaseMapper, Clock clock) {
        this.firebaseClient = Objects.requireNonNull(firebaseClient, "firebaseClient");
        this.firebaseMapper = Objects.requireNonNull(firebaseMapper, "firebaseMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Ingest the given titles. The input list is de-duplicated
     * preserving insertion order before processing. Throws
     * {@link IllegalArgumentException} on:
     * <ul>
     *   <li>{@code names} null or empty</li>
     *   <li>size after de-duplication greater than
     *       {@link #MAX_BATCH_SIZE}</li>
     *   <li>{@code language} non-null and not in
     *       {@link #SUPPORTED_LANGUAGES}</li>
     * </ul>
     * Per-item failures (empty name, slug derivation) are
     * captured in {@link IngestOutcome#failed()} and do not
     * abort the batch.
     */
    public IngestOutcome ingest(List<String> rawNames, String language) {
        if (rawNames == null || rawNames.isEmpty()) {
            throw new IllegalArgumentException("names must be non-empty");
        }
        String lang = language == null ? DEFAULT_LANGUAGE : language;
        if (!SUPPORTED_LANGUAGES.contains(lang)) {
            throw new IllegalArgumentException(
                    "language must be one of " + SUPPORTED_LANGUAGES + ", got \"" + language + "\"");
        }

        List<String> unique = new ArrayList<>(new LinkedHashSet<>(rawNames));
        if (unique.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "batch size " + unique.size() + " exceeds maximum " + MAX_BATCH_SIZE);
        }

        Instant start = Instant.now(clock);
        log.info("ingest_request count={} source=service language={}", unique.size(), lang);

        List<IngestItem> accepted = new ArrayList<>(unique.size());
        List<IngestFailure> failed = new ArrayList<>();
        for (String original : unique) {
            ingestOne(original, accepted, failed);
        }

        long durationMs = Duration.between(start, Instant.now(clock)).toMillis();
        log.info("ingest_done accepted={} failed={} durationMs={}",
                accepted.size(), failed.size(), durationMs);
        return new IngestOutcome(List.copyOf(accepted), List.copyOf(failed));
    }

    private void ingestOne(String original, List<IngestItem> accepted, List<IngestFailure> failed) {
        String name = normalise(original);
        if (name.isEmpty()) {
            failed.add(new IngestFailure(original, "empty name"));
            log.warn("ingest_one_failed name=\"{}\" error=empty_name", original);
            return;
        }
        String slug;
        try {
            slug = FirebaseMapper.toSlug(name);
        } catch (IllegalArgumentException e) {
            failed.add(new IngestFailure(original, "empty slug: \"" + name + "\""));
            log.warn("ingest_one_failed name=\"{}\" error=empty_slug", name);
            return;
        }
        try {
            GameDocumentDto doc = firebaseMapper.toBootstrapDocument(name, slug);
            boolean created = firebaseClient.createIfNotExists(slug, doc);
            firebaseClient.addToPending(slug);
            IngestAction action = created ? IngestAction.CREATED : IngestAction.ALREADY_EXISTED;
            accepted.add(new IngestItem(name, slug, action));
            log.info("ingest_one_ok name=\"{}\" slug={} action={}", name, slug, action);
        } catch (RuntimeException e) {
            failed.add(new IngestFailure(original,
                    e.getClass().getSimpleName() + ": " + e.getMessage()));
            log.error("ingest_one_failed name=\"{}\" slug={} error={}: {}",
                    name, slug, e.getClass().getSimpleName(), e.getMessage(), e);
        }
    }

    private static String normalise(String s) {
        if (s == null) {
            return "";
        }
        return s.trim().replaceAll("\\s+", " ");
    }

    /** Result of a batch ingest. */
    public record IngestOutcome(List<IngestItem> accepted, List<IngestFailure> failed) {
    }

    /** One successful ingest entry. */
    public record IngestItem(String name, String slug, IngestAction action) {
    }

    /** One failed ingest entry, with a short human-readable reason. */
    public record IngestFailure(String name, String error) {
    }

    /** What happened to the game document in Firestore. */
    public enum IngestAction {
        /** The game document was created in this call. */
        CREATED,
        /** The game document already existed; enqueued for re-hydration. */
        ALREADY_EXISTED
    }
}
