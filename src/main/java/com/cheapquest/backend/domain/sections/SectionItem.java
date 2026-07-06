package com.cheapquest.backend.domain.sections;

import com.cheapquest.backend.dto.firebase.OfferDto;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/**
 * One ranked entry inside a {@link SectionSnapshot}. The
 * {@code bestDeal} is the offer that the section chose to
 * surface for that game (always the per-game best, so a game
 * never appears twice in a single section even when several
 * stores carry it).
 *
 * <p>{@code score} is the section-specific ranking signal
 * (0..1, higher is better). The sort and the limit are applied
 * by the builder; consumers should re-sort only if the section
 * contract explicitly allows it.
 *
 * <p>{@code extra} is a small per-section bag of human-friendly
 * hints (e.g. {@code savingsPct=66.70} for promos,
 * {@code year=2014} for vintage). Keys are stable per section;
 * the public DTO serialises them flat alongside the other
 * fields. All values are strings to keep the type safe at the
 * record boundary; builders are responsible for formatting
 * numbers and dates.
 */
public record SectionItem(
        String slug,
        String title,
        OfferDto bestDeal,
        BigDecimal score,
        Map<String, String> extra) {

    public SectionItem {
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(bestDeal, "bestDeal");
        Objects.requireNonNull(score, "score");
        extra = extra == null ? Map.of() : Map.copyOf(extra);
    }
}
