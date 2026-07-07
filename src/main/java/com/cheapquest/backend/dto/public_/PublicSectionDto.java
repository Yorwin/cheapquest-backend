package com.cheapquest.backend.dto.public_;

import com.cheapquest.backend.domain.rawg.RawgDetails;
import com.cheapquest.backend.dto.firebase.OfferDto;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Public read DTO for one section's snapshot. Returned by
 * {@code GET /sections/{name}?date=YYYY-MM-DD|live}.
 *
 * <p>The shape is the same as the Firestore DTO at the
 * field level, but lives in {@code dto/public/} so the
 * public contract can evolve independently of the
 * persistence contract (e.g. rename a field for the front
 * without rewriting historical Firestore documents). The
 * mapper that bridges the two lives in
 * {@code mapper/PublicSectionMapper}.
 *
 * <p>{@code rawgDetails} is the full RAWG payload
 * (description, genres, tags, platforms, developers,
 * publishers, etc.) so the front can render rich game
 * cards from a single API call without re-fetching from
 * RAWG. Nullable for backward compatibility with snapshots
 * written before this field was added.
 */
public record PublicSectionDto(
        String name,
        String date,
        String computedAt,
        int totalCandidates,
        List<Item> items) {

    public record Item(
            String slug,
            String title,
            OfferDto bestDeal,
            BigDecimal score,
            Map<String, String> extra,
            RawgDetails rawgDetails) {
    }
}
