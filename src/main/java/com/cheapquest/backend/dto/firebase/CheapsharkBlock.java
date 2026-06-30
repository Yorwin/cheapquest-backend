package com.cheapquest.backend.dto.firebase;

import java.math.BigDecimal;
import java.util.List;

/**
 * The {@code cheapshark} sub-object of a game document. Mirrors what
 * {@link com.cheapquest.backend.domain.GameDeals} holds, minus the
 * {@code fetchedAt} (kept in the parent document metadata, not here).
 */
public record CheapsharkBlock(
        Boolean synced,
        String gameId,
        BigDecimal cheapestEver,
        OfferDto bestDeal,
        Integer offerCount,
        List<OfferDto> deals) {
}
