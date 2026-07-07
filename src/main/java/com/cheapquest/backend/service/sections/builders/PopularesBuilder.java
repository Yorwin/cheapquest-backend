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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * "Populares" section builder. Surfaces popular games that
 * also carry a very good current CheapShark deal, ranked by
 * a composite score that multiplies a RAWG popularity
 * signal by the offer savings.
 *
 * <p>Score, higher is better:
 * <pre>
 *   pop     = ratingsCount * 2
 *           + (owned + beaten + playing)
 *           + additionsCount
 *           + suggestionsCount
 *   score   = pop * savings
 * </pre>
 *
 * <p>The multiplicative shape is the contract. A zero on
 * either side zeros the score, so a highly popular game
 * with a poor deal does not climb the ranking and a great
 * deal with no engagement does not surface either. Within
 * the eligible set a mid-popular game with an outstanding
 * deal can outscore a mega-popular game with a merely good
 * deal, which is the trade-off the product wants to
 * surface.
 *
 * <p>{@code addedByStatus} is read selectively: {@code owned},
 * {@code beaten} and {@code playing} contribute to the
 * popularity signal because they reflect real engagement
 * (bought, finished, currently playing). {@code yet} and
 * {@code dropped} are intentionally ignored: "want to
 * play" is interest without commitment and "dropped" is
 * negative signal that should not boost a card.
 *
 * <p>Eligibility, all conditions mandatory and any failure
 * silently excludes the game:
 * <ul>
 *   <li>{@code cheapshark} non-null, {@code synced}, with
 *       a non-null {@code bestDeal} carrying a non-null
 *       {@code savings} value.</li>
 *   <li>{@code savings >= 50}. The hard threshold that
 *       defines "very good offer": below 50 percent the
 *       offer is a regular sale, not a deal worth
 *       surfacing in a popularity-curated section.</li>
 *   <li>{@code rawg} non-null with a strictly positive
 *       {@code pop}, i.e. at least one engagement field
 *       contributes. A game with no engagement data
 *       cannot meaningfully rank in a popularity
 *       section.</li>
 * </ul>
 *
 * <p>The score is computed with {@code BigDecimal.multiply}
 * so the product of the typically-seven-digit operands
 * stays exact; {@code savings}'s two-decimal precision is
 * preserved. Tie-break is catalog order:
 * {@code List#stream().sorted()} is stable, the catalog is
 * the Firestore games collection ordered by document id,
 * so the result is deterministic across runs.
 *
 * <p>The {@code bestDeal} on each {@link SectionItem} is
 * the per-game best offer that CheapShark pinned, the
 * same one the other "deals" sections surface, so a game
 * appears at most once per section even when several
 * stores carry it. The {@code extra} bag always carries
 * {@code ratings}, {@code owned}, {@code beaten},
 * {@code additions} and {@code savingsPct} so the front
 * can render the underlying inputs of the score without
 * having to re-fetch the game document.
 */
public final class PopularesBuilder implements SectionBuilder {

    public static final int DEFAULT_MAX_ITEMS = 11;

    private static final BigDecimal MIN_SAVINGS = new BigDecimal("50");
    private static final int RATINGS_WEIGHT = 2;

    private final int maxItems;

    public PopularesBuilder(int maxItems) {
        if (maxItems < 1) {
            throw new IllegalArgumentException(
                    "maxItems must be >= 1, got: " + maxItems);
        }
        this.maxItems = maxItems;
    }

    @Override
    public SectionName name() {
        return SectionName.POPULARES;
    }

    @Override
    public BuildResult build(SectionContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        List<Candidate> eligible = ctx.catalog().stream()
                .map(this::toCandidate)
                .filter(Objects::nonNull)
                .toList();
        List<SectionItem> top = eligible.stream()
                .sorted(Comparator.comparing(Candidate::score).reversed())
                .limit(maxItems)
                .map(this::toItem)
                .toList();
        return new BuildResult(eligible.size(), top);
    }

    private Candidate toCandidate(GameView g) {
        if (g.cheapshark() == null
                || !g.cheapshark().synced()
                || g.cheapshark().bestDeal() == null) {
            return null;
        }
        BigDecimal savings = g.cheapshark().bestDeal().savings();
        if (savings.compareTo(MIN_SAVINGS) < 0) {
            return null;
        }
        if (g.rawg() == null) {
            return null;
        }
        long pop = popularity(g.rawg());
        if (pop <= 0) {
            return null;
        }
        BigDecimal score = BigDecimal.valueOf(pop).multiply(savings);
        return new Candidate(g, pop, savings, score);
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
                c.game().cheapshark().bestDeal(),
                c.score(),
                Map.of(
                        "ratings", intOrZero(rawg.ratingsCount()),
                        "owned", intOrZero(status == null ? null : status.get("owned")),
                        "beaten", intOrZero(status == null ? null : status.get("beaten")),
                        "additions", intOrZero(rawg.additionsCount()),
                        "savingsPct", c.savings().toPlainString()),
                c.game().rawgDetails());
    }

    private static String intOrZero(Integer v) {
        return v == null ? "0" : v.toString();
    }

    private record Candidate(GameView game, long popularity,
            BigDecimal savings, BigDecimal score) {
    }
}
