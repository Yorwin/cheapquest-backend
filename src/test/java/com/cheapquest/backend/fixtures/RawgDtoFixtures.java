package com.cheapquest.backend.fixtures;

import com.cheapquest.backend.dto.rawg.RawgClipDto;
import com.cheapquest.backend.dto.rawg.RawgGameDto;
import com.cheapquest.backend.dto.rawg.RawgGenreDto;
import com.cheapquest.backend.dto.rawg.RawgPlatformEntryDto;
import com.cheapquest.backend.dto.rawg.RawgTagDto;
import java.util.List;

/**
 * Shared builder for {@link RawgGameDto} instances used by multiple tests.
 * Only the fields a test typically cares about (slug, name, description, dates,
 * URLs, count fields, genres, tags, platforms, clip) are exposed; the rest of
 * the 40-field DTO is filled with sensible defaults (zeros, nulls, false).
 */
public final class RawgDtoFixtures {

    private RawgDtoFixtures() {
    }

    public static GameDtoBuilder dto(String slug, String name) {
        return new GameDtoBuilder(slug, name);
    }

    public static RawgGameDto minimalGame(String slug, String name) {
        return dto(slug, name).build();
    }

    public static RawgGameDto detailWithCounts(String slug, String name,
            int additions, int creators, int movies, int screenshots) {
        return dto(slug, name)
                .additionsCount(additions)
                .creatorsCount(creators)
                .moviesCount(movies)
                .screenshotsCount(screenshots)
                .build();
    }

    public static final class GameDtoBuilder {
        private final String slug;
        private final String name;
        private String description;
        private String backgroundImage;
        private int additionsCount;
        private int creatorsCount;
        private int moviesCount;
        private int screenshotsCount;
        private String released;
        private String website;
        private double rating;
        private int ratingTop;
        private Integer metacritic;
        private List<RawgGenreDto> genres = List.of();
        private List<RawgTagDto> tags = List.of();
        private List<RawgPlatformEntryDto> platforms = List.of();
        private RawgClipDto clip;

        private GameDtoBuilder(String slug, String name) {
            this.slug = slug;
            this.name = name;
        }

        public GameDtoBuilder description(String s) { this.description = s; return this; }
        public GameDtoBuilder backgroundImage(String s) { this.backgroundImage = s; return this; }
        public GameDtoBuilder additionsCount(int v) { this.additionsCount = v; return this; }
        public GameDtoBuilder creatorsCount(int v) { this.creatorsCount = v; return this; }
        public GameDtoBuilder moviesCount(int v) { this.moviesCount = v; return this; }
        public GameDtoBuilder screenshotsCount(int v) { this.screenshotsCount = v; return this; }
        public GameDtoBuilder released(String s) { this.released = s; return this; }
        public GameDtoBuilder website(String s) { this.website = s; return this; }
        public GameDtoBuilder rating(double v) { this.rating = v; return this; }
        public GameDtoBuilder ratingTop(int v) { this.ratingTop = v; return this; }
        public GameDtoBuilder metacritic(Integer v) { this.metacritic = v; return this; }
        public GameDtoBuilder genres(List<RawgGenreDto> v) { this.genres = v; return this; }
        public GameDtoBuilder tags(List<RawgTagDto> v) { this.tags = v; return this; }
        public GameDtoBuilder platforms(List<RawgPlatformEntryDto> v) { this.platforms = v; return this; }
        public GameDtoBuilder clip(RawgClipDto v) { this.clip = v; return this; }

        public RawgGameDto build() {
            return new RawgGameDto(
                    1,                              // id
                    slug,                           // slug
                    name,                           // name
                    name,                           // nameOriginal
                    description,                    // description
                    null,                           // descriptionRaw
                    released,                       // released
                    false,                          // tba
                    null,                           // updated
                    backgroundImage,                // backgroundImage
                    null,                           // backgroundImageAdditional
                    website,                        // website
                    rating,                         // rating
                    ratingTop,                      // ratingTop
                    null,                           // ratings
                    0,                              // ratingsCount
                    0,                              // reviewsCount
                    0,                              // reviewsTextCount
                    metacritic,                     // metacritic
                    null,                           // metacriticUrl
                    0,                              // playtime
                    0,                              // parentsCount
                    additionsCount,                 // additionsCount
                    0,                              // gameSeriesCount
                    screenshotsCount,               // screenshotsCount
                    moviesCount,                    // moviesCount
                    creatorsCount,                  // creatorsCount
                    0,                              // achievementsCount
                    0,                              // parentAchievementsCount
                    clip,                           // clip
                    null,                           // alternativeNames
                    null,                           // developers
                    null,                           // publishers
                    genres,                         // genres
                    tags,                           // tags
                    platforms,                      // platforms
                    null,                           // parentPlatforms
                    null,                           // esrbRating
                    null,                           // stores
                    null                            // shortScreenshots
            );
        }
    }
}
