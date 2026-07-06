package com.cheapquest.backend.service.sections;

import com.cheapquest.backend.domain.sections.GameView;
import java.util.List;
import java.util.Objects;

/**
 * Input handed to every {@link SectionBuilder}. Carries the
 * catalog of {@link GameView}s that the builder filters,
 * sorts and trims to produce its {@link
 * com.cheapquest.backend.domain.sections.SectionItem} list.
 *
 * <p>The v1 shape is intentionally minimal: just the
 * catalog. Future sections (e.g. "nuevas ofertas") will add
 * per-day deal-snapshot fields to the same record so the
 * service layer only has to build the context once and
 * every builder sees the same data.
 *
 * <p>The {@code catalog} is defensive-copied at construction
 * so the builder cannot accidentally observe mid-rebuild
 * mutations if a caller (typically
 * {@code SectionsService.recomputeAll}) ever decides to
 * share a single list across builders.
 */
public record SectionContext(List<GameView> catalog) {

    public SectionContext {
        catalog = catalog == null ? List.of() : List.copyOf(catalog);
    }

    /**
     * Convenience for the empty / initial state used by some
     * builder tests and for the "no catalog at all" fallback
     * in {@code SectionsService}. Avoids the "is null"
     * dance at every call site.
     */
    public static SectionContext empty() {
        return new SectionContext(List.of());
    }
}
