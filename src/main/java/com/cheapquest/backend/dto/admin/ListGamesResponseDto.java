package com.cheapquest.backend.dto.admin;

import java.util.List;

/**
 * Response body for {@code GET /admin/games?status=…}.
 *
 * <p>{@code status} echoes the query parameter so the caller
 * can tell which queue the list came from. {@code count} is
 * the size of {@code entries} (kept for convenience so the
 * caller does not have to count). {@code entries} holds one
 * row per document in the requested queue.
 *
 * <p>Timestamps are ISO-8601 strings (matching the
 * project-wide DTO convention; see {@code RawgDocumentDto}),
 * not {@link java.time.Instant} so the DTO does not depend on
 * a custom GSON {@code Instant} adapter. {@code firstAttemptAt}
 * is only populated for the {@code failed} queue (the
 * {@code pending} docs do not carry it); it is {@code null}
 * for pending entries.
 */
public record ListGamesResponseDto(
        String status,
        int count,
        List<QueueEntryDto> entries) {

    public record QueueEntryDto(
            String slug,
            int attempts,
            String firstAttemptAt,
            String lastAttemptAt,
            String lastError) {
    }

    public static ListGamesResponseDto of(String status, List<QueueEntryDto> entries) {
        return new ListGamesResponseDto(status, entries.size(), entries);
    }
}
