package com.cheapquest.backend.domain.sections;

import java.util.Optional;

/**
 * The five curated sections that the daily cron recomputes from
 * the hydrated games collection. See AGENTS.md §8 (Refresh
 * Trigger) for the operational role of these sections and the
 * {@code /admin/sections} and {@code /sections} endpoint surface.
 *
 * <p>{@link #slug()} is the wire-format identifier used in URLs
 * (e.g. {@code /admin/sections/mejores-promos}) and in the
 * Firestore document path. It is derived from the enum constant
 * so a rename here is a single-source-of-truth change. The
 * inverse, {@link #fromSlug(String)}, is what the admin
 * endpoint uses to resolve a path segment back to a constant
 * without throwing on an unknown value.
 */
public enum SectionName {
    POPULARES,
    NUEVAS_OFERTAS,
    VINTAGE,
    MEJORES_PROMOS,
    BAJOS_HISTORICOS;

    /**
     * Wire-format slug: lowercased, with {@code _} replaced by
     * {@code -}. Stable across renames: the value here is the
     * contract the cron, the endpoint and Firestore share.
     */
    public String slug() {
        return name().toLowerCase().replace('_', '-');
    }

    /**
     * Resolve a wire-format slug back to a constant. Returns
     * {@link Optional#empty()} for {@code null}, blank or
     * unknown slugs so the caller (typically the admin
     * endpoint) can map a bad value to HTTP 400 without
     * having to catch exceptions.
     */
    public static Optional<SectionName> fromSlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        for (SectionName n : values()) {
            if (n.slug().equals(slug)) {
                return Optional.of(n);
            }
        }
        return Optional.empty();
    }
}
