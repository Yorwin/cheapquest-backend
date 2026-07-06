package com.cheapquest.backend.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.cheapquest.backend.domain.Offer;
import com.cheapquest.backend.dto.firebase.OfferDto;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class OfferConverterTest {

    private static final Offer OFFER = new Offer(
            "1", "Steam", "https://example.com/steam.png",
            new BigDecimal("9.99"), new BigDecimal("29.99"),
            new BigDecimal("66.70"), "https://example.com/deal");

    @Test
    void toDto_copies_every_field() {
        OfferDto dto = OfferConverter.toDto(OFFER);
        assertThat(dto.storeId()).isEqualTo("1");
        assertThat(dto.storeName()).isEqualTo("Steam");
        assertThat(dto.storeIconUrl()).isEqualTo("https://example.com/steam.png");
        assertThat(dto.price()).isEqualByComparingTo("9.99");
        assertThat(dto.retailPrice()).isEqualByComparingTo("29.99");
        assertThat(dto.savings()).isEqualByComparingTo("66.70");
        assertThat(dto.dealUrl()).isEqualTo("https://example.com/deal");
    }

    @Test
    void toDomain_copies_every_field() {
        OfferDto dto = new OfferDto(
                "1", "Steam", "https://example.com/steam.png",
                new BigDecimal("9.99"), new BigDecimal("29.99"),
                new BigDecimal("66.70"), "https://example.com/deal");
        Offer back = OfferConverter.toDomain(dto);
        assertThat(back.storeId()).isEqualTo("1");
        assertThat(back.storeName()).isEqualTo("Steam");
        assertThat(back.storeIconUrl()).isEqualTo("https://example.com/steam.png");
        assertThat(back.price()).isEqualByComparingTo("9.99");
        assertThat(back.retailPrice()).isEqualByComparingTo("29.99");
        assertThat(back.savings()).isEqualByComparingTo("66.70");
        assertThat(back.dealUrl()).isEqualTo("https://example.com/deal");
    }

    @Test
    void round_trip_preserves_everything() {
        OfferDto dto = OfferConverter.toDto(OFFER);
        Offer back = OfferConverter.toDomain(dto);
        assertThat(back).isEqualTo(OFFER);
    }

    @Test
    void nullables_are_preserved() {
        Offer withNulls = new Offer(
                "1", "Steam", null,
                new BigDecimal("9.99"), new BigDecimal("29.99"),
                new BigDecimal("66.70"), null);
        Offer back = OfferConverter.toDomain(OfferConverter.toDto(withNulls));
        assertThat(back.storeIconUrl()).isNull();
        assertThat(back.dealUrl()).isNull();
    }
}
