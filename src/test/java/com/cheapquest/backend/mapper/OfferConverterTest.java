package com.cheapquest.backend.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.cheapquest.backend.domain.Offer;
import com.cheapquest.backend.dto.firebase.OfferDto;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OfferConverterTest {

    private static final Offer OFFER = new Offer(
            "1", "Steam", "https://example.com/steam.png",
            new BigDecimal("9.99"), new BigDecimal("29.99"),
            new BigDecimal("66.70"), "https://example.com/deal", null);

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
        assertThat(dto.firstSeenAt()).isNull();
    }

    @Test
    void toDomain_copies_every_field() {
        OfferDto dto = new OfferDto(
                "1", "Steam", "https://example.com/steam.png",
                new BigDecimal("9.99"), new BigDecimal("29.99"),
                new BigDecimal("66.70"), "https://example.com/deal", null);
        Offer back = OfferConverter.toDomain(dto);
        assertThat(back.storeId()).isEqualTo("1");
        assertThat(back.storeName()).isEqualTo("Steam");
        assertThat(back.storeIconUrl()).isEqualTo("https://example.com/steam.png");
        assertThat(back.price()).isEqualByComparingTo("9.99");
        assertThat(back.retailPrice()).isEqualByComparingTo("29.99");
        assertThat(back.savings()).isEqualByComparingTo("66.70");
        assertThat(back.dealUrl()).isEqualTo("https://example.com/deal");
        assertThat(back.firstSeenAt()).isNull();
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
                new BigDecimal("66.70"), null, null);
        Offer back = OfferConverter.toDomain(OfferConverter.toDto(withNulls));
        assertThat(back.storeIconUrl()).isNull();
        assertThat(back.dealUrl()).isNull();
        assertThat(back.firstSeenAt()).isNull();
    }

    @Test
    void firstSeenAt_is_serialised_as_iso_string() {
        Instant t = Instant.parse("2026-07-01T00:00:00Z");
        Offer offer = new Offer(
                "1", "Steam", null,
                new BigDecimal("9.99"), new BigDecimal("29.99"),
                new BigDecimal("66.70"), null, t);
        OfferDto dto = OfferConverter.toDto(offer);
        assertThat(dto.firstSeenAt()).isEqualTo("2026-07-01T00:00:00Z");
    }

    @Test
    void firstSeenAt_is_parsed_back_from_iso_string() {
        OfferDto dto = new OfferDto(
                "1", "Steam", null,
                new BigDecimal("9.99"), new BigDecimal("29.99"),
                new BigDecimal("66.70"), null,
                "2026-07-01T00:00:00Z");
        Offer back = OfferConverter.toDomain(dto);
        assertThat(back.firstSeenAt()).isEqualTo(Instant.parse("2026-07-01T00:00:00Z"));
    }

    @Test
    void unparseable_firstSeenAt_yields_null_on_read() {
        OfferDto dto = new OfferDto(
                "1", "Steam", null,
                new BigDecimal("9.99"), new BigDecimal("29.99"),
                new BigDecimal("66.70"), null,
                "not-a-date");
        Offer back = OfferConverter.toDomain(dto);
        assertThat(back.firstSeenAt()).isNull();
    }

    @Test
    void null_firstSeenAt_round_trips_as_null() {
        Offer offer = new Offer(
                "1", "Steam", null,
                new BigDecimal("9.99"), new BigDecimal("29.99"),
                new BigDecimal("66.70"), null, null);
        OfferDto dto = OfferConverter.toDto(offer);
        Offer back = OfferConverter.toDomain(dto);
        assertThat(back.firstSeenAt()).isNull();
    }
}
