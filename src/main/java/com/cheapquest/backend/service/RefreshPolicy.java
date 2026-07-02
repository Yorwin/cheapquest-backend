package com.cheapquest.backend.service;

import com.cheapquest.backend.config.AppProperties;
import com.cheapquest.backend.dto.firebase.GameDocumentDto;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * Decides which of the two data sources (CheapShark / RAWG) are stale
 * for a given game document and therefore need to be re-fetched.
 * Pure service: no I/O, no Firestore, no API calls; the {@link Clock}
 * is injected so tests can pin a fixed instant.
 *
 * <p>Thresholds come from {@code refresh.deals-max-age-hours} (default
 * 24h) and {@code refresh.rawg-max-age-days} (default 180d). A missing
 * or malformed {@code fetchedAt} is treated as "never fetched" and
 * therefore always stale.
 *
 * <p><b>First-call semantics:</b> a freshly bootstrapped document has
 * {@code cheapshark.fetchedAt = null} and {@code rawg.fetchedAt = null}
 * (see {@code FirebaseMapper.toBootstrapDocument}). Both sources are
 * therefore considered stale on the first {@code hydrate} run after
 * bootstrap, so the game is fully populated without waiting for the
 * cadence intervals. The intervals only start governing refresh
 * frequency from the second run onward, once the timestamps are set.
 */
public final class RefreshPolicy {

    private final Duration dealsMaxAge;
    private final Duration rawgMaxAge;
    private final Clock clock;

    public RefreshPolicy(AppProperties props, Clock clock) {
        this(
                Duration.ofHours(props.refreshDealsMaxAgeHours()),
                Duration.ofDays(props.refreshRawgMaxAgeDays()),
                clock);
    }

    public RefreshPolicy(Duration dealsMaxAge, Duration rawgMaxAge, Clock clock) {
        this.dealsMaxAge = Objects.requireNonNull(dealsMaxAge, "dealsMaxAge");
        this.rawgMaxAge = Objects.requireNonNull(rawgMaxAge, "rawgMaxAge");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public RefreshDecision decide(GameDocumentDto doc) {
        return decide(doc, false);
    }

    /**
     * Decide which sources to refresh for a given document.
     *
     * <p>When {@code force} is {@code true} both sources are always
     * refreshed regardless of the per-source cadence. This is the
     * implementation behind the {@code -Dapp.refresh.force=true} flag
     * and is used by the smoke / repair flows where a full re-fetch
     * is required (e.g. a doc was bootstrapped, the validation
     * report was corrupted, or a previously-merged-but-partial
     * document needs to be re-evaluated from scratch).
     *
     * <p>When {@code force} is {@code false} the per-source
     * thresholds are consulted: CheapShark is stale when
     * {@code cheapshark.fetchedAt} is null/malformed or older than
     * {@code dealsMaxAge}; RAWG is stale when
     * {@code rawg.fetchedAt} is null/malformed or older than
     * {@code rawgMaxAge}. See the class Javadoc for first-call
     * semantics.
     */
    public RefreshDecision decide(GameDocumentDto doc, boolean force) {
        Objects.requireNonNull(doc, "doc");
        if (force) {
            return new RefreshDecision(true, true);
        }
        Instant now = Instant.now(clock);
        return new RefreshDecision(
                isStale(cheapsharkFetchedAt(doc), now, dealsMaxAge),
                isStale(rawgFetchedAt(doc), now, rawgMaxAge));
    }

    private static String cheapsharkFetchedAt(GameDocumentDto doc) {
        return doc.cheapshark() == null ? null : doc.cheapshark().fetchedAt();
    }

    private static String rawgFetchedAt(GameDocumentDto doc) {
        return doc.rawg() == null ? null : doc.rawg().fetchedAt();
    }

    private static boolean isStale(String fetchedAt, Instant now, Duration maxAge) {
        if (fetchedAt == null) {
            return true;
        }
        try {
            Instant t = Instant.parse(fetchedAt);
            return Duration.between(t, now).compareTo(maxAge) > 0;
        } catch (DateTimeParseException e) {
            return true;
        }
    }

    public record RefreshDecision(boolean refreshDeals, boolean refreshRawg) {
        public boolean nothingToDo() {
            return !refreshDeals && !refreshRawg;
        }

        public boolean isFullRefresh() {
            return refreshDeals && refreshRawg;
        }
    }
}
