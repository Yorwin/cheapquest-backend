package com.cheapquest.backend.domain.validation;

/**
 * Health of an aggregated game based on which fields could be filled.
 * See AGENTS.md §7.
 *
 * <p>{@code SKIPPED} is the outcome of the per-source cadence
 * (RefreshPolicy): the document was visited but neither CheapShark
 * nor RAWG was stale, so the lookup was skipped entirely. The
 * document is not rewritten.
 */
public enum ValidationStatus {
    COMPLETE,
    PARTIAL,
    EMPTY,
    SKIPPED
}
