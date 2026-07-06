package com.cheapquest.backend.dto.firebase.sections;

import java.util.List;
import java.util.Objects;

/**
 * Firestore representation of a
 * {@link com.cheapquest.backend.domain.sections.SectionSnapshot}.
 * One document per (section, day) pair, written under
 * {@code sections/{YYYY-MM-DD}/items/{slug}} for the history
 * mirror, and the same payload rewritten under
 * {@code sections/latest/items/{slug}} for cheap reads.
 *
 * <p>Timestamps are ISO-8601 strings so the document does
 * not depend on a custom {@code LocalDate} / {@code Instant}
 * adapter. The same convention is used by
 * {@link com.cheapquest.backend.dto.firebase.LocaleBlock}
 * and {@code RawgDocumentDto.fetchedAt}.
 */
public record SectionSnapshotDto(
        String name,
        String date,
        String computedAt,
        Integer totalCandidates,
        List<SectionItemDto> items) {

    public SectionSnapshotDto {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(computedAt, "computedAt");
        Objects.requireNonNull(totalCandidates, "totalCandidates");
        items = items == null ? List.of() : List.copyOf(items);
    }
}
