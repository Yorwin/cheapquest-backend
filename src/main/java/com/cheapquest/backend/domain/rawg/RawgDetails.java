package com.cheapquest.backend.domain.rawg;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record RawgDetails(
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
        /**
         * Maximum value of RAWG's rating scale (always {@code 5} as of
         * the current API contract — every game uses a 0-5 star scale).
         * Not the highest rating this game has ever received: for that
         * see {@link #rating}. Carried through for round-trip fidelity
         * with the upstream API and the Firestore document.
         */
        Integer ratingTop,
        Integer metacritic,
        int additionsCount,
        int creatorsCount,
        int moviesCount,
        int screenshotsCount,
        List<DeveloperSummary> developers,
        List<PublisherSummary> publishers,
        List<RawgGenre> genres,
        List<RawgTag> tags,
        List<RawgPlatform> platforms,
        List<RawgPlatform> parentPlatforms,
        List<RawgDlc> dlcs,
        List<RawgCreator> creators,
        List<String> screenshots,
        Instant fetchedAt) {

    public RawgDetails {
        developers = developers == null ? List.of() : List.copyOf(developers);
        publishers = publishers == null ? List.of() : List.copyOf(publishers);
        genres = genres == null ? List.of() : List.copyOf(genres);
        tags = tags == null ? List.of() : List.copyOf(tags);
        platforms = platforms == null ? List.of() : List.copyOf(platforms);
        parentPlatforms = parentPlatforms == null ? List.of() : List.copyOf(parentPlatforms);
        dlcs = dlcs == null ? List.of() : List.copyOf(dlcs);
        creators = creators == null ? List.of() : List.copyOf(creators);
        screenshots = screenshots == null ? List.of() : List.copyOf(screenshots);
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(fetchedAt, "fetchedAt");
    }
}
