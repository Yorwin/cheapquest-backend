package com.cheapquest.backend.dto.firebase;

import java.time.Instant;

/**
 * One entry in the {@code failed} top-level collection: a slug
 * the cron has given up on after the configured number of
 * consecutive failures (AGENTS.md §7). The DLQ fields let the
 * operator diagnose why a doc was moved aside and decide whether
 * to re-enqueue it for another attempt.
 *
 * <p>{@code failed} is a top-level collection (same rationale
 * as {@link PendingDoc}). The first-attempt timestamp is kept
 * separately from the latest so a chronically-failing doc can
 * be aged out by an operator query without re-counting from the
 * current attempt.
 */
public record FailedDoc(
        String slug,
        int attempts,
        Instant firstAttemptAt,
        Instant lastAttemptAt,
        String lastError) {
}
