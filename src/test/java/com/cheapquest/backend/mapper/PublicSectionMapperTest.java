package com.cheapquest.backend.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.cheapquest.backend.domain.Offer;
import com.cheapquest.backend.domain.sections.SectionItem;
import com.cheapquest.backend.domain.sections.SectionName;
import com.cheapquest.backend.domain.sections.SectionSnapshot;
import com.cheapquest.backend.dto.admin.SectionsResponseDto;
import com.cheapquest.backend.dto.public_.PublicSectionDto;
import com.cheapquest.backend.dto.public_.PublicSectionListDto;
import com.cheapquest.backend.service.sections.SectionsService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PublicSectionMapperTest {

    private static final Instant T = Instant.parse("2026-07-06T00:00:05Z");
    private static final LocalDate DAY = LocalDate.parse("2026-07-06");
    private static final Offer OFFER = new Offer(
            "1", "Steam", "https://example.com/steam.png",
            new BigDecimal("9.99"), new BigDecimal("29.99"),
            new BigDecimal("66.70"), "https://example.com/deal");
    private static final SectionItem ITEM = new SectionItem(
            "slug", "Title", OFFER, new BigDecimal("66.70"),
            Map.of("savingsPct", "66.70"));

    private final PublicSectionMapper mapper = new PublicSectionMapper();

    @Test
    void toPublic_maps_snapshot_to_dto() {
        SectionSnapshot snap = new SectionSnapshot(
                SectionName.MEJORES_PROMOS, DAY, T, 5, List.of(ITEM));
        PublicSectionDto dto = mapper.toPublic(snap);

        assertThat(dto.name()).isEqualTo("mejores-promos");
        assertThat(dto.date()).isEqualTo("2026-07-06");
        assertThat(dto.computedAt()).isEqualTo("2026-07-06T00:00:05Z");
        assertThat(dto.totalCandidates()).isEqualTo(5);
        assertThat(dto.items()).hasSize(1);
        PublicSectionDto.Item item = dto.items().get(0);
        assertThat(item.slug()).isEqualTo("slug");
        assertThat(item.bestDeal().storeName()).isEqualTo("Steam");
        assertThat(item.extra()).containsEntry("savingsPct", "66.70");
    }

    @Test
    void toPublic_rejects_null_snapshot() {
        org.assertj.core.api.Assertions.assertThatNullPointerException()
                .isThrownBy(() -> mapper.toPublic(null));
    }

    @Test
    void toPublicList_iterates_section_names_in_enum_order_skipping_absent() {
        SectionSnapshot promos = new SectionSnapshot(
                SectionName.MEJORES_PROMOS, DAY, T, 5, List.of(ITEM));
        SectionSnapshot vintage = new SectionSnapshot(
                SectionName.VINTAGE, DAY, T, 8, List.of());
        Map<SectionName, SectionSnapshot> latest = new EnumMap<>(SectionName.class);
        latest.put(SectionName.MEJORES_PROMOS, promos);
        latest.put(SectionName.VINTAGE, vintage);
        // POPULARES, NUEVAS_OFERTAS, BAJOS_HISTORICOS absent on purpose.

        PublicSectionListDto dto = mapper.toPublicList(latest);

        assertThat(dto.status()).isEqualTo("ok");
        assertThat(dto.count()).isEqualTo(2);
        // EnumMap iterates in SectionName declaration order
        // (POPULARES, NUEVAS_OFERTAS, VINTAGE, MEJORES_PROMOS, BAJOS_HISTORICOS);
        // we inserted VINTAGE and MEJORES_PROMOS in that order so the
        // present ones come out in declaration order.
        assertThat(dto.sections()).extracting(PublicSectionListDto.Entry::name)
                .containsExactly("vintage", "mejores-promos");
    }

    @Test
    void toPublicList_with_empty_map_yields_empty_sections() {
        PublicSectionListDto dto = mapper.toPublicList(new EnumMap<>(SectionName.class));
        assertThat(dto.count()).isZero();
        assertThat(dto.sections()).isEmpty();
    }

    @Test
    void toAdminResponse_rolls_up_completed_and_failed_counts() {
        List<SectionsService.Report> reports = List.of(
                new SectionsService.Report(
                        SectionName.MEJORES_PROMOS, SectionsService.Status.COMPLETED,
                        5, 1, 100L, null),
                new SectionsService.Report(
                        SectionName.POPULARES, SectionsService.Status.FAILED,
                        0, 0, 200L, "boom"),
                new SectionsService.Report(
                        SectionName.NUEVAS_OFERTAS, SectionsService.Status.SKIPPED_NO_BUILDER,
                        0, 0, 5L, null));

        SectionsResponseDto dto = mapper.toAdminResponse(reports, 305L);

        assertThat(dto.status()).isEqualTo("partial");
        assertThat(dto.processed()).isEqualTo(1);
        assertThat(dto.failed()).isEqualTo(1);
        assertThat(dto.durationMs()).isEqualTo(305L);
        assertThat(dto.sections()).hasSize(3);
        assertThat(dto.sections().get(0).name()).isEqualTo("mejores-promos");
        assertThat(dto.sections().get(0).status()).isEqualTo("COMPLETED");
        assertThat(dto.sections().get(0).error()).isNull();
        assertThat(dto.sections().get(1).name()).isEqualTo("populares");
        assertThat(dto.sections().get(1).status()).isEqualTo("FAILED");
        assertThat(dto.sections().get(1).error()).contains("boom");
        assertThat(dto.sections().get(2).status()).isEqualTo("SKIPPED_NO_BUILDER");
    }

    @Test
    void toAdminResponse_with_no_failures_status_is_completed() {
        List<SectionsService.Report> reports = List.of(
                new SectionsService.Report(
                        SectionName.MEJORES_PROMOS, SectionsService.Status.COMPLETED,
                        5, 1, 100L, null));
        SectionsResponseDto dto = mapper.toAdminResponse(reports, 100L);
        assertThat(dto.status()).isEqualTo("completed");
    }
}
