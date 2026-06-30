package com.cheapquest.backend.dto.firebase;

import java.util.List;

/**
 * Firestore-friendly view of a {@code ValidationReport}. Status and
 * missing-field enums are stored as their {@code name()} (e.g.
 * {@code "PARTIAL"}, {@code ["TRAILER"]}). Timestamps are stored as
 * ISO 8601 strings to stay string-typed at the Firestore boundary.
 */
public record ValidationReportDto(
        String status,
        List<String> missingFields,
        String lastFullFetchAt,
        String lastPartialFetchAt) {
}
