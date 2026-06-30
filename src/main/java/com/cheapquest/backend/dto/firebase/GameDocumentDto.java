package com.cheapquest.backend.dto.firebase;

import java.util.Map;

/**
 * Top-level shape of a game document under
 * {@code firestore.collection.games-path} (default {@code games}).
 *
 * <p>Document ID is the RAWG slug (e.g. {@code "far-cry-6"}), which
 * is also stored as the {@code slug} field for convenience. The
 * {@code addedAt} timestamp is set once at bootstrap and never
 * rewritten; the {@code validationReport} snapshot is rewritten on
 * every hydration pass.
 */
public record GameDocumentDto(
        String title,
        String slug,
        String originalLanguage,
        Boolean active,
        String addedAt,
        CheapsharkBlock cheapshark,
        RawgBlock rawg,
        Map<String, LocaleBlock> locales,
        ValidationReportDto validationReport) {
}
