package com.cheapquest.backend.dto.admin;

import java.util.List;

/**
 * Response of {@code POST /admin/sections} and
 * {@code POST /admin/sections/{name}}. Mirrors the
 * per-section {@code SectionsService.Report} list and adds
 * a top-level rollup so a caller can see at a glance how
 * many sections succeeded without iterating the array.
 *
 * <p>{@code processed} is the number of reports with
 * status {@code COMPLETED}. {@code failed} is the number
 * with status {@code FAILED}. Sections reported as
 * {@code SKIPPED_NO_BUILDER} count towards neither; they
 * are expected during the rollout as the builders land
 * one section at a time.
 */
public record SectionsResponseDto(
        String status,
        int processed,
        int failed,
        long durationMs,
        List<SectionSummary> sections) {

    public record SectionSummary(
            String name,
            String status,
            int totalCandidates,
            int itemsKept,
            long durationMs,
            String error) {
    }
}
