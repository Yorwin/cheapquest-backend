package com.cheapquest.backend.mapper;

import com.cheapquest.backend.domain.Offer;
import com.cheapquest.backend.dto.firebase.OfferDto;
import com.cheapquest.backend.util.InstantUtils;
import java.time.Instant;

/**
 * Single source of truth for the
 * {@link com.cheapquest.backend.domain.Offer} <-> {@link OfferDto}
 * conversion. Both records have an identical 1:1 field shape,
 * so the conversion is mechanical, but the alternative
 * (duplicating the field copy in every mapper that needs it)
 * is fragile: a future field on {@code Offer} (e.g.
 * {@code firstSeenAt} for the "nuevas ofertas" section) would
 * silently drop the new field on any mapper that forgets to
 * update.
 *
 * <p>Live in the {@code mapper} package so the
 * domain-to-Firestore boundary stays one-way: the DTO does
 * not import the domain, the domain does not import the DTO,
 * and the conversion is the only place that knows about both.
 *
 * <p>Stateless and thread-safe. Methods are intentionally
 * static: there is no per-instance state and the call sites
 * are utility-style.
 */
public final class OfferConverter {

    private OfferConverter() {
    }

    public static OfferDto toDto(Offer o) {
        Instant firstSeen = o.firstSeenAt();
        return new OfferDto(
                o.storeId(), o.storeName(), o.storeIconUrl(),
                o.price(), o.retailPrice(), o.savings(), o.dealUrl(),
                firstSeen == null ? null : firstSeen.toString());
    }

    public static Offer toDomain(OfferDto d) {
        return new Offer(
                d.storeId(), d.storeName(), d.storeIconUrl(),
                d.price(), d.retailPrice(), d.savings(), d.dealUrl(),
                InstantUtils.parseOrNull(d.firstSeenAt()));
    }
}
