package com.cheapquest.backend.service.sections.builders;

import com.cheapquest.backend.domain.Offer;
import com.cheapquest.backend.domain.sections.GameView;
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
 * "Mejores Promos" section builder. For every game in the
 * catalog, looks at the per-game best offer (the offer with
 * the highest savings that {@code GameDeals.bestDeal}
 * already pinned) and surfaces the top N by savings
 * percentage.
 *
 * <p>Eligibility: the game must have a non-null
 * {@code cheapshark} view, the block must be
 * {@code synced == true} (a partially-hydrated game has
 * stale or missing deal data) and there must be a non-null
 * {@code bestDeal} (otherwise there is nothing to rank).
 * Bootstrapped docs that have never been hydrated are
 * silently filtered out; the snapshot's
 * {@code totalCandidates} is the count of games that passed
 * this filter, not the raw catalog size, so the front can
 * tell apart "no games" from "no games yet".
 *
 * <p>Ties (two games with the exact same savings) are broken
 * by catalog order: {@code List#stream().sorted()} is stable,
 * so insertion order is preserved. The catalog is the
 * Firestore {@code games} collection ordered by document id
 * (the existing pagination contract), so the tie-break is
 * deterministic across runs.
 *
 * <p>The {@code bestDeal} field of the produced
 * {@link SectionItem} is the domain {@code Offer} (not a
 * DTO); the mapper at the persistence boundary converts it
 * to an {@code OfferDto} when the snapshot is written to
 * Firestore. Same for the {@code score}, which carries the
 * raw savings {@code BigDecimal} so the public DTO can
 * format it as a number, not a string.
 */
public final class MejoresPromosBuilder implements SectionBuilder {

    public static final int DEFAULT_MAX_ITEMS = 5;

    private final int maxItems;

    public MejoresPromosBuilder(int maxItems) {
        if (maxItems < 1) {
            throw new IllegalArgumentException(
                    "maxItems must be >= 1, got: " + maxItems);
        }
        this.maxItems = maxItems;
    }

    @Override
    public SectionName name() {
        return SectionName.MEJORES_PROMOS;
    }

    @Override
    public BuildResult build(SectionContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        List<GameView> eligible = ctx.catalog().stream()
                .filter(this::isEligible)
                .toList();
        List<SectionItem> top = eligible.stream()
                .map(this::toItem)
                .sorted(Comparator.comparing(SectionItem::score).reversed())
                .limit(maxItems)
                .toList();
        return new BuildResult(eligible.size(), top);
    }

    private boolean isEligible(GameView g) {
        return g.cheapshark() != null
                && g.cheapshark().synced()
                && g.cheapshark().bestDeal() != null;
    }

    private SectionItem toItem(GameView g) {
        Offer best = g.cheapshark().bestDeal();
        BigDecimal savings = best.savings();
        return new SectionItem(
                g.slug(),
                g.title(),
                best,
                savings,
                Map.of("savingsPct", savings.toPlainString()));
    }
}
