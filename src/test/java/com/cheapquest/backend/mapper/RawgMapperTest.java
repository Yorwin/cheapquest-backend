package com.cheapquest.backend.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.cheapquest.backend.domain.rawg.RawgCreator;
import com.cheapquest.backend.domain.rawg.RawgDetails;
import com.cheapquest.backend.domain.rawg.RawgDlc;
import com.cheapquest.backend.dto.rawg.RawgClipDto;
import com.cheapquest.backend.dto.rawg.RawgCreatorDto;
import com.cheapquest.backend.dto.rawg.RawgDeveloperDto;
import com.cheapquest.backend.dto.rawg.RawgGameDto;
import com.cheapquest.backend.dto.rawg.RawgGenreDto;
import com.cheapquest.backend.dto.rawg.RawgMovieDataDto;
import com.cheapquest.backend.dto.rawg.RawgMovieDto;
import com.cheapquest.backend.dto.rawg.RawgPlatformEntryDto;
import com.cheapquest.backend.dto.rawg.RawgPlatformRefDto;
import com.cheapquest.backend.dto.rawg.RawgPublisherDto;
import com.cheapquest.backend.dto.rawg.RawgScreenshotDto;
import com.cheapquest.backend.dto.rawg.RawgTagDto;
import com.cheapquest.backend.fixtures.RawgDtoFixtures;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RawgMapperTest {

    private final RawgMapper mapper = new RawgMapper();

    @Test
    void pickExactMatch_picksFarCryNotSpecialEdition() {
        var farCry = RawgDtoFixtures.dto("far-cry", "Far Cry").build();
        var special = RawgDtoFixtures.dto("far-cry-special-edition", "Far Cry: Special Edition").build();
        var farCry2 = RawgDtoFixtures.dto("far-cry-2", "Far Cry 2").build();

        Optional<RawgGameDto> found = mapper.pickExactMatch(List.of(farCry, special, farCry2), "Far Cry");

        assertThat(found).isPresent();
        assertThat(found.get().slug()).isEqualTo("far-cry");
    }

    @Test
    void pickExactMatch_isCaseInsensitiveAndIgnoresPunctuation() {
        var hl2 = RawgDtoFixtures.dto("half-life-2", "Half-Life 2").build();

        Optional<RawgGameDto> found = mapper.pickExactMatch(List.of(hl2), "  half-life 2  ");

        assertThat(found).isPresent();
        assertThat(found.get().slug()).isEqualTo("half-life-2");
    }

    @Test
    void pickExactMatch_emptyOnNoMatch() {
        var portal2 = RawgDtoFixtures.dto("portal-2", "Portal 2").build();
        assertThat(mapper.pickExactMatch(List.of(portal2), "Portal")).isEmpty();
    }

    @Test
    void pickExactMatch_emptyOnNullInputs() {
        var portal = RawgDtoFixtures.dto("portal", "Portal").build();
        assertThat(mapper.pickExactMatch(null, "Portal")).isEmpty();
        assertThat(mapper.pickExactMatch(List.of(portal), null)).isEmpty();
        assertThat(mapper.pickExactMatch(null, null)).isEmpty();
    }

    @Test
    void pickClosestByLevenshtein_findsFuzzyMatch() {
        var farCry = RawgDtoFixtures.dto("far-cry", "Far Cry").build();
        var farCry2 = RawgDtoFixtures.dto("far-cry-2", "Far Cry 2").build();
        var stardew = RawgDtoFixtures.dto("stardew-valley", "Stardew Valley").build();

        Optional<RawgGameDto> found = mapper.pickClosestByLevenshtein(
                List.of(farCry, farCry2, stardew), "Farcry");

        assertThat(found).isPresent();
        assertThat(found.get().slug()).isEqualTo("far-cry");
    }

    @Test
    void pickClosestByLevenshtein_picksMostSimilar() {
        var farCry2 = RawgDtoFixtures.dto("far-cry-2", "Far Cry 2").build();
        var farCry3 = RawgDtoFixtures.dto("far-cry-3", "Far Cry 3").build();

        Optional<RawgGameDto> found = mapper.pickClosestByLevenshtein(List.of(farCry2, farCry3), "Far Cry 2");

        assertThat(found).isPresent();
        assertThat(found.get().slug()).isEqualTo("far-cry-2");
    }

    @Test
    void pickClosestByLevenshtein_emptyWhenAllTooFar() {
        var somethingElse = RawgDtoFixtures.dto("completely-different", "Completely Different Thing").build();

        Optional<RawgGameDto> found = mapper.pickClosestByLevenshtein(
                List.of(somethingElse), "Far Cry");

        assertThat(found).isEmpty();
    }

    @Test
    void pickClosestByLevenshtein_picksAtThresholdBoundary() {
        var farCry3 = RawgDtoFixtures.dto("far-cry-3", "Far Cry 3").build();

        Optional<RawgGameDto> found = mapper.pickClosestByLevenshtein(List.of(farCry3), "farcry");

        assertThat(found).isPresent();
        assertThat(found.get().slug()).isEqualTo("far-cry-3");
    }

    @Test
    void pickClosestByLevenshtein_rejectsJustOverThreshold() {
        var unrelated = RawgDtoFixtures.dto("stardew-valley", "Stardew Valley").build();

        Optional<RawgGameDto> found = mapper.pickClosestByLevenshtein(
                List.of(unrelated), "farcry");

        assertThat(found).isEmpty();
    }

    @Test
    void pickClosestByLevenshtein_emptyOnEmptyInput() {
        assertThat(mapper.pickClosestByLevenshtein(null, "Portal")).isEmpty();
        assertThat(mapper.pickClosestByLevenshtein(List.of(), "Portal")).isEmpty();
    }

    @Test
    void pickTrailerUrl_prefersClipVideo() {
        var detail = RawgDtoFixtures.dto("x", "X")
                .clip(new RawgClipDto("https://clip/short", "https://clip/full", "vid", null))
                .build();
        var movie = new RawgMovieDto(1, "trailer", null,
                new RawgMovieDataDto("https://yt/480", "https://yt/max", "https://yt/full"));

        assertThat(mapper.pickTrailerUrl(detail, List.of(movie))).isEqualTo("https://clip/full");
    }

    @Test
    void pickTrailerUrl_fallsBackToClipShort() {
        var detail = RawgDtoFixtures.dto("x", "X")
                .clip(new RawgClipDto("https://clip/short", null, "vid", null))
                .build();

        assertThat(mapper.pickTrailerUrl(detail, List.of())).isEqualTo("https://clip/short");
    }

    @Test
    void pickTrailerUrl_fallsBackToFirstMovieMax() {
        var detail = RawgDtoFixtures.dto("x", "X").build();
        var movie = new RawgMovieDto(1, "trailer", null,
                new RawgMovieDataDto("https://yt/480", "https://yt/max", "https://yt/full"));

        assertThat(mapper.pickTrailerUrl(detail, List.of(movie))).isEqualTo("https://yt/max");
    }

    @Test
    void pickTrailerUrl_fallsBackToFirstMovieFull() {
        var detail = RawgDtoFixtures.dto("x", "X").build();
        var movie = new RawgMovieDto(1, "trailer", null,
                new RawgMovieDataDto("https://yt/480", null, "https://yt/full"));

        assertThat(mapper.pickTrailerUrl(detail, List.of(movie))).isEqualTo("https://yt/full");
    }

    @Test
    void pickTrailerUrl_nullWhenNoClipAndNoMovies() {
        var detail = RawgDtoFixtures.dto("x", "X").build();
        assertThat(mapper.pickTrailerUrl(detail, null)).isNull();
        assertThat(mapper.pickTrailerUrl(detail, List.of())).isNull();
    }

    @Test
    void pickTrailerUrl_nullWhenClipBlankAndMoviesEmpty() {
        var detail = RawgDtoFixtures.dto("x", "X")
                .clip(new RawgClipDto("", "", "vid", null))
                .build();
        assertThat(mapper.pickTrailerUrl(detail, List.of())).isNull();
    }

    @Test
    void toDlcSummaries_extractsSlugNameReleasedAndImage() {
        var rtx = RawgDtoFixtures.dto("portal-with-rtx", "Portal with RTX")
                .released("2022-12-08")
                .backgroundImage("https://x/rtx.jpg")
                .build();
        var companion = RawgDtoFixtures.dto("portal-companion-collection", "Portal: Companion Collection")
                .released("2022-06-28")
                .backgroundImage("https://x/comp.jpg")
                .build();

        List<RawgDlc> dlcs = mapper.toDlcSummaries(List.of(rtx, companion));

        assertThat(dlcs).hasSize(2);
        assertThat(dlcs.get(0).slug()).isEqualTo("portal-with-rtx");
        assertThat(dlcs.get(0).name()).isEqualTo("Portal with RTX");
        assertThat(dlcs.get(0).released()).isEqualTo("2022-12-08");
        assertThat(dlcs.get(0).backgroundImage()).isEqualTo("https://x/rtx.jpg");
        assertThat(dlcs.get(1).slug()).isEqualTo("portal-companion-collection");
    }

    @Test
    void toDlcSummaries_emptyOnNull() {
        assertThat(mapper.toDlcSummaries(null)).isEmpty();
    }

    @Test
    void toCreators_extractsNameSlugAndPosition() {
        var c1 = new RawgCreatorDto(1, "Gabe Newell", "gabe-newell", null, null, "Founder", 1);
        var c2 = new RawgCreatorDto(2, "Kim Swift", "kim-swift", null, null, "Designer", 1);

        List<RawgCreator> creators = mapper.toCreators(List.of(c1, c2));

        assertThat(creators).hasSize(2);
        assertThat(creators.get(0).name()).isEqualTo("Gabe Newell");
        assertThat(creators.get(0).position()).isEqualTo("Founder");
        assertThat(creators.get(1).name()).isEqualTo("Kim Swift");
    }

    @Test
    void toCreators_emptyOnNull() {
        assertThat(mapper.toCreators(null)).isEmpty();
    }

    @Test
    void toScreenshotUrls_extractsImageUrls() {
        var s1 = new RawgScreenshotDto(1L, "https://x/1.jpg", 1920, 1080, false);
        var s2 = new RawgScreenshotDto(2L, "https://x/2.jpg", 1920, 1080, false);

        assertThat(mapper.toScreenshotUrls(List.of(s1, s2)))
                .containsExactly("https://x/1.jpg", "https://x/2.jpg");
    }

    @Test
    void toScreenshotUrls_emptyOnNull() {
        assertThat(mapper.toScreenshotUrls(null)).isEmpty();
    }

    @Test
    void toGenres_extractsIdNameSlug() {
        var g = new RawgGenreDto(4, "Action", "action", 191775, "https://x/bg.jpg");
        assertThat(mapper.toGenres(List.of(g)))
                .singleElement()
                .satisfies(genre -> {
                    assertThat(genre.id()).isEqualTo(4);
                    assertThat(genre.name()).isEqualTo("Action");
                    assertThat(genre.slug()).isEqualTo("action");
                });
    }

    @Test
    void toGenres_emptyOnNull() {
        assertThat(mapper.toGenres(null)).isEmpty();
    }

    @Test
    void toTags_extractsIdNameSlugLanguage() {
        var t = new RawgTagDto(31, "Singleplayer", "singleplayer", "eng", 0, null);
        assertThat(mapper.toTags(List.of(t)))
                .singleElement()
                .satisfies(tag -> {
                    assertThat(tag.id()).isEqualTo(31);
                    assertThat(tag.name()).isEqualTo("Singleplayer");
                    assertThat(tag.language()).isEqualTo("eng");
                });
    }

    @Test
    void toPlatforms_unwrapsEntry() {
        var entry = new RawgPlatformEntryDto(new RawgPlatformRefDto(4, "PC", "pc"));
        assertThat(mapper.toPlatforms(List.of(entry)))
                .singleElement()
                .satisfies(p -> {
                    assertThat(p.id()).isEqualTo(4);
                    assertThat(p.name()).isEqualTo("PC");
                });
    }

    @Test
    void toPlatforms_skipsNullInner() {
        var entry = new RawgPlatformEntryDto(null);
        assertThat(mapper.toPlatforms(List.of(entry))).isEmpty();
    }

    @Test
    void toPlatforms_emptyOnNull() {
        assertThat(mapper.toPlatforms(null)).isEmpty();
    }

    @Test
    void toDeveloperSummaries_extractsNameAndSlug() {
        var d = new RawgDeveloperDto(1612, "Valve Software", "valve-software", 44, "https://x/bg.jpg");
        assertThat(mapper.toDeveloperSummaries(List.of(d)))
                .singleElement()
                .satisfies(dev -> {
                    assertThat(dev.name()).isEqualTo("Valve Software");
                    assertThat(dev.slug()).isEqualTo("valve-software");
                });
    }

    @Test
    void toPublisherSummaries_extractsNameAndSlug() {
        var p = new RawgPublisherDto(3399, "Valve", "valve", 48, "https://x/bg.jpg");
        assertThat(mapper.toPublisherSummaries(List.of(p)))
                .singleElement()
                .satisfies(pub -> {
                    assertThat(pub.name()).isEqualTo("Valve");
                    assertThat(pub.slug()).isEqualTo("valve");
                });
    }

    @Test
    void toDetails_assemblesAllFields() {
        var detail = RawgDtoFixtures.dto("portal", "Portal")
                .description("<p>HTML description</p>")
                .backgroundImage("https://x/portal.jpg")
                .additionsCount(4)
                .creatorsCount(2)
                .moviesCount(0)
                .screenshotsCount(0)
                .released("2007-10-09")
                .website("http://example.com")
                .rating(4.49)
                .ratingTop(5)
                .metacritic(90)
                .genres(List.of(new RawgGenreDto(4, "Action", "action", 0, null)))
                .tags(List.of(new RawgTagDto(31, "Singleplayer", "singleplayer", "eng", 0, null)))
                .platforms(List.of(new RawgPlatformEntryDto(new RawgPlatformRefDto(4, "PC", "pc"))))
                .build();

        var addition = RawgDtoFixtures.dto("portal-with-rtx", "Portal with RTX")
                .released("2022-12-08")
                .build();
        var creator = new RawgCreatorDto(1, "Gabe Newell", "gabe-newell", null, null, "Founder", 1);

        RawgDetails out = mapper.toDetails(detail, List.of(),
                List.of(), List.of(addition), List.of(creator));

        assertThat(out.slug()).isEqualTo("portal");
        assertThat(out.name()).isEqualTo("Portal");
        assertThat(out.description()).isEqualTo("<p>HTML description</p>");
        assertThat(out.headerImage()).isEqualTo("https://x/portal.jpg");
        assertThat(out.trailerUrl()).isNull();
        assertThat(out.website()).isEqualTo("http://example.com");
        assertThat(out.rating()).isEqualTo(4.49);
        assertThat(out.ratingTop()).isEqualTo(5);
        assertThat(out.metacritic()).isEqualTo(90);
        assertThat(out.additionsCount()).isEqualTo(4);
        assertThat(out.creatorsCount()).isEqualTo(2);
        assertThat(out.moviesCount()).isZero();
        assertThat(out.dlcs()).hasSize(1);
        assertThat(out.dlcs().get(0).slug()).isEqualTo("portal-with-rtx");
        assertThat(out.creators()).hasSize(1);
        assertThat(out.creators().get(0).name()).isEqualTo("Gabe Newell");
        assertThat(out.genres()).hasSize(1);
        assertThat(out.tags()).hasSize(1);
        assertThat(out.platforms()).hasSize(1);
        assertThat(out.parentPlatforms()).isEmpty();
        assertThat(out.screenshots()).isEmpty();
    }

    @Test
    void toDetails_throwsOnNullDetail() {
        org.junit.jupiter.api.Assertions.assertThrows(
                NullPointerException.class,
                () -> mapper.toDetails(null, List.of(), List.of(), List.of(), List.of()));
    }

    @Test
    void normalize_keepsAlnumLowercase() {
        assertThat(RawgMapper.normalize(" Half-Life 2 ")).isEqualTo("halflife2");
        assertThat(RawgMapper.normalize("FAR_CRY-3!")).isEqualTo("farcry3");
    }

    @Test
    void levenshtein_knownCases() {
        assertThat(RawgMapper.levenshtein("farcry", "farcry")).isZero();
        assertThat(RawgMapper.levenshtein("farcry", "farcry2")).isEqualTo(1);
        assertThat(RawgMapper.levenshtein("farcry", "farcryspecialedition")).isEqualTo(14);
        assertThat(RawgMapper.levenshtein("", "abc")).isEqualTo(3);
        assertThat(RawgMapper.levenshtein("abc", "")).isEqualTo(3);
    }

}
