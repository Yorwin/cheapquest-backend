package com.cheapquest.backend.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.cheapquest.backend.domain.rawg.DeveloperSummary;
import com.cheapquest.backend.domain.rawg.PublisherSummary;
import com.cheapquest.backend.domain.rawg.RawgDetails;
import com.cheapquest.backend.domain.rawg.RawgEsrbRating;
import com.cheapquest.backend.domain.rawg.RawgGenre;
import com.cheapquest.backend.domain.rawg.RawgPlatform;
import com.cheapquest.backend.domain.rawg.RawgRating;
import com.cheapquest.backend.domain.rawg.RawgScreenshot;
import com.cheapquest.backend.domain.rawg.RawgStoreEntry;
import com.cheapquest.backend.domain.rawg.RawgStoreRef;
import com.cheapquest.backend.domain.rawg.RawgTag;
import com.cheapquest.backend.dto.firebase.RawgDocumentDto;
import com.cheapquest.backend.fixtures.RawgDocumentDtoFixtures;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RawgDetailsMapperTest {

    private final RawgDetailsMapper mapper = new RawgDetailsMapper();

    @Test
    void toDomain_nullDto_throws() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> mapper.toDomain(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void toDto_nullDetails_throws() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> mapper.toDto(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void toDomain_populatesScalarsAndLists() {
        RawgDocumentDto dto = RawgDocumentDtoFixtures
                .full("portal", "Portal")
                .released("2007-10-10")
                .description("A puzzle game.")
                .build();

        RawgDetails details = mapper.toDomain(dto);

        assertThat(details.slug()).isEqualTo("portal");
        assertThat(details.name()).isEqualTo("Portal");
        assertThat(details.released()).isEqualTo("2007-10-10");
        assertThat(details.description()).isEqualTo("A puzzle game.");
        assertThat(details.fetchedAt()).isEqualTo(Instant.parse("2026-06-30T10:05:00Z"));
        assertThat(details.developers()).containsExactly(new DeveloperSummary("Valve", "valve"));
        assertThat(details.genres()).containsExactly(new RawgGenre(1, "Action", "action"));
    }

    @Test
    void toDomain_unboxesNullCountsToZero() {
        RawgDocumentDto dto = RawgDocumentDtoFixtures.full("g", "G").build();

        RawgDetails details = mapper.toDomain(dto);

        assertThat(details.additionsCount()).isZero();
        assertThat(details.creatorsCount()).isZero();
        assertThat(details.moviesCount()).isZero();
        assertThat(details.screenshotsCount()).isZero();
        assertThat(details.ratingsCount()).isZero();
        assertThat(details.reviewsCount()).isZero();
        assertThat(details.playtime()).isZero();
        assertThat(details.suggestionsCount()).isZero();
        assertThat(details.tba()).isFalse();
    }

    @Test
    void toDomain_nullListsBecomeEmpty() {
        RawgDocumentDto dto = newDtoWithAllNulls();

        RawgDetails details = mapper.toDomain(dto);

        assertThat(details.developers()).isEmpty();
        assertThat(details.publishers()).isEmpty();
        assertThat(details.genres()).isEmpty();
        assertThat(details.tags()).isEmpty();
        assertThat(details.platforms()).isEmpty();
        assertThat(details.parentPlatforms()).isEmpty();
        assertThat(details.dlcs()).isEmpty();
        assertThat(details.creators()).isEmpty();
        assertThat(details.screenshots()).isEmpty();
        assertThat(details.ratings()).isEmpty();
        assertThat(details.alternativeNames()).isEmpty();
        assertThat(details.stores()).isEmpty();
        assertThat(details.shortScreenshots()).isEmpty();
        assertThat(details.addedByStatus()).isEmpty();
        assertThat(details.reactions()).isEmpty();
    }

    @Test
    void toDto_serialisesAllFieldsAndDefensiveCopies() {
        Instant ts = Instant.parse("2026-01-01T00:00:00Z");
        List<RawgGenre> genres = new ArrayList<>(
                List.of(new RawgGenre(1, "Action", "action")));
        List<RawgScreenshot> shots = new ArrayList<>(
                List.of(new RawgScreenshot(1L, "https://example.com/s.jpg", 1920, 1080, false)));
        Map<String, Integer> status = new HashMap<>(Map.of("yet", 12));
        Map<String, Integer> reactions = new HashMap<>(Map.of("1", 3));
        RawgDetails details = new RawgDetails(
                "slug", "Name", "Name", "2020-01-01",
                "desc", "descRaw", "https://h.example/img", "https://t.example/v",
                "https://w.example", 4.5, 5, 96,
                3, 2, 1, 4,
                List.of(new DeveloperSummary("D", "d")),
                List.of(new PublisherSummary("P", "p")),
                genres,
                List.of(new RawgTag(1, "FPS", "fps", "eng")),
                List.of(new RawgPlatform(1, "PC", "pc")),
                List.of(new RawgPlatform(1, "PC", "pc")),
                List.of(),
                List.of(),
                List.of("https://example.com/shot.jpg"),
                true, "2020-01-02", "https://bg.example/img",
                List.of(new RawgRating(1, "exceptional", 4, 4.5)),
                50, 5, 4, "https://metacritic.example/url", 12, 0, 0, 0, 0,
                null, List.of("alt"), new RawgEsrbRating(1, "Mature", "mature", "M"),
                List.of(new RawgStoreEntry(1L, "https://store.example/url",
                        new RawgStoreRef(1, "Steam", "steam", "store.steampowered.com", 0,
                                "https://steam.example/icon"))),
                shots, status, reactions, 0, ts);

        RawgDocumentDto dto = mapper.toDto(details);

        assertThat(dto.slug()).isEqualTo("slug");
        assertThat(dto.name()).isEqualTo("Name");
        assertThat(dto.released()).isEqualTo("2020-01-01");
        assertThat(dto.description()).isEqualTo("desc");
        assertThat(dto.headerImage()).isEqualTo("https://h.example/img");
        assertThat(dto.trailerUrl()).isEqualTo("https://t.example/v");
        assertThat(dto.website()).isEqualTo("https://w.example");
        assertThat(dto.rating()).isEqualTo(4.5);
        assertThat(dto.ratingTop()).isEqualTo(5);
        assertThat(dto.metacritic()).isEqualTo(96);
        assertThat(dto.additionsCount()).isEqualTo(3);
        assertThat(dto.creatorsCount()).isEqualTo(2);
        assertThat(dto.moviesCount()).isEqualTo(1);
        assertThat(dto.screenshotsCount()).isEqualTo(4);
        assertThat(dto.developers()).containsExactly(new DeveloperSummary("D", "d"));
        assertThat(dto.publishers()).containsExactly(new PublisherSummary("P", "p"));
        assertThat(dto.genres()).containsExactly(new RawgGenre(1, "Action", "action"));
        assertThat(dto.tags()).containsExactly(new RawgTag(1, "FPS", "fps", "eng"));
        assertThat(dto.platforms()).containsExactly(new RawgPlatform(1, "PC", "pc"));
        assertThat(dto.parentPlatforms()).containsExactly(new RawgPlatform(1, "PC", "pc"));
        assertThat(dto.tba()).isTrue();
        assertThat(dto.updated()).isEqualTo("2020-01-02");
        assertThat(dto.backgroundImageAdditional()).isEqualTo("https://bg.example/img");
        assertThat(dto.ratings()).hasSize(1);
        assertThat(dto.ratingsCount()).isEqualTo(50);
        assertThat(dto.reviewsCount()).isEqualTo(5);
        assertThat(dto.reviewsTextCount()).isEqualTo(4);
        assertThat(dto.metacriticUrl()).isEqualTo("https://metacritic.example/url");
        assertThat(dto.playtime()).isEqualTo(12);
        assertThat(dto.parentsCount()).isZero();
        assertThat(dto.gameSeriesCount()).isZero();
        assertThat(dto.achievementsCount()).isZero();
        assertThat(dto.parentAchievementsCount()).isZero();
        assertThat(dto.clip()).isNull();
        assertThat(dto.alternativeNames()).containsExactly("alt");
        assertThat(dto.esrbRating()).isEqualTo(new RawgEsrbRating(1, "Mature", "mature", "M"));
        assertThat(dto.stores()).hasSize(1);
        assertThat(dto.shortScreenshots()).hasSize(1);
        assertThat(dto.addedByStatus()).containsEntry("yet", 12);
        assertThat(dto.reactions()).containsEntry("1", 3);
        assertThat(dto.suggestionsCount()).isZero();
        assertThat(dto.fetchedAt()).isEqualTo("2026-01-01T00:00:00Z");

        genres.clear();
        shots.clear();
        status.clear();
        reactions.clear();
        assertThat(dto.genres()).containsExactly(new RawgGenre(1, "Action", "action"));
        assertThat(dto.shortScreenshots()).hasSize(1);
        assertThat(dto.addedByStatus()).containsEntry("yet", 12);
        assertThat(dto.reactions()).containsEntry("1", 3);
    }

    @Test
    void roundTrip_dtoToDomainToDto_preservesAllFields() {
        RawgDocumentDto original = RawgDocumentDtoFixtures
                .full("slug", "Name")
                .released("2010-01-01")
                .description("<p>desc</p>")
                .genres(List.of(new RawgGenre(1, "Action", "action")))
                .screenshots(List.of("https://example.com/s.jpg"))
                .fetchedAt("2026-06-30T10:05:00Z")
                .build();

        RawgDetails details = mapper.toDomain(original);
        RawgDocumentDto roundTripped = mapper.toDto(details);

        assertThat(roundTripped.slug()).isEqualTo(original.slug());
        assertThat(roundTripped.name()).isEqualTo(original.name());
        assertThat(roundTripped.released()).isEqualTo(original.released());
        assertThat(roundTripped.description()).isEqualTo(original.description());
        assertThat(roundTripped.fetchedAt()).isEqualTo(original.fetchedAt());
        assertThat(roundTripped.genres()).isEqualTo(original.genres());
        assertThat(roundTripped.developers()).isEqualTo(original.developers());
        assertThat(roundTripped.publishers()).isEqualTo(original.publishers());
        assertThat(roundTripped.tags()).isEqualTo(original.tags());
        assertThat(roundTripped.screenshots()).isEqualTo(original.screenshots());
    }

    @Test
    void toDomain_nullFetchedAt_fallsBackToCurrentInstant() {
        java.time.Instant before = java.time.Instant.now();
        RawgDocumentDto dto = newDtoWithAllNulls();
        java.time.Instant after = java.time.Instant.now();

        java.time.Instant result = mapper.toDomain(dto).fetchedAt();

        assertThat(result).isNotNull();
        assertThat(result).isBetween(before, after);
    }

    @Test
    void toDomain_invalidFetchedAtString_throws() {
        RawgDocumentDto base = newDtoWithAllNulls();
        RawgDocumentDto dto = new RawgDocumentDto(
                base.slug(), base.name(), base.nameOriginal(), base.released(),
                base.description(), base.descriptionRaw(), base.headerImage(), base.trailerUrl(),
                base.website(), base.rating(), base.ratingTop(), base.metacritic(),
                base.additionsCount(), base.creatorsCount(), base.moviesCount(), base.screenshotsCount(),
                base.developers(), base.publishers(), base.genres(), base.tags(),
                base.platforms(), base.parentPlatforms(), base.dlcs(), base.creators(), base.screenshots(),
                base.tba(), base.updated(), base.backgroundImageAdditional(), base.ratings(),
                base.ratingsCount(), base.reviewsCount(), base.reviewsTextCount(), base.metacriticUrl(),
                base.playtime(), base.parentsCount(), base.gameSeriesCount(), base.achievementsCount(),
                base.parentAchievementsCount(), base.clip(), base.alternativeNames(), base.esrbRating(),
                base.stores(), base.shortScreenshots(), base.addedByStatus(), base.reactions(),
                base.suggestionsCount(), "not-a-date");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> mapper.toDomain(dto))
                .isInstanceOf(java.time.format.DateTimeParseException.class);
    }

    private static RawgDocumentDto newDtoWithAllNulls() {
        return new RawgDocumentDto(
                "g", "G", "G",
                null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null,
                null, null, null, null, null, null, null,
                null, null);
    }
}
