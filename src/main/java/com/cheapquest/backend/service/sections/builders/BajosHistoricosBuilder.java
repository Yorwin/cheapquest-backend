package com.cheapquest.backend.service.sections.builders;

import com.cheapquest.backend.domain.Offer;
import com.cheapquest.backend.domain.sections.CheapsharkView;
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
 * "Bajos Históricos" section builder. Surfaces games whose
 * current best CheapShark price is at or below their
 * all-time historical low, ranked inside the eligible set
 * by the RAWG {@code ratingsCount}.
 *
 * <p>Eligibility, binary and strict. Every condition is
 * mandatory and a failure silently excludes the game; the
 * snapshot's {@code totalCandidates} is the post-filter
 * count, so a thin or empty section is distinguishable
 * from an empty catalog. The product expects roughly
 * 0.5 percent of a 1k-strong catalog to qualify, so the
 * filter does almost all the work and the score only has
 * to rank the small surviving set. The conditions are:
 * <ul>
 *   <li>{@code cheapshark} non-null, {@code synced}, with
 *       a non-null {@code bestDeal} whose price is
 *       strictly positive.</li>
 *   <li>{@code cheapestEver} non-null and strictly
 *       positive, so the "historical low" has a defined
 *       value to compare against.</li>
 *   <li>{@code bestDeal.price <= cheapestEver}. A price
 *       strictly above the low means the game is not
 *       currently at its low and is filtered out. A
 *       price strictly below the low is accepted because
 *       it trivially qualifies as a new all-time low
 *       (the recorded {@code cheapestEver} is just
 *       stale).</li>
 *   <li>{@code rawg} non-null with a non-null
 *       {@code ratingsCount}. Without a rating count the
 *       popularity score is undefined; the game is
 *       excluded rather than ranked with a zero that
 *       could steal a slot from a real candidate.</li>
 * </ul>
 *
 * <p>Score, higher is better. The raw RAWG
 * {@code ratingsCount} is lifted to {@code BigDecimal} to
 * match {@link SectionItem#score()}'s type. A rating count
 * of zero is a valid input and yields a score of zero; the
 * game is still in the eligible set because the
 * popularity signal is not the filter, it is only the
 * rank. Tie-break is catalog order: {@code List#stream()
 * .sorted()} is stable, the catalog is the Firestore
 * games collection ordered by document id, so the
 * tie-break is deterministic across runs.
 *
 * <p>The {@code bestDeal} on each {@link SectionItem} is
 * the per-game best offer that CheapShark pinned, the
 * same one the "mejores promos" section surfaces, so a
 * game appears at most once per section even when several
 * stores carry it. The {@code extra} bag always carries
 * {@code cheapestEver}, {@code currentPrice} and
 * {@code markupPct}; {@code markupPct} is emitted as the
 * literal string "0.00" because the binary filter means
 * eligible games are, by definition, at or below the low.
 * The field is kept stable so the front can rely on it as
 * a "historical low" label and so a future relaxation of
 * the filter does not break the wire contract.
 */
public final class BajosHistoricosBuilder implements SectionBuilder {

    public static final int DEFAULT_MAX_ITEMS = 5;

    private static final BigDecimal MARKUP_PCT_AT_LOW = new BigDecimal("0.00");

    private final int maxItems;

    public BajosHistoricosBuilder(int maxItems) {
        if (maxItems < 1) {
            throw new IllegalArgumentException(
                    "maxItems must be >= 1, got: " + maxItems);
        }
        this.maxItems = maxItems;
    }

    @Override
    public SectionName name() {
        return SectionName.BAJOS_HISTORICOS;
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
        CheapsharkView cs = g.cheapshark();
        if (cs == null || !cs.synced() || cs.bestDeal() == null) {
            return null;
        }
        if (cs.cheapestEver() == null
                || cs.cheapestEver().signum() <= 0
                || cs.bestDeal().price().signum() <= 0) {
            return null;
        }
        if (cs.bestDeal().price().compareTo(cs.cheapestEver()) > 0) {
            return null;
        }
        RawgView rawg = g.rawg();
        if (rawg == null || rawg.ratingsCount() == null) {
            return null;
        }
        return new Candidate(g, BigDecimal.valueOf(rawg.ratingsCount().longValue()));
    }

    private SectionItem toItem(Candidate c) {
        Offer best = c.game().cheapshark().bestDeal();
        BigDecimal low = c.game().cheapshark().cheapestEver();
        return new SectionItem(
                c.game().slug(),
                c.game().title(),
                best,
                c.score(),
                Map.of(
                        "cheapestEver", low.toPlainString(),
                        "currentPrice", best.price().toPlainString(),
                        "markupPct", MARKUP_PCT_AT_LOW.toPlainString()));
    }

    private record Candidate(GameView game, BigDecimal score) {
    }
}
