package com.cheapquest.backend.fixtures;

import com.cheapquest.backend.dto.rawg.RawgClipDto;
import com.cheapquest.backend.dto.rawg.RawgDeveloperDto;
import com.cheapquest.backend.dto.rawg.RawgEsrbRatingDto;
import com.cheapquest.backend.dto.rawg.RawgGameDto;
import com.cheapquest.backend.dto.rawg.RawgGenreDto;
import com.cheapquest.backend.dto.rawg.RawgPlatformEntryDto;
import com.cheapquest.backend.dto.rawg.RawgPublisherDto;
import com.cheapquest.backend.dto.rawg.RawgRatingDto;
import com.cheapquest.backend.dto.rawg.RawgScreenshotDto;
import com.cheapquest.backend.dto.rawg.RawgStoreEntryDto;
import com.cheapquest.backend.dto.rawg.RawgTagDto;
import java.util.List;
import java.util.Map;

/**
 * Shared builder for {@link RawgGameDto} instances used by multiple tests.
 * The 43-field DTO is filled with sensible defaults (zeros, nulls, false,
 * empty lists/maps); every field is exposed via a fluent setter so merge
 * tests can override just the ones they care about.
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
        private int id = 1;
        private final String slug;
        private final String name;
        private String nameOriginal;
        private String description;
        private String descriptionRaw;
        private String released;
        private boolean tba;
        private String updated;
        private String backgroundImage;
        private String backgroundImageAdditional;
        private String website;
        private double rating;
        private int ratingTop;
        private List<RawgRatingDto> ratings;
        private int ratingsCount;
        private int reviewsCount;
        private int reviewsTextCount;
        private Integer metacritic;
        private String metacriticUrl;
        private int playtime;
        private int parentsCount;
        private int additionsCount;
        private int gameSeriesCount;
        private int screenshotsCount;
        private int moviesCount;
        private int creatorsCount;
        private int achievementsCount;
        private int parentAchievementsCount;
        private RawgClipDto clip;
        private List<String> alternativeNames;
        private List<RawgDeveloperDto> developers;
        private List<RawgPublisherDto> publishers;
        private List<RawgGenreDto> genres = List.of();
        private List<RawgTagDto> tags = List.of();
        private List<RawgPlatformEntryDto> platforms = List.of();
        private List<RawgPlatformEntryDto> parentPlatforms;
        private RawgEsrbRatingDto esrbRating;
        private List<RawgStoreEntryDto> stores;
        private List<RawgScreenshotDto> shortScreenshots;
        private Map<String, Integer> addedByStatus;
        private Map<String, Integer> reactions;
        private int suggestionsCount;

        private GameDtoBuilder(String slug, String name) {
            this.slug = slug;
            this.name = name;
            this.nameOriginal = name;
        }

        public GameDtoBuilder id(int v) { this.id = v; return this; }
        public GameDtoBuilder nameOriginal(String v) { this.nameOriginal = v; return this; }
        public GameDtoBuilder description(String v) { this.description = v; return this; }
        public GameDtoBuilder descriptionRaw(String v) { this.descriptionRaw = v; return this; }
        public GameDtoBuilder released(String v) { this.released = v; return this; }
        public GameDtoBuilder tba(boolean v) { this.tba = v; return this; }
        public GameDtoBuilder updated(String v) { this.updated = v; return this; }
        public GameDtoBuilder backgroundImage(String v) { this.backgroundImage = v; return this; }
        public GameDtoBuilder backgroundImageAdditional(String v) { this.backgroundImageAdditional = v; return this; }
        public GameDtoBuilder website(String v) { this.website = v; return this; }
        public GameDtoBuilder rating(double v) { this.rating = v; return this; }
        public GameDtoBuilder ratingTop(int v) { this.ratingTop = v; return this; }
        public GameDtoBuilder ratings(List<RawgRatingDto> v) { this.ratings = v; return this; }
        public GameDtoBuilder ratingsCount(int v) { this.ratingsCount = v; return this; }
        public GameDtoBuilder reviewsCount(int v) { this.reviewsCount = v; return this; }
        public GameDtoBuilder reviewsTextCount(int v) { this.reviewsTextCount = v; return this; }
        public GameDtoBuilder metacritic(Integer v) { this.metacritic = v; return this; }
        public GameDtoBuilder metacriticUrl(String v) { this.metacriticUrl = v; return this; }
        public GameDtoBuilder playtime(int v) { this.playtime = v; return this; }
        public GameDtoBuilder parentsCount(int v) { this.parentsCount = v; return this; }
        public GameDtoBuilder additionsCount(int v) { this.additionsCount = v; return this; }
        public GameDtoBuilder gameSeriesCount(int v) { this.gameSeriesCount = v; return this; }
        public GameDtoBuilder screenshotsCount(int v) { this.screenshotsCount = v; return this; }
        public GameDtoBuilder moviesCount(int v) { this.moviesCount = v; return this; }
        public GameDtoBuilder creatorsCount(int v) { this.creatorsCount = v; return this; }
        public GameDtoBuilder achievementsCount(int v) { this.achievementsCount = v; return this; }
        public GameDtoBuilder parentAchievementsCount(int v) { this.parentAchievementsCount = v; return this; }
        public GameDtoBuilder clip(RawgClipDto v) { this.clip = v; return this; }
        public GameDtoBuilder alternativeNames(List<String> v) { this.alternativeNames = v; return this; }
        public GameDtoBuilder developers(List<RawgDeveloperDto> v) { this.developers = v; return this; }
        public GameDtoBuilder publishers(List<RawgPublisherDto> v) { this.publishers = v; return this; }
        public GameDtoBuilder genres(List<RawgGenreDto> v) { this.genres = v; return this; }
        public GameDtoBuilder tags(List<RawgTagDto> v) { this.tags = v; return this; }
        public GameDtoBuilder platforms(List<RawgPlatformEntryDto> v) { this.platforms = v; return this; }
        public GameDtoBuilder parentPlatforms(List<RawgPlatformEntryDto> v) { this.parentPlatforms = v; return this; }
        public GameDtoBuilder esrbRating(RawgEsrbRatingDto v) { this.esrbRating = v; return this; }
        public GameDtoBuilder stores(List<RawgStoreEntryDto> v) { this.stores = v; return this; }
        public GameDtoBuilder shortScreenshots(List<RawgScreenshotDto> v) { this.shortScreenshots = v; return this; }
        public GameDtoBuilder addedByStatus(Map<String, Integer> v) { this.addedByStatus = v; return this; }
        public GameDtoBuilder reactions(Map<String, Integer> v) { this.reactions = v; return this; }
        public GameDtoBuilder suggestionsCount(int v) { this.suggestionsCount = v; return this; }

        public RawgGameDto build() {
            return new RawgGameDto(
                    id,
                    slug,
                    name,
                    nameOriginal,
                    description,
                    descriptionRaw,
                    released,
                    tba,
                    updated,
                    backgroundImage,
                    backgroundImageAdditional,
                    website,
                    rating,
                    ratingTop,
                    ratings,
                    ratingsCount,
                    reviewsCount,
                    reviewsTextCount,
                    metacritic,
                    metacriticUrl,
                    playtime,
                    parentsCount,
                    additionsCount,
                    gameSeriesCount,
                    screenshotsCount,
                    moviesCount,
                    creatorsCount,
                    achievementsCount,
                    parentAchievementsCount,
                    clip,
                    alternativeNames,
                    developers,
                    publishers,
                    genres,
                    tags,
                    platforms,
                    parentPlatforms,
                    esrbRating,
                    stores,
                    shortScreenshots,
                    addedByStatus,
                    reactions,
                    suggestionsCount);
        }
    }
}
