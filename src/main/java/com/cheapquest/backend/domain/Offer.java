package com.cheapquest.backend.domain;

import java.math.BigDecimal;

public record Offer(
        String storeId,
        String storeName,
        String storeIconUrl,
        BigDecimal price,
        BigDecimal retailPrice,
        BigDecimal savings,
        String dealUrl) {

    public Offer {
        java.util.Objects.requireNonNull(storeId, "storeId");
        java.util.Objects.requireNonNull(storeName, "storeName");
        java.util.Objects.requireNonNull(price, "price");
        java.util.Objects.requireNonNull(retailPrice, "retailPrice");
        java.util.Objects.requireNonNull(savings, "savings");
    }
}
