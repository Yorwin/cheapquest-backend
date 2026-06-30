package com.cheapquest.backend.domain.validation;

/**
 * Health of an aggregated game based on which fields could be filled.
 * See AGENTS.md §7.
 */
public enum ValidationStatus {
    COMPLETE,
    PARTIAL,
    EMPTY
}
