package com.cheapquest.backend.service.sections.builders;

import com.cheapquest.backend.domain.sections.GameView;
import com.cheapquest.backend.domain.sections.SectionItem;
import com.cheapquest.backend.domain.sections.SectionName;
import com.cheapquest.backend.service.sections.BuildResult;
import com.cheapquest.backend.service.sections.SectionBuilder;
import com.cheapquest.backend.service.sections.SectionContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * "Vintage" section builder. Surfaces old-but-still-playable
 * games: at least {@value #MIN_AGE_YEARS} years since the RAWG
 * release date, with a quality signal (metacritic or rating)
 * and at least one live CheapShark offer.
 *
 * <p>Eligibility, all conditions mandatory. A missing or
 * malformed field silently excludes the game; the section's
 * {@code totalCandidates} is the post-filter count, so a thin
 * section is distinguishable from an empty catalog.
 * <ul>
 *   <li>{@code rawg} non-null with a parseable
 *       {@code released} date, and that date is at least
 *       eight whole years before "today" (per the
 *       injected {@link Clock}).</li>
 *   <li>At least one quality signal: a non-null
 *       {@code metacritic} or a non-null {@code rating}.
 *       Games without either are excluded because the data
 *       is not there to claim "this is a quality vintage
 *       title".</li>
 *   <li>{@code cheapshark} non-null, {@code synced}, with a
 *       non-null {@code bestDeal}. Without a live offer the
 *       "still playable today" half of the contract is not
 *       met.</li>
 * </ul>
 *
 * <p>Score, higher is better. The two quality signals are
 * made directly comparable: when metacritic is present it is
 * the score as-is; when only the RAWG rating is present, the
 * score rescales it (rating 0-5 to 0-100) so the sort key
 * keeps a single scale. Tie-break is
 * {@code released} ascending (the older release wins),
 * then catalog order, which is deterministic because the
 * catalog comes from Firestore ordered by document id.
 *
 * <p>The {@code bestDeal} surfaced on each
 * {@link SectionItem} is the per-game best offer that
 * CheapShark pinned, the same one the "mejores promos"
 * section uses, so a game appears at most once per section
 * even when several stores carry it. The {@code extra} bag
 * always carries {@code year=<YYYY>}; the quality signal
 * key is {@code metacritic} when present or {@code rating}
 * otherwise, with its raw value.
 */
public final class VintageBuilder implements SectionBuilder {

    /**
     * Minimum age (whole years) for a game to be considered
     * "vintage". Hardcoded so the contract is one source of
     * truth; lift to a property only if a future use case
     * needs to tune it without a deploy.
     */
    private static final int MIN_AGE_YEARS = 8;

    private static final Logger log = LoggerFactory.getLogger(VintageBuilder.class);

    private static final int RATING_SCALE_TO_100 = 20;

    private final int maxItems;
    private final Clock clock;

    public VintageBuilder(int maxItems, Clock clock) {
        if (maxItems < 1) {
            throw new IllegalArgumentException(
                    "maxItems must be >= 1, got: " + maxItems);
        }
        this.maxItems = maxItems;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public SectionName name() {
        return SectionName.VINTAGE;
    }

    @Override
    public BuildResult build(SectionContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        LocalDate today = LocalDate.now(clock);
        List<Candidate> eligible = ctx.catalog().stream()
                .map(g -> toCandidate(g, today))
                .filter(Objects::nonNull)
                .toList();
        List<SectionItem> top = eligible.stream()
                .sorted(Comparator
                        .comparing(Candidate::score).reversed()
                        .thenComparing(Candidate::released))
                .limit(maxItems)
                .map(this::toItem)
                .toList();
        return new BuildResult(eligible.size(), top);
    }

    private Candidate toCandidate(GameView g, LocalDate today) {
        if (g.rawg() == null) {
            return null;
        }
        LocalDate released = parseReleaseOrNull(g.rawg().released());
        if (released == null) {
            return null;
        }
        if (Period.between(released, today).getYears() < MIN_AGE_YEARS) {
            return null;
        }
        Integer metacritic = g.rawg().metacritic();
        Double rating = g.rawg().rating();
        if (metacritic == null && rating == null) {
            return null;
        }
        if (g.cheapshark() == null
                || !g.cheapshark().synced()
                || g.cheapshark().bestDeal() == null) {
            return null;
        }
        BigDecimal score = metacritic != null
                ? BigDecimal.valueOf(metacritic)
                : BigDecimal.valueOf(rating * RATING_SCALE_TO_100);
        return new Candidate(g, released, score);
    }

    private LocalDate parseReleaseOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            log.debug("vintage_release_unparseable released={}", raw);
            return null;
        }
    }

    private SectionItem toItem(Candidate c) {
        String year = Integer.toString(c.released().getYear());
        Integer metacritic = c.game().rawg().metacritic();
        String scoreKey;
        String scoreValue;
        if (metacritic != null) {
            scoreKey = "metacritic";
            scoreValue = metacritic.toString();
        } else {
            scoreKey = "rating";
            scoreValue = c.game().rawg().rating().toString();
        }
        return new SectionItem(
                c.game().slug(),
                c.game().title(),
                c.game().cheapshark().bestDeal(),
                c.score(),
                Map.of("year", year, scoreKey, scoreValue));
    }

    private record Candidate(GameView game, LocalDate released, BigDecimal score) {
    }
}
