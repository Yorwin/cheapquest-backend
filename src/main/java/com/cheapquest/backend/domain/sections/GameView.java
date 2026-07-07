package com.cheapquest.backend.domain.sections;

import com.cheapquest.backend.domain.rawg.RawgDetails;
import java.util.Objects;

/**
 * Buildable projection of a hydrated game document. This is
 * the input shape that every section {@code SectionBuilder}
 * receives: a flat record with the slug, the canonical title
 * and a per-source view ({@link CheapsharkView},
 * {@link RawgView}) so a builder only has to read the fields
 * it actually cares about.
 *
 * <p>{@code cheapshark}, {@code rawg} and {@code rawgDetails}
 * are nullable on purpose: a game can be in the catalog with
 * neither source hydrated yet. Builders filter on the fields
 * they need (e.g. {@code cheapshark.synced && cheapshark.bestDeal != null}
 * for the "mejores promos" section) and silently skip the
 * rest.
 *
 * <p>{@code rawg} is the narrow view used by the section
 * builders (popularity counters and release date, the only
 * inputs the score formula needs). {@code rawgDetails} is
 * the full {@link RawgDetails} record, propagated to the
 * {@code SectionItem} so the public API can surface the
 * description, genres, tags, platforms and the rest of the
 * RAWG payload without a re-fetch.
 */
public record GameView(
        String slug,
        String title,
        CheapsharkView cheapshark,
        RawgView rawg,
        RawgDetails rawgDetails) {

    public GameView {
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(title, "title");
    }
}
