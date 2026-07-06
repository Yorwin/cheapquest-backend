package com.cheapquest.backend.dto.firebase.sections;

import com.cheapquest.backend.dto.firebase.OfferDto;
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
 */
public record SectionItemDto(
        String slug,
        String title,
        OfferDto bestDeal,
        BigDecimal score,
        Map<String, String> extra) {

    public SectionItemDto {
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(bestDeal, "bestDeal");
        Objects.requireNonNull(score, "score");
        extra = extra == null ? Map.of() : Map.copyOf(extra);
    }
}
