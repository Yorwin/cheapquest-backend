package com.cheapquest.backend.domain.sections;

import static org.assertj.core.api.Assertions.assertThat;

import com.cheapquest.backend.domain.Offer;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CheapsharkViewTest {

    private static final Offer OFFER = new Offer(
            "1", "Steam", null,
            new BigDecimal("9.99"), new BigDecimal("29.99"),
            new BigDecimal("66.70"), null, null);

    @Test
    void accepts_null_offers_and_returns_emptyList() {
        CheapsharkView v = new CheapsharkView(false, null, null, null, null);
        assertThat(v.offers()).isEmpty();
        assertThat(v.synced()).isFalse();
        assertThat(v.bestDeal()).isNull();
        assertThat(v.cheapestEver()).isNull();
        assertThat(v.offerCount()).isNull();
    }

    @Test
    void offers_is_defensive_copy() {
        List<Offer> mutable = new ArrayList<>();
        mutable.add(OFFER);
        CheapsharkView v = new CheapsharkView(true, OFFER, null, 1, mutable);
        mutable.clear();
        assertThat(v.offers()).hasSize(1);
    }

    @Test
    void offers_is_unmodifiable() {
        CheapsharkView v = new CheapsharkView(true, OFFER, null, 1, List.of(OFFER));
        assertThat(v.offers()).isUnmodifiable();
    }
}
