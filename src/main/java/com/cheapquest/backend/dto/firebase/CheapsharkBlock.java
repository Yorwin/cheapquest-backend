package com.cheapquest.backend.dto.firebase;

import java.math.BigDecimal;
import java.util.List;

/**
 * The {@code cheapshark} sub-object of a game document. Mirrors what
 * {@link com.cheapquest.backend.domain.GameDeals} holds. The
 * {@code fetchedAt} is the ISO 8601 timestamp of the last CheapShark
 * fetch and is what the per-source cadence (see
 * {@code RefreshPolicy.refreshDealsMaxAgeHours}) reads to decide
 * whether the deals block is stale.
 */
public record CheapsharkBlock(
        Boolean synced,
        String gameId,
        BigDecimal cheapestEver,
        OfferDto bestDeal,
        Integer offerCount,
        List<OfferDto> deals,
        String fetchedAt) {

    public static CheapsharkBlock empty() {
        return new CheapsharkBlock(false, null, null, null, 0, List.of(), null);
    }
}
