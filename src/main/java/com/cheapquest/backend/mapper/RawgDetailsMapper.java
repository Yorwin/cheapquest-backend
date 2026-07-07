package com.cheapquest.backend.mapper;

import com.cheapquest.backend.domain.rawg.RawgDetails;
import com.cheapquest.backend.dto.firebase.RawgDocumentDto;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Boundary between the RAWG persistence DTO
 * ({@link RawgDocumentDto}) and the RAWG domain record
 * ({@link RawgDetails}). The two shapes are 1:1 by field
 * name; the only differences are:
 * <ul>
 *   <li>The DTO uses boxed {@code Integer} for count fields
 *       and {@code Boolean} for {@code tba} so a missing
 *       field deserialises to {@code null}; the domain uses
 *       primitives ({@code int} / {@code boolean}) and
 *       defaults to {@code 0} / {@code false} for missing
 *       values.</li>
 *   <li>The DTO carries {@code fetchedAt} as a string (ISO-8601)
 *       to avoid depending on a custom GSON {@code Instant}
 *       adapter in the persistence layer; the domain uses
 *       {@link Instant} because the catalog walk and the
 *       public API both want a typed timestamp.</li>
 * </ul>
 *
 * <p>Used by {@link GameViewMapper} (toDomain: DTO read from
 * Firestore becomes a {@link RawgDetails} attached to
 * {@code GameView.rawgDetails}) and by
 * {@link SectionSnapshotMapper} (both directions: write
 * {@code SectionItemDto.rawgDetails} on the way out, read it
 * back on the way in).
 *
 * <p>Stateless and thread-safe. The lists and maps are
 * defensively copied by {@code RawgDetails}' own canonical
 * constructor, so this mapper can hand back the references
 * the DTO provided without aliasing surprises.
 */
public final class RawgDetailsMapper {

    private final Clock clock;

    public RawgDetailsMapper() {
        this(Clock.systemUTC());
    }

    public RawgDetailsMapper(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public RawgDetails toDomain(RawgDocumentDto dto) {
        Objects.requireNonNull(dto, "dto");
        Instant fetchedAt = dto.fetchedAt() == null
                ? Instant.now(clock)
                : Instant.parse(dto.fetchedAt());
        return new RawgDetails(
                dto.slug(),
                dto.name(),
                dto.nameOriginal(),
                dto.released(),
                dto.description(),
                dto.descriptionRaw(),
                dto.headerImage(),
                dto.trailerUrl(),
                dto.website(),
                dto.rating(),
                dto.ratingTop(),
                dto.metacritic(),
                intOrZero(dto.additionsCount()),
                intOrZero(dto.creatorsCount()),
                intOrZero(dto.moviesCount()),
                intOrZero(dto.screenshotsCount()),
                nullSafe(dto.developers()),
                nullSafe(dto.publishers()),
                nullSafe(dto.genres()),
                nullSafe(dto.tags()),
                nullSafe(dto.platforms()),
                nullSafe(dto.parentPlatforms()),
                nullSafe(dto.dlcs()),
                nullSafe(dto.creators()),
                nullSafe(dto.screenshots()),
                Boolean.TRUE.equals(dto.tba()),
                dto.updated(),
                dto.backgroundImageAdditional(),
                nullSafe(dto.ratings()),
                intOrZero(dto.ratingsCount()),
                intOrZero(dto.reviewsCount()),
                intOrZero(dto.reviewsTextCount()),
                dto.metacriticUrl(),
                intOrZero(dto.playtime()),
                intOrZero(dto.parentsCount()),
                intOrZero(dto.gameSeriesCount()),
                intOrZero(dto.achievementsCount()),
                intOrZero(dto.parentAchievementsCount()),
                dto.clip(),
                nullSafe(dto.alternativeNames()),
                dto.esrbRating(),
                nullSafe(dto.stores()),
                nullSafe(dto.shortScreenshots()),
                nullSafe(dto.addedByStatus()),
                nullSafe(dto.reactions()),
                intOrZero(dto.suggestionsCount()),
                fetchedAt);
    }

    public RawgDocumentDto toDto(RawgDetails details) {
        Objects.requireNonNull(details, "details");
        String fetchedAt = details.fetchedAt() == null
                ? null
                : details.fetchedAt().toString();
        return new RawgDocumentDto(
                details.slug(),
                details.name(),
                details.nameOriginal(),
                details.released(),
                details.description(),
                details.descriptionRaw(),
                details.headerImage(),
                details.trailerUrl(),
                details.website(),
                details.rating(),
                details.ratingTop(),
                details.metacritic(),
                details.additionsCount(),
                details.creatorsCount(),
                details.moviesCount(),
                details.screenshotsCount(),
                List.copyOf(details.developers()),
                List.copyOf(details.publishers()),
                List.copyOf(details.genres()),
                List.copyOf(details.tags()),
                List.copyOf(details.platforms()),
                List.copyOf(details.parentPlatforms()),
                List.copyOf(details.dlcs()),
                List.copyOf(details.creators()),
                List.copyOf(details.screenshots()),
                details.tba(),
                details.updated(),
                details.backgroundImageAdditional(),
                List.copyOf(details.ratings()),
                details.ratingsCount(),
                details.reviewsCount(),
                details.reviewsTextCount(),
                details.metacriticUrl(),
                details.playtime(),
                details.parentsCount(),
                details.gameSeriesCount(),
                details.achievementsCount(),
                details.parentAchievementsCount(),
                details.clip(),
                List.copyOf(details.alternativeNames()),
                details.esrbRating(),
                List.copyOf(details.stores()),
                List.copyOf(details.shortScreenshots()),
                Map.copyOf(details.addedByStatus()),
                Map.copyOf(details.reactions()),
                details.suggestionsCount(),
                fetchedAt);
    }

    private static int intOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static <K, V> Map<K, V> nullSafe(Map<K, V> map) {
        return map == null ? Map.of() : map;
    }
}
