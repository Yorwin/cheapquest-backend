package com.cheapquest.backend.dto.firebase.sections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.cheapquest.backend.dto.firebase.OfferDto;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SectionItemDtoTest {

    private static final OfferDto OFFER = new OfferDto(
            "1", "Steam", "https://example.com/steam.png",
            new BigDecimal("9.99"), new BigDecimal("29.99"),
            new BigDecimal("66.70"), "https://example.com/deal", null);

    @Test
    void rejects_null_slug() {
        assertThatNullPointerException()
                .isThrownBy(() -> new SectionItemDto(null, "t", OFFER, BigDecimal.ZERO, java.util.Map.of(), null))
                .withMessageContaining("slug");
    }

    @Test
    void rejects_null_title() {
        assertThatNullPointerException()
                .isThrownBy(() -> new SectionItemDto("s", null, OFFER, BigDecimal.ZERO, java.util.Map.of(), null))
                .withMessageContaining("title");
    }

    @Test
    void rejects_null_bestDeal() {
        assertThatNullPointerException()
                .isThrownBy(() -> new SectionItemDto("s", "t", null, BigDecimal.ZERO, java.util.Map.of(), null))
                .withMessageContaining("bestDeal");
    }

    @Test
    void rejects_null_score() {
        assertThatNullPointerException()
                .isThrownBy(() -> new SectionItemDto("s", "t", OFFER, null, java.util.Map.of(), null))
                .withMessageContaining("score");
    }

    @Test
    void accepts_null_extra_and_returns_emptyMap() {
        SectionItemDto dto = new SectionItemDto("s", "t", OFFER, BigDecimal.ZERO, null, null);
        assertThat(dto.extra()).isEmpty();
    }

    @Test
    void extra_is_defensive_copy() {
        java.util.Map<String, String> mutable = new java.util.HashMap<>();
        mutable.put("k", "v");
        SectionItemDto dto = new SectionItemDto("s", "t", OFFER, BigDecimal.ZERO, mutable, null);
        mutable.put("k2", "v2");
        assertThat(dto.extra()).containsOnlyKeys("k");
    }

    @Test
    void extra_is_unmodifiable() {
        SectionItemDto dto = new SectionItemDto("s", "t", OFFER, BigDecimal.ZERO, java.util.Map.of("k", "v"), null);
        assertThat(dto.extra()).isUnmodifiable();
    }

    @Test
    void accepts_null_rawgDetails() {
        SectionItemDto dto = new SectionItemDto("s", "t", OFFER, BigDecimal.ZERO, java.util.Map.of(), null);
        assertThat(dto.rawgDetails()).isNull();
    }
}
