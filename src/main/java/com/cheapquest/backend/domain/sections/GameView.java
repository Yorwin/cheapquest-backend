package com.cheapquest.backend.domain.sections;

import java.util.Objects;

/**
 * Buildable projection of a hydrated game document. This is
 * the input shape that every section {@code SectionBuilder}
 * receives: a flat record with the slug, the canonical title
 * and a per-source view ({@link CheapsharkView},
 * {@link RawgView}) so a builder only has to read the fields
 * it actually cares about.
 *
 * <p>{@code cheapshark} and {@code rawg} are nullable on
 * purpose: a game can be in the catalog with neither source
 * hydrated yet. Builders filter on the fields they need (e.g.
 * {@code cheapshark.synced && cheapshark.bestDeal != null}
 * for the "mejores promos" section) and silently skip the
 * rest.
 */
public record GameView(
        String slug,
        String title,
        CheapsharkView cheapshark,
        RawgView rawg) {

    public GameView {
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(title, "title");
    }
}
