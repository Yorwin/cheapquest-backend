package com.cheapquest.backend.service.sections;

import com.cheapquest.backend.domain.sections.SectionName;

/**
 * Contract for the five section recomputations. A builder
 * is pure: it takes a {@link SectionContext} (catalog, plus
 * later the per-day deal snapshot) and returns a
 * {@link BuildResult} (eligible-set size plus the final
 * ranked, limited list). The orchestration (lock, Firestore
 * read of the catalog, write of the snapshot, error
 * reporting) lives in {@code SectionsService}, not here.
 *
 * <p>The {@link #name()} is what
 * {@code SectionsService.recompute(SectionName)} uses to
 * dispatch to the right builder. It must match the
 * {@link SectionName} the builder produces.
 *
 * <p>Builders are stateless and thread-safe; a single
 * instance is constructed at startup in
 * {@code App.runServe} and shared across recompute calls.
 */
public interface SectionBuilder {

    SectionName name();

    BuildResult build(SectionContext ctx);
}
