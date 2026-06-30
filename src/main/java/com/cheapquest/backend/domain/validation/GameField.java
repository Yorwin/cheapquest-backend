package com.cheapquest.backend.domain.validation;

/**
 * Single field-level deficiency that the future employee-facing UI will
 * surface so a missing piece of data can be located and re-fetched or
 * hand-edited. Each value corresponds to one refetch strategy in
 * AGENTS.md §7.
 */
public enum GameField {
    DESCRIPTION,
    HEADER_IMAGE,
    TRAILER,
    GENRES,
    TAGS,
    SCREENSHOTS,
    REVIEWS,
    STORES,
    RELEASED,
    DEVELOPER,
    PUBLISHER
}
