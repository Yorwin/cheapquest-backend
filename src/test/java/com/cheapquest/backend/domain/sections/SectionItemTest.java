package com.cheapquest.backend.domain.sections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.cheapquest.backend.dto.firebase.OfferDto;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SectionItemTest {

    private static final OfferDto OFFER = new OfferDto(
            "1", "Steam", "https://example.com/steam.png",
            new BigDecimal("9.99"), new BigDecimal("29.99"),
            new BigDecimal("66.70"), "https://example.com/deal");

    @Test
    void rejects_null_slug() {
        assertThatNullPointerException()
                .isThrownBy(() -> new SectionItem(null, "t", OFFER, BigDecimal.ZERO, Map.of()))
                .withMessageContaining("slug");
    }

    @Test
    void rejects_null_title() {
        assertThatNullPointerException()
                .isThrownBy(() -> new SectionItem("s", null, OFFER, BigDecimal.ZERO, Map.of()))
                .withMessageContaining("title");
    }

    @Test
    void rejects_null_bestDeal() {
        assertThatNullPointerException()
                .isThrownBy(() -> new SectionItem("s", "t", null, BigDecimal.ZERO, Map.of()))
                .withMessageContaining("bestDeal");
    }

    @Test
    void rejects_null_score() {
        assertThatNullPointerException()
                .isThrownBy(() -> new SectionItem("s", "t", OFFER, null, Map.of()))
                .withMessageContaining("score");
    }

    @Test
    void accepts_null_extra_and_returns_emptyMap() {
        SectionItem item = new SectionItem("s", "t", OFFER, BigDecimal.ZERO, null);
        assertThat(item.extra()).isEmpty();
    }

    @Test
    void extra_is_defensive_copy() {
        Map<String, String> mutable = new HashMap<>();
        mutable.put("k", "v");
        SectionItem item = new SectionItem("s", "t", OFFER, BigDecimal.ZERO, mutable);
        mutable.put("k2", "v2");
        assertThat(item.extra()).containsOnlyKeys("k");
    }

    @Test
    void extra_is_unmodifiable() {
        SectionItem item = new SectionItem("s", "t", OFFER, BigDecimal.ZERO, Map.of("k", "v"));
        assertThat(item.extra()).isUnmodifiable();
    }
}
