package com.cheapquest.backend.dto.firebase.sections;

import com.cheapquest.backend.dto.firebase.OfferDto;
import com.cheapquest.backend.dto.firebase.RawgDocumentDto;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Firestore representation of one entry inside a
 * {@link SectionSnapshotDto}. Mirrors
 * {@link com.cheapquest.backend.domain.sections.SectionItem}
 * field by field. The {@code extra} bag is a
 * {@code Map<String, String>} so each section can attach its
 * own per-item hints (savings pct, year, store name, ...) and
 * Firestore stores them as a native nested map.
 *
 * <p>{@code rawgDetails} carries the full RAWG payload as a
 * typed nested document so the public read endpoint can
 * surface the description, genres, tags, platforms,
 * developers, publishers and the rest without a re-fetch. It
 * is nullable for backward compatibility: snapshots written
 * before this field was added (no {@code rawgDetails} key in
 * the Firestore document) load with {@code rawgDetails ==
 * null}, which the section contract treats as "no RAWG
 * metadata available".
 */
public record SectionItemDto(
        String slug,
        String title,
        OfferDto bestDeal,
        BigDecimal score,
        Map<String, String> extra,
        RawgDocumentDto rawgDetails) {

    public SectionItemDto {
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(bestDeal, "bestDeal");
        Objects.requireNonNull(score, "score");
        extra = extra == null ? Map.of() : Map.copyOf(extra);
    }
}
