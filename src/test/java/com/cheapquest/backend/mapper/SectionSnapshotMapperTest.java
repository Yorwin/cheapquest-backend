package com.cheapquest.backend.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cheapquest.backend.domain.Offer;
import com.cheapquest.backend.domain.sections.SectionItem;
import com.cheapquest.backend.domain.sections.SectionName;
import com.cheapquest.backend.domain.sections.SectionSnapshot;
import com.cheapquest.backend.dto.firebase.OfferDto;
import com.cheapquest.backend.dto.firebase.sections.SectionItemDto;
import com.cheapquest.backend.dto.firebase.sections.SectionSnapshotDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SectionSnapshotMapperTest {

    private static final Instant T = Instant.parse("2026-07-06T00:00:05Z");
    private static final LocalDate DAY = LocalDate.parse("2026-07-06");

    private static final Offer OFFER = new Offer(
            "1", "Steam", "https://example.com/steam.png",
            new BigDecimal("9.99"), new BigDecimal("29.99"),
            new BigDecimal("66.70"), "https://example.com/deal", null);

    private static final SectionItem ITEM = new SectionItem(
            "slug", "Title", OFFER, new BigDecimal("66.70"),
            Map.of("savingsPct", "66.70"), null);

    private final SectionSnapshotMapper mapper = new SectionSnapshotMapper();

    @Test
    void toDto_maps_name_to_slug_and_dates_to_iso() {
        SectionSnapshot snap = new SectionSnapshot(
                SectionName.MEJORES_PROMOS, DAY, T, 5, List.of(ITEM));
        SectionSnapshotDto dto = mapper.toDto(snap);

        assertThat(dto.name()).isEqualTo("mejores-promos");
        assertThat(dto.date()).isEqualTo("2026-07-06");
        assertThat(dto.computedAt()).isEqualTo("2026-07-06T00:00:05Z");
        assertThat(dto.totalCandidates()).isEqualTo(5);
        assertThat(dto.items()).hasSize(1);
    }

    @Test
    void toDto_converts_offer_to_offerDto() {
        SectionSnapshotDto dto = mapper.toDto(new SectionSnapshot(
                SectionName.MEJORES_PROMOS, DAY, T, 1, List.of(ITEM)));
        OfferDto offerDto = dto.items().get(0).bestDeal();
        assertThat(offerDto.storeId()).isEqualTo("1");
        assertThat(offerDto.storeName()).isEqualTo("Steam");
        assertThat(offerDto.price()).isEqualByComparingTo("9.99");
        assertThat(offerDto.savings()).isEqualByComparingTo("66.70");
    }

    @Test
    void toDto_rejects_null_snapshot() {
        assertThatThrownBy(() -> mapper.toDto(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("snapshot");
    }

    @Test
    void fromDto_rejects_null_dto() {
        assertThatThrownBy(() -> mapper.fromDto(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("dto");
    }

    @Test
    void fromDto_rejects_unknown_slug() {
        SectionSnapshotDto bad = new SectionSnapshotDto(
                "nope", "2026-07-06", "2026-07-06T00:00:05Z", 0, List.of());
        assertThatThrownBy(() -> mapper.fromDto(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void fromDto_parses_iso_dates_and_slug() {
        SectionSnapshotDto dto = new SectionSnapshotDto(
                "mejores-promos", "2026-07-06", "2026-07-06T00:00:05Z", 1,
                List.of(new SectionItemDto(
                        "slug", "Title",
                        new OfferDto("1", "Steam", null,
                                new BigDecimal("9.99"), new BigDecimal("29.99"),
                                new BigDecimal("66.70"), null, null),
                        new BigDecimal("66.70"),
                        Map.of("savingsPct", "66.70"), null)));
        SectionSnapshot snap = mapper.fromDto(dto);

        assertThat(snap.name()).isEqualTo(SectionName.MEJORES_PROMOS);
        assertThat(snap.date()).isEqualTo(DAY);
        assertThat(snap.computedAt()).isEqualTo(T);
        assertThat(snap.totalCandidates()).isEqualTo(1);
        assertThat(snap.items()).hasSize(1);
        assertThat(snap.items().get(0).bestDeal().storeName()).isEqualTo("Steam");
    }

    @Test
    void round_trip_through_dto_preserves_snapshot() {
        SectionSnapshot original = new SectionSnapshot(
                SectionName.MEJORES_PROMOS, DAY, T, 5, List.of(ITEM));
        SectionSnapshot back = mapper.fromDto(mapper.toDto(original));
        assertThat(back.name()).isEqualTo(original.name());
        assertThat(back.date()).isEqualTo(original.date());
        assertThat(back.computedAt()).isEqualTo(original.computedAt());
        assertThat(back.totalCandidates()).isEqualTo(original.totalCandidates());
        assertThat(back.items()).isEqualTo(original.items());
    }

    @Test
    void toDto_propagates_rawgDetails() {
        com.cheapquest.backend.domain.rawg.RawgDetails details =
                com.cheapquest.backend.fixtures.RawgDetailsFixtures.minimalDetails("slug", "Title");
        SectionItem item = new SectionItem("slug", "Title", OFFER, new BigDecimal("66.70"),
                Map.of("savingsPct", "66.70"), details);
        SectionSnapshotDto dto = mapper.toDto(new SectionSnapshot(
                SectionName.MEJORES_PROMOS, DAY, T, 1, List.of(item)));
        assertThat(dto.items().get(0).rawgDetails()).isNotNull();
        assertThat(dto.items().get(0).rawgDetails().name()).isEqualTo("Title");
    }

    @Test
    void fromDto_propagates_rawgDetails() {
        com.cheapquest.backend.dto.firebase.RawgDocumentDto rawg =
                com.cheapquest.backend.fixtures.RawgDocumentDtoFixtures
                        .full("slug", "Title").build();
        SectionSnapshotDto dto = new SectionSnapshotDto(
                "mejores-promos", "2026-07-06", "2026-07-06T00:00:05Z", 1,
                List.of(new SectionItemDto(
                        "slug", "Title",
                        new OfferDto("1", "Steam", null,
                                new BigDecimal("9.99"), new BigDecimal("29.99"),
                                new BigDecimal("66.70"), null, null),
                        new BigDecimal("66.70"),
                        Map.of("savingsPct", "66.70"),
                        rawg)));
        SectionSnapshot snap = mapper.fromDto(dto);
        assertThat(snap.items().get(0).rawgDetails()).isNotNull();
        assertThat(snap.items().get(0).rawgDetails().name()).isEqualTo("Title");
    }

    @Test
    void round_trip_preserves_rawgDetails() {
        com.cheapquest.backend.domain.rawg.RawgDetails details =
                com.cheapquest.backend.fixtures.RawgDetailsFixtures.minimalDetails("slug", "Title");
        SectionItem item = new SectionItem("slug", "Title", OFFER, new BigDecimal("66.70"),
                Map.of("savingsPct", "66.70"), details);
        SectionSnapshot original = new SectionSnapshot(
                SectionName.MEJORES_PROMOS, DAY, T, 1, List.of(item));
        SectionSnapshot back = mapper.fromDto(mapper.toDto(original));
        assertThat(back.items().get(0).rawgDetails()).isNotNull();
        assertThat(back.items().get(0).rawgDetails().name()).isEqualTo("Title");
    }
}
