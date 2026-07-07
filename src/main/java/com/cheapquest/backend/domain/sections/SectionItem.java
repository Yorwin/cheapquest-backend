package com.cheapquest.backend.domain.sections;

import com.cheapquest.backend.domain.Offer;
import com.cheapquest.backend.domain.rawg.RawgDetails;
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
 * (higher is better). The range and unit depend on the section
 * (e.g. the raw savings percentage for "mejores promos", a
 * normalised composite score for "populares"); the consumer
 * must not assume a fixed range. The sort and the limit are
 * applied by the builder; consumers should re-sort only if
 * the section contract explicitly allows it.
 *
 * <p>{@code extra} is a small per-section bag of human-friendly
 * hints (e.g. {@code savingsPct=66.70} for promos,
 * {@code year=2014} for vintage). Keys are stable per section;
 * the public DTO serialises them flat alongside the other
 * fields. All values are strings to keep the type safe at the
 * record boundary; builders are responsible for formatting
 * numbers and dates.
 *
 * <p>{@code rawgDetails} is the full {@link RawgDetails} for
 * the game, propagated from the catalog walk so the public
 * API can surface the description, genres, tags, platforms,
 * developers, publishers and the rest of the RAWG payload
 * without a re-fetch. Nullable: a game in a section may not
 * have RAWG data (e.g. the cheapshark block came through but
 * the RAWG lookup 404'd, leaving the doc with no
 * {@code rawg.data}).
 */
public record SectionItem(
        String slug,
        String title,
        Offer bestDeal,
        BigDecimal score,
        Map<String, String> extra,
        RawgDetails rawgDetails) {

    public SectionItem {
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(bestDeal, "bestDeal");
        Objects.requireNonNull(score, "score");
        extra = extra == null ? Map.of() : Map.copyOf(extra);
    }
}
