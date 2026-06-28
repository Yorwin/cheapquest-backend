package com.cheapquest.backend.domain;

import java.math.BigDecimal;
import java.util.List;

public record GameDeals(
        String gameId,
        String searchTitle,
        String name,
        String internalName,
        String thumb,
        BigDecimal cheapestEver,
        int offerCount,
        Offer bestDeal,
        List<Offer> offers) {
}
