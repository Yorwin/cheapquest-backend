package com.cheapquest.backend.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record GameDeals(
        String gameId,
        String searchTitle,
        String name,
        String internalName,
        String thumb,
        BigDecimal cheapestEver,
        int offerCount,
        Offer bestDeal,
        List<Offer> offers,
        Instant fetchedAt) {

    public GameDeals {
        Objects.requireNonNull(fetchedAt, "fetchedAt");
    }
}
