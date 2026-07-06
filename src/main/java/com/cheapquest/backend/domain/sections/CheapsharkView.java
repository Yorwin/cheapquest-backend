package com.cheapquest.backend.domain.sections;

import com.cheapquest.backend.domain.Offer;
import java.math.BigDecimal;
import java.util.List;

/**
 * Domain projection of the
 * {@link com.cheapquest.backend.dto.firebase.CheapsharkBlock}
 * block. Carries the four fields every "deals" section cares
 * about: the sync flag, the per-game best offer, the
 * historical cheapest price, and the full list of remaining
 * offers. {@code bestDeal} and {@code cheapestEver} are
 * nullable for games whose CheapShark data is still partial.
 */
public record CheapsharkView(
        boolean synced,
        Offer bestDeal,
        BigDecimal cheapestEver,
        Integer offerCount,
        List<Offer> offers) {

    public CheapsharkView {
        offers = offers == null ? List.of() : List.copyOf(offers);
    }
}
