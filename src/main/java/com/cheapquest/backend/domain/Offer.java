package com.cheapquest.backend.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One CheapShark offer surfaced to the user. The
 * {@code firstSeenAt} timestamp records the first time this
 * exact offer (identified by {@code storeId}, {@code price} and
 * {@code savings}) became the per-game best deal in our
 * catalog. It is set on the first hydration that surfaces the
 * offer and preserved across subsequent hydrations as long as
 * the offer is the same; it is reset to "now" when the offer
 * changes (cheaper price, better savings, or a different
 * store), so the "nuevas ofertas" section can rank games
 * whose best deal improved in the recent window.
 *
 * <p>{@code firstSeenAt} is nullable because the field did
 * not exist when older documents were written; the backfill
 * script in {@code scripts/} fills it in for the existing
 * catalog.
 */
public record Offer(
        String storeId,
        String storeName,
        String storeIconUrl,
        BigDecimal price,
        BigDecimal retailPrice,
        BigDecimal savings,
        String dealUrl,
        Instant firstSeenAt) {

    public Offer {
        java.util.Objects.requireNonNull(storeId, "storeId");
        java.util.Objects.requireNonNull(storeName, "storeName");
        java.util.Objects.requireNonNull(price, "price");
        java.util.Objects.requireNonNull(retailPrice, "retailPrice");
        java.util.Objects.requireNonNull(savings, "savings");
    }
}
