package com.cheapquest.backend.dto.admin;

/**
 * Response body for {@code POST /admin/refresh} on a successful
 * run. {@code status} is always {@code "completed"} on 200; the
 * other fields summarise the hydration that just finished.
 */
public record RefreshResponseDto(String status, int processed, int failed, long durationMs) {
}
