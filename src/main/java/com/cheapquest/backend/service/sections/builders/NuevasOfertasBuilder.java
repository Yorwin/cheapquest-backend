package com.cheapquest.backend.service.sections.builders;

import com.cheapquest.backend.domain.Offer;
import com.cheapquest.backend.domain.sections.GameView;
import com.cheapquest.backend.domain.sections.RawgView;
import com.cheapquest.backend.domain.sections.SectionItem;
import com.cheapquest.backend.domain.sections.SectionName;
import com.cheapquest.backend.service.sections.BuildResult;
import com.cheapquest.backend.service.sections.SectionBuilder;
import com.cheapquest.backend.service.sections.SectionContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * "Nuevas Ofertas" section builder. Surfaces games whose
 * best CheapShark deal became the best (or first appeared)
 * within the recent window, ranked by the same composite as
 * {@code PopularesBuilder} (popularity times savings) so a
 * popular game with a great new offer outranks a mid-popular
 * game with a similar new deal.
 *
 * <p>The "new" signal is {@code bestDeal.firstSeenAt}, set
 * and maintained by the hydration pipeline. An offer is
 * considered new when its {@code firstSeenAt} lies in the
 * closed window {@code [now - windowDays, now]}: the best
 * offer became the best, either because it just appeared
 * or because the previous best got worse. The window is
 * configurable per deploy via
 * {@code sections.new-offers.window-days} in
 * {@code application.properties}, default 2.
 *
 * <p>Eligibility, all conditions mandatory and any failure
 * silently excludes the game:
 * <ul>
 *   <li>{@code cheapshark} non-null, {@code synced}, with
 *       a non-null {@code bestDeal} that carries a
 *       non-null {@code firstSeenAt} (older games with no
 *       firstSeenAt are filtered out; the backfill script
 *       in {@code scripts/} populates the field for the
 *       existing catalog).</li>
 *   <li>{@code firstSeenAt >= now - windowDays}. The
 *       closed lower bound means a deal whose firstSeenAt
 *       is exactly at the cutoff still qualifies.</li>
 *   <li>{@code rawg} non-null with a strictly positive
 *       {@code pop} (at least one engagement field
 *       contributes) so the score has signal.</li>
 * </ul>
 *
 * <p>Score, higher is better: the same {@code pop * savings}
 * formula as {@code PopularesBuilder}, computed with
 * {@code BigDecimal.multiply} so the two typically-seven-
 * digit operands stay exact. Tie-break is catalog order
 * via {@code List.sorted()} stable; the catalog is the
 * Firestore games collection ordered by document id.
 *
 * <p>The {@code extras} bag always carries {@code ratings},
 * {@code owned}, {@code beaten}, {@code additions},
 * {@code savingsPct} and {@code firstSeenAt} so the front
 * can render "New today" / "New 1d ago" labels without
 * re-fetching the game document.
 */
public final class NuevasOfertasBuilder implements SectionBuilder {

    public static final int DEFAULT_MAX_ITEMS = 8;
    public static final int DEFAULT_WINDOW_DAYS = 2;

    private static final int RATINGS_WEIGHT = 2;

    private final int maxItems;
    private final int windowDays;
    private final Clock clock;

    public NuevasOfertasBuilder(int maxItems, int windowDays, Clock clock) {
        if (maxItems < 1) {
            throw new IllegalArgumentException(
                    "maxItems must be >= 1, got: " + maxItems);
        }
        if (windowDays < 1) {
            throw new IllegalArgumentException(
                    "windowDays must be >= 1, got: " + windowDays);
        }
        this.maxItems = maxItems;
        this.windowDays = windowDays;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public SectionName name() {
        return SectionName.NUEVAS_OFERTAS;
    }

    @Override
    public BuildResult build(SectionContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        Instant cutoff = Instant.now(clock).minus(Duration.ofDays(windowDays));
        List<Candidate> eligible = ctx.catalog().stream()
                .map(g -> toCandidate(g, cutoff))
                .filter(Objects::nonNull)
                .toList();
        List<SectionItem> top = eligible.stream()
                .sorted(Comparator.comparing(Candidate::score).reversed())
                .limit(maxItems)
                .map(this::toItem)
                .toList();
        return new BuildResult(eligible.size(), top);
    }

    private Candidate toCandidate(GameView g, Instant cutoff) {
        if (g.cheapshark() == null
                || !g.cheapshark().synced()
                || g.cheapshark().bestDeal() == null) {
            return null;
        }
        Offer best = g.cheapshark().bestDeal();
        if (best.firstSeenAt() == null || best.firstSeenAt().isBefore(cutoff)) {
            return null;
        }
        if (g.rawg() == null) {
            return null;
        }
        long pop = popularity(g.rawg());
        if (pop <= 0) {
            return null;
        }
        BigDecimal score = BigDecimal.valueOf(pop).multiply(best.savings());
        return new Candidate(g, best, score);
    }

    private long popularity(RawgView rawg) {
        long pop = 0;
        Integer ratings = rawg.ratingsCount();
        if (ratings != null) {
            pop += ratings.longValue() * RATINGS_WEIGHT;
        }
        Integer additions = rawg.additionsCount();
        if (additions != null) {
            pop += additions.longValue();
        }
        Integer suggestions = rawg.suggestionsCount();
        if (suggestions != null) {
            pop += suggestions.longValue();
        }
        Map<String, Integer> status = rawg.addedByStatus();
        if (status != null) {
            pop += safeAdd(status, "owned");
            pop += safeAdd(status, "beaten");
            pop += safeAdd(status, "playing");
        }
        return pop;
    }

    private static long safeAdd(Map<String, Integer> map, String key) {
        Integer v = map.get(key);
        return v == null ? 0L : v.longValue();
    }

    private SectionItem toItem(Candidate c) {
        RawgView rawg = c.game().rawg();
        Map<String, Integer> status = rawg.addedByStatus();
        return new SectionItem(
                c.game().slug(),
                c.game().title(),
                c.best(),
                c.score(),
                Map.of(
                        "ratings", intOrZero(rawg.ratingsCount()),
                        "owned", intOrZero(status == null ? null : status.get("owned")),
                        "beaten", intOrZero(status == null ? null : status.get("beaten")),
                        "additions", intOrZero(rawg.additionsCount()),
                        "savingsPct", c.best().savings().toPlainString(),
                        "firstSeenAt", c.best().firstSeenAt().toString()));
    }

    private static String intOrZero(Integer v) {
        return v == null ? "0" : v.toString();
    }

    private record Candidate(GameView game, Offer best, BigDecimal score) {
    }
}
