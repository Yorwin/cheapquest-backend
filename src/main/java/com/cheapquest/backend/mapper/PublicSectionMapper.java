package com.cheapquest.backend.mapper;

import com.cheapquest.backend.domain.sections.SectionItem;
import com.cheapquest.backend.domain.sections.SectionName;
import com.cheapquest.backend.domain.sections.SectionSnapshot;
import com.cheapquest.backend.dto.admin.SectionsResponseDto;
import com.cheapquest.backend.dto.public_.PublicSectionDto;
import com.cheapquest.backend.dto.public_.PublicSectionListDto;
import com.cheapquest.backend.service.sections.SectionsService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Boundary between the sections domain / service types and
 * the public + admin wire DTOs. The persistence DTO
 * (Firestore-shaped) is owned by the store; this mapper
 * exists so the public and admin contracts can evolve
 * without rewriting historical Firestore documents.
 *
 * <p>Stateless and thread-safe. Methods are pure: no I/O,
 * no clock, no caching.
 */
public final class PublicSectionMapper {

    public PublicSectionDto toPublic(SectionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        List<PublicSectionDto.Item> items = new ArrayList<>(snapshot.items().size());
        for (SectionItem i : snapshot.items()) {
            items.add(toItem(i));
        }
        return new PublicSectionDto(
                snapshot.name().slug(),
                snapshot.date().toString(),
                snapshot.computedAt().toString(),
                snapshot.totalCandidates(),
                List.copyOf(items));
    }

    public PublicSectionListDto toPublicList(Map<SectionName, SectionSnapshot> latest) {
        Objects.requireNonNull(latest, "latest");
        List<PublicSectionListDto.Entry> entries = new ArrayList<>(latest.size());
        for (SectionName n : SectionName.values()) {
            SectionSnapshot s = latest.get(n);
            if (s == null) {
                continue;
            }
            entries.add(new PublicSectionListDto.Entry(
                    n.slug(),
                    s.date().toString(),
                    s.totalCandidates()));
        }
        return new PublicSectionListDto("ok", entries.size(), List.copyOf(entries));
    }

    public SectionsResponseDto toAdminResponse(List<SectionsService.Report> reports,
            long totalDurationMs) {
        Objects.requireNonNull(reports, "reports");
        int processed = 0;
        int failed = 0;
        List<SectionsResponseDto.SectionSummary> summaries =
                new ArrayList<>(reports.size());
        for (SectionsService.Report r : reports) {
            switch (r.status()) {
                case COMPLETED -> processed++;
                case FAILED -> failed++;
                case SKIPPED_NO_BUILDER -> { /* not counted in top-level rollup */ }
            }
            summaries.add(new SectionsResponseDto.SectionSummary(
                    r.name().slug(),
                    r.status().name(),
                    r.totalCandidates(),
                    r.itemsKept(),
                    r.durationMs(),
                    r.error()));
        }
        return new SectionsResponseDto(
                failed == 0 ? "completed" : "partial",
                processed, failed, totalDurationMs,
                List.copyOf(summaries));
    }

    private static PublicSectionDto.Item toItem(SectionItem i) {
        return new PublicSectionDto.Item(
                i.slug(),
                i.title(),
                OfferConverter.toDto(i.bestDeal()),
                i.score(),
                i.extra());
    }
}
