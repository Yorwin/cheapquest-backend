package com.cheapquest.backend.dto.firebase;

import com.cheapquest.backend.domain.rawg.DeveloperSummary;
import com.cheapquest.backend.domain.rawg.PublisherSummary;
import com.cheapquest.backend.domain.rawg.RawgClip;
import com.cheapquest.backend.domain.rawg.RawgCreator;
import com.cheapquest.backend.domain.rawg.RawgDlc;
import com.cheapquest.backend.domain.rawg.RawgEsrbRating;
import com.cheapquest.backend.domain.rawg.RawgGenre;
import com.cheapquest.backend.domain.rawg.RawgPlatform;
import com.cheapquest.backend.domain.rawg.RawgRating;
import com.cheapquest.backend.domain.rawg.RawgScreenshot;
import com.cheapquest.backend.domain.rawg.RawgStoreEntry;
import com.cheapquest.backend.domain.rawg.RawgTag;
import java.util.List;
import java.util.Map;

/**
 * Typed Firestore representation of the RAWG payload. Mirrors
 * {@link com.cheapquest.backend.domain.rawg.RawgDetails} field
 * by field, with two adjustments that matter for the persistence
 * shape:
 *
 * <ul>
 *   <li>{@code fetchedAt} is a {@code String} (ISO-8601) rather
 *       than an {@link java.time.Instant} so the document does
 *       not depend on a custom GSON {@code Instant} adapter; and
 *   </li>
 *   <li>the count fields are {@link Integer} (boxed) rather than
 *       {@code int} so a missing field deserialises to
 *       {@code null} instead of zero, and the consistency checker
 *       can treat a missing count as "unknown" rather than
 *       "zero".</li>
 * </ul>
 *
 * <p>Replaces the previous {@code Map<String, Object>} representation
 * of {@code rawg.data}. With a typed shape, renaming a field in
 * {@code RawgDetails} is a compile error rather than a silent
 * production bug. The field names match the JSON keys produced by
 * the previous {@code rawgDetailsToMap} round-trip, so existing
 * documents on disk keep loading without a migration.
 */
public record RawgDocumentDto(
        String slug,
        String name,
        String nameOriginal,
        String released,
        String description,
        String descriptionRaw,
        String headerImage,
        String trailerUrl,
        String website,
        Double rating,
        Integer ratingTop,
        Integer metacritic,
        Integer additionsCount,
        Integer creatorsCount,
        Integer moviesCount,
        Integer screenshotsCount,
        List<DeveloperSummary> developers,
        List<PublisherSummary> publishers,
        List<RawgGenre> genres,
        List<RawgTag> tags,
        List<RawgPlatform> platforms,
        List<RawgPlatform> parentPlatforms,
        List<RawgDlc> dlcs,
        List<RawgCreator> creators,
        List<String> screenshots,
        Boolean tba,
        String updated,
        String backgroundImageAdditional,
        List<RawgRating> ratings,
        Integer ratingsCount,
        Integer reviewsCount,
        Integer reviewsTextCount,
        String metacriticUrl,
        Integer playtime,
        Integer parentsCount,
        Integer gameSeriesCount,
        Integer achievementsCount,
        Integer parentAchievementsCount,
        RawgClip clip,
        List<String> alternativeNames,
        RawgEsrbRating esrbRating,
        List<RawgStoreEntry> stores,
        List<RawgScreenshot> shortScreenshots,
        Map<String, Integer> addedByStatus,
        Map<String, Integer> reactions,
        Integer suggestionsCount,
        String fetchedAt) {
}
