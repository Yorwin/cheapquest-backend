package com.cheapquest.backend.dto.firebase;

import java.math.BigDecimal;

/**
 * Mirror of {@link com.cheapquest.backend.domain.Offer} shaped for Firestore
 * storage. Kept as its own record so a future domain change in {@code Offer}
 * does not silently rewrite historical Firestore documents.
 *
 * <p>{@code firstSeenAt} is the ISO-8601 string form of
 * {@link com.cheapquest.backend.domain.Offer#firstSeenAt()}, the first time
 * this exact offer (by {@code storeId}, {@code price} and {@code savings})
 * became the per-game best deal. It is the input the "nuevas ofertas"
 * section filters and ranks on. Nullable because older documents were
 * written before the field existed; the backfill script populates it for
 * the existing catalog.
 */
public record OfferDto(
        String storeId,
        String storeName,
        String storeIconUrl,
        BigDecimal price,
        BigDecimal retailPrice,
        BigDecimal savings,
        String dealUrl,
        String firstSeenAt) {
}
