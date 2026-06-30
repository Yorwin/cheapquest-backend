package com.cheapquest.backend.dto.firebase;

import java.math.BigDecimal;

/**
 * Mirror of {@link com.cheapquest.backend.domain.Offer} shaped for Firestore
 * storage. Kept as its own record so a future domain change in {@code Offer}
 * does not silently rewrite historical Firestore documents.
 */
public record OfferDto(
        String storeId,
        String storeName,
        String storeIconUrl,
        BigDecimal price,
        BigDecimal retailPrice,
        BigDecimal savings,
        String dealUrl) {
}
