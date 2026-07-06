package com.cheapquest.backend.mapper;

import com.cheapquest.backend.domain.sections.SectionItem;
import com.cheapquest.backend.domain.sections.SectionName;
import com.cheapquest.backend.domain.sections.SectionSnapshot;
import com.cheapquest.backend.dto.firebase.sections.SectionItemDto;
import com.cheapquest.backend.dto.firebase.sections.SectionSnapshotDto;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Boundary between the sections domain types
 * ({@link SectionSnapshot}, {@link SectionItem}) and the
 * Firestore DTOs ({@link SectionSnapshotDto},
 * {@link SectionItemDto}). Dates are converted to ISO-8601
 * strings on the way out and parsed back on the way in so the
 * document does not depend on a custom
 * {@code LocalDate}/{@code Instant} adapter.
 *
 * <p>The mapper is stateless and thread-safe: every method
 * is pure, no I/O, no caching. A new instance per request is
 * fine; the {@code SectionsService} holds a single instance.
 */
public final class SectionSnapshotMapper {

    public SectionSnapshotDto toDto(SectionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        List<SectionItemDto> itemDtos = snapshot.items().stream()
                .map(this::toItemDto)
                .toList();
        return new SectionSnapshotDto(
                snapshot.name().slug(),
                snapshot.date().toString(),
                snapshot.computedAt().toString(),
                snapshot.totalCandidates(),
                List.copyOf(itemDtos));
    }

    public SectionSnapshot fromDto(SectionSnapshotDto dto) {
        Objects.requireNonNull(dto, "dto");
        SectionName name = SectionName.fromSlug(dto.name())
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown section slug in stored snapshot: " + dto.name()));
        LocalDate date = LocalDate.parse(dto.date());
        Instant computedAt = Instant.parse(dto.computedAt());
        List<SectionItem> items = dto.items().stream()
                .map(this::toItem)
                .toList();
        return new SectionSnapshot(name, date, computedAt, dto.totalCandidates(), items);
    }

    private SectionItemDto toItemDto(SectionItem item) {
        return new SectionItemDto(
                item.slug(),
                item.title(),
                OfferConverter.toDto(item.bestDeal()),
                item.score(),
                item.extra());
    }

    private SectionItem toItem(SectionItemDto dto) {
        return new SectionItem(
                dto.slug(),
                dto.title(),
                OfferConverter.toDomain(dto.bestDeal()),
                dto.score(),
                dto.extra());
    }
}
