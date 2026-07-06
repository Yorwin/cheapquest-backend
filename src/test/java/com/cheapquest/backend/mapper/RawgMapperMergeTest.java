package com.cheapquest.backend.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cheapquest.backend.dto.rawg.RawgClipDto;
import com.cheapquest.backend.dto.rawg.RawgDeveloperDto;
import com.cheapquest.backend.dto.rawg.RawgEsrbRatingDto;
import com.cheapquest.backend.dto.rawg.RawgGameDto;
import com.cheapquest.backend.dto.rawg.RawgGenreDto;
import com.cheapquest.backend.dto.rawg.RawgPlatformEntryDto;
import com.cheapquest.backend.dto.rawg.RawgPlatformRefDto;
import com.cheapquest.backend.dto.rawg.RawgPublisherDto;
import com.cheapquest.backend.dto.rawg.RawgRatingDto;
import com.cheapquest.backend.dto.rawg.RawgScreenshotDto;
import com.cheapquest.backend.dto.rawg.RawgStoreEntryDto;
import com.cheapquest.backend.dto.rawg.RawgStoreRefDto;
import com.cheapquest.backend.dto.rawg.RawgTagDto;
import com.cheapquest.backend.fixtures.RawgDtoFixtures;
import com.cheapquest.backend.fixtures.RawgDtoFixtures.GameDtoBuilder;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RawgMapperMergeTest {

    private final RawgMapper mapper = new RawgMapper();

    @Test
    void prefersIdWhenAllThreePresentAndEqual() {
        var search = RawgDtoFixtures.dto("portal", "Portal")
                .released("2007-10-10")
                .backgroundImage("https://x/p.jpg")
                .build();
        var slug = search;
        var id = search;
        RawgGameDto merged = mapper.mergeSearchAndDetails(search, slug, id);
        assertThat(merged.slug()).isEqualTo("portal");
        assertThat(merged.name()).isEqualTo("Portal");
        assertThat(merged.released()).isEqualTo("2007-10-10");
        assertThat(merged.backgroundImage()).isEqualTo("https://x/p.jpg");
    }

    @Test
    void fallsBackToSlugWhenIdIsNull() {
        var search = RawgDtoFixtures.dto("portal", "Portal")
                .description("from search")
                .build();
        var slug = RawgDtoFixtures.dto("portal", "Portal")
                .description("from slug")
                .build();
        RawgGameDto merged = mapper.mergeSearchAndDetails(search, slug, null);
        assertThat(merged.description()).isEqualTo("from slug");
    }

    @Test
    void fallsBackToSearchWhenIdAndSlugAreNull() {
        var search = RawgDtoFixtures.dto("portal", "Portal")
                .description("from search")
                .build();
        var slug = RawgDtoFixtures.dto("portal", "Portal").build();
        RawgGameDto merged = mapper.mergeSearchAndDetails(search, slug, null);
        assertThat(merged.description()).isEqualTo("from search");
    }

    @Test
    void prefersIdAndLogsWarnOnStringValueConflict() {
        var search = RawgDtoFixtures.dto("portal", "Portal")
                .description("from search")
                .build();
        var slug = RawgDtoFixtures.dto("portal", "Portal")
                .description("from slug")
                .build();
        var id = RawgDtoFixtures.dto("portal", "Portal")
                .description("from id")
                .build();
        RawgGameDto merged = mapper.mergeSearchAndDetails(search, slug, id);
        assertThat(merged.description()).isEqualTo("from id");
    }

    @Test
    void prefersIdOnIntValueConflict() {
        var search = RawgDtoFixtures.dto("portal", "Portal").rating(1.0).build();
        var slug = RawgDtoFixtures.dto("portal", "Portal").rating(2.0).build();
        var id = RawgDtoFixtures.dto("portal", "Portal").rating(5.0).build();
        RawgGameDto merged = mapper.mergeSearchAndDetails(search, slug, id);
        assertThat(merged.rating()).isEqualTo(5.0);
    }

    @Test
    void countFieldsUseMaxAcrossSources() {
        var search = RawgDtoFixtures.dto("p", "P").ratingsCount(50).additionsCount(2).build();
        var slug = RawgDtoFixtures.dto("p", "P").ratingsCount(120).additionsCount(3).build();
        var id = RawgDtoFixtures.dto("p", "P").ratingsCount(80).additionsCount(1).build();
        RawgGameDto merged = mapper.mergeSearchAndDetails(search, slug, id);
        assertThat(merged.ratingsCount()).isEqualTo(120);
        assertThat(merged.additionsCount()).isEqualTo(3);
    }

    @Test
    void unionsGenreListByIdPreservingIdOrder() {
        var g1 = new RawgGenreDto(1, "Action", "action", 0, null);
        var g2 = new RawgGenreDto(2, "FPS", "fps", 0, null);
        var g3 = new RawgGenreDto(3, "RPG", "rpg", 0, null);
        var search = RawgDtoFixtures.dto("p", "P").genres(List.of(g1, g2)).build();
        var slug = RawgDtoFixtures.dto("p", "P").genres(List.of(g2, g3)).build();
        var id = RawgDtoFixtures.dto("p", "P").genres(List.of(g1, g3)).build();
        RawgGameDto merged = mapper.mergeSearchAndDetails(search, slug, id);
        assertThat(merged.genres()).extracting(RawgGenreDto::id)
                .containsExactly(1, 3, 2);
    }

    @Test
    void unionsTagsListById() {
        var t1 = new RawgTagDto(1, "Singleplayer", "singleplayer", "eng", 0, null);
        var t2 = new RawgTagDto(2, "Multiplayer", "multiplayer", "eng", 0, null);
        var search = RawgDtoFixtures.dto("p", "P").tags(List.of(t1)).build();
        var slug = RawgDtoFixtures.dto("p", "P").tags(List.of(t2)).build();
        var id = RawgDtoFixtures.dto("p", "P").tags(List.of(t1, t2)).build();
        RawgGameDto merged = mapper.mergeSearchAndDetails(search, slug, id);
        assertThat(merged.tags()).extracting(RawgTagDto::id)
                .containsExactly(1, 2);
    }

    @Test
    void unionsPlatformsByNestedPlatformId() {
        var pc = new RawgPlatformEntryDto(new RawgPlatformRefDto(4, "PC", "pc"));
        var ps5 = new RawgPlatformEntryDto(new RawgPlatformRefDto(187, "PS5", "ps5"));
        var search = RawgDtoFixtures.dto("p", "P").platforms(List.of(pc)).build();
        var slug = RawgDtoFixtures.dto("p", "P").platforms(List.of(ps5)).build();
        var id = RawgDtoFixtures.dto("p", "P").platforms(List.of(pc, ps5)).build();
        RawgGameDto merged = mapper.mergeSearchAndDetails(search, slug, id);
        assertThat(merged.platforms()).hasSize(2);
        assertThat(merged.platforms().get(0).platform().id()).isEqualTo(4);
        assertThat(merged.platforms().get(1).platform().id()).isEqualTo(187);
    }

    @Test
    void unionsShortScreenshotsById() {
        var s1 = new RawgScreenshotDto(1L, "https://x/1.jpg", 1920, 1080, false);
        var s2 = new RawgScreenshotDto(2L, "https://x/2.jpg", 1920, 1080, false);
        var search = RawgDtoFixtures.dto("p", "P").shortScreenshots(List.of(s1)).build();
        var slug = RawgDtoFixtures.dto("p", "P").shortScreenshots(List.of(s2)).build();
        var id = RawgDtoFixtures.dto("p", "P").shortScreenshots(List.of(s1, s2)).build();
        RawgGameDto merged = mapper.mergeSearchAndDetails(search, slug, id);
        assertThat(merged.shortScreenshots()).extracting(RawgScreenshotDto::id)
                .containsExactly(1L, 2L);
    }

    @Test
    void unionsDevelopersAndPublishersById() {
        var d1 = new RawgDeveloperDto(1, "Valve", "valve", 0, null);
        var d2 = new RawgDeveloperDto(2, "Bethesda", "bethesda", 0, null);
        var p1 = new RawgPublisherDto(10, "Steam", "steam", 0, null);
        var search = RawgDtoFixtures.dto("p", "P")
                .developers(List.of(d1))
                .publishers(List.of(p1))
                .build();
        var slug = RawgDtoFixtures.dto("p", "P")
                .developers(List.of(d2))
                .publishers(List.of())
                .build();
        var id = RawgDtoFixtures.dto("p", "P")
                .developers(List.of(d1, d2))
                .publishers(List.of(p1))
                .build();
        RawgGameDto merged = mapper.mergeSearchAndDetails(search, slug, id);
        assertThat(merged.developers()).extracting(RawgDeveloperDto::id)
                .containsExactly(1, 2);
        assertThat(merged.publishers()).extracting(RawgPublisherDto::id)
                .containsExactly(10);
    }

    @Test
    void unionsAlternativeNamesDeduplicated() {
        var search = RawgDtoFixtures.dto("p", "P")
                .alternativeNames(List.of("Alias A", "Alias B"))
                .build();
        var slug = RawgDtoFixtures.dto("p", "P")
                .alternativeNames(List.of("Alias B", "Alias C"))
                .build();
        var id = RawgDtoFixtures.dto("p", "P")
                .alternativeNames(List.of("Alias A", "Alias C"))
                .build();
        RawgGameDto merged = mapper.mergeSearchAndDetails(search, slug, id);
        assertThat(merged.alternativeNames())
                .containsExactlyInAnyOrder("Alias A", "Alias B", "Alias C");
    }

    @Test
    void mergeIntMapTakesMaxPerKey() {
        var search = RawgDtoFixtures.dto("p", "P")
                .addedByStatus(Map.of("owned", 50, "beaten", 5))
                .build();
        var slug = RawgDtoFixtures.dto("p", "P")
                .addedByStatus(Map.of("owned", 100, "yet", 20))
                .build();
        var id = RawgDtoFixtures.dto("p", "P")
                .addedByStatus(Map.of("owned", 80, "beaten", 12, "yet", 3))
                .build();
        RawgGameDto merged = mapper.mergeSearchAndDetails(search, slug, id);
        assertThat(merged.addedByStatus())
                .containsEntry("owned", 100)
                .containsEntry("beaten", 12)
                .containsEntry("yet", 20);
    }

    @Test
    void mergeIntMapUnionsKeysAcrossSources() {
        var search = RawgDtoFixtures.dto("p", "P")
                .reactions(Map.of("1", 5))
                .build();
        var id = RawgDtoFixtures.dto("p", "P")
                .reactions(Map.of("2", 3, "3", 1))
                .build();
        var slug = RawgDtoFixtures.dto("p", "P").build();
        RawgGameDto merged = mapper.mergeSearchAndDetails(search, slug, id);
        assertThat(merged.reactions())
                .containsEntry("1", 5)
                .containsEntry("2", 3)
                .containsEntry("3", 1);
    }

    @Test
    void identityFieldsAssertEqual_prefersId() {
        var search = RawgDtoFixtures.dto("p", "P").id(100).build();
        var slug = RawgDtoFixtures.dto("p", "P").id(100).build();
        var id = RawgDtoFixtures.dto("p", "P").id(200).build();
        RawgGameDto merged = mapper.mergeSearchAndDetails(search, slug, id);
        assertThat(merged.id()).isEqualTo(200);
    }

    @Test
    void handlesNullDetailsById_mergesSearchAndSlug() {
        var search = RawgDtoFixtures.dto("p", "P")
                .description("from search")
                .rating(3.0)
                .build();
        var slug = RawgDtoFixtures.dto("p", "P")
                .description("from slug")
                .rating(4.5)
                .build();
        RawgGameDto merged = mapper.mergeSearchAndDetails(search, slug, null);
        assertThat(merged.description()).isEqualTo("from slug");
        assertThat(merged.rating()).isEqualTo(4.5);
    }

    @Test
    void handlesNullSearch_returnsSlugAndIdMerge() {
        var search = RawgDtoFixtures.dto("p", "P").id(1).build();
        var slug = RawgDtoFixtures.dto("p", "P")
                .id(1)
                .description("from slug")
                .build();
        var id = RawgDtoFixtures.dto("p", "P")
                .id(1)
                .description("from id")
                .build();
        assertThatThrownBy(() -> mapper.mergeSearchAndDetails(null, slug, id))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("search");
        RawgGameDto merged = mapper.mergeSearchAndDetails(search, slug, id);
        assertThat(merged.description()).isEqualTo("from id");
        assertThat(search).isNotNull();
    }

    @Test
    void handlesNullDetailsBySlug() {
        var search = RawgDtoFixtures.dto("p", "P").build();
        var id = RawgDtoFixtures.dto("p", "P").build();
        assertThatThrownBy(() -> mapper.mergeSearchAndDetails(search, null, id))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("detailsBySlug");
    }

    @Test
    void pickNestedPrefersIdForClip() {
        var clipId = new RawgClipDto("https://clip/id", "https://vid/id", "vidId", null);
        var clipSlug = new RawgClipDto("https://clip/slug", "https://vid/slug", "slugId", null);
        var search = RawgDtoFixtures.dto("p", "P").clip(clipSlug).build();
        var slug = RawgDtoFixtures.dto("p", "P").clip(clipSlug).build();
        var id = RawgDtoFixtures.dto("p", "P").clip(clipId).build();
        RawgGameDto merged = mapper.mergeSearchAndDetails(search, slug, id);
        assertThat(merged.clip()).isSameAs(clipId);
    }

    @Test
    void pickNestedFallsBackThroughChainForEsrbRating() {
        var rating = new RawgEsrbRatingDto(4, "Mature", "mature", "Mature");
        var search = RawgDtoFixtures.dto("p", "P").build();
        var slug = RawgDtoFixtures.dto("p", "P").esrbRating(rating).build();
        var id = RawgDtoFixtures.dto("p", "P").build();
        RawgGameDto merged = mapper.mergeSearchAndDetails(search, slug, id);
        assertThat(merged.esrbRating()).isSameAs(rating);
    }

    @Test
    void unionStoreEntriesByLongId() {
        var store1 = new RawgStoreEntryDto(1L, "https://store/1",
                new RawgStoreRefDto(1, "Steam", "steam", "store.steampowered.com", 0, null));
        var store2 = new RawgStoreEntryDto(2L, "https://store/2",
                new RawgStoreRefDto(2, "GOG", "gog", "gog.com", 0, null));
        var search = RawgDtoFixtures.dto("p", "P").stores(List.of(store1)).build();
        var slug = RawgDtoFixtures.dto("p", "P").stores(List.of(store2)).build();
        var id = RawgDtoFixtures.dto("p", "P").stores(List.of(store1, store2)).build();
        RawgGameDto merged = mapper.mergeSearchAndDetails(search, slug, id);
        assertThat(merged.stores()).extracting(RawgStoreEntryDto::id)
                .containsExactly(1L, 2L);
    }

    @Test
    void ratingsListUnionById() {
        var r1 = new RawgRatingDto(1, "recommended", 100, 70.0);
        var r2 = new RawgRatingDto(2, "meh", 20, 15.0);
        var search = RawgDtoFixtures.dto("p", "P").ratings(List.of(r1)).build();
        var slug = RawgDtoFixtures.dto("p", "P").ratings(List.of(r2)).build();
        var id = RawgDtoFixtures.dto("p", "P").ratings(List.of(r1, r2)).build();
        RawgGameDto merged = mapper.mergeSearchAndDetails(search, slug, id);
        assertThat(merged.ratings()).extracting(RawgRatingDto::id)
                .containsExactly(1, 2);
    }

    @Test
    void pickIntObject_handlesNullValuesCorrectly() {
        var search = RawgDtoFixtures.dto("p", "P").metacritic(85).build();
        var slug = RawgDtoFixtures.dto("p", "P").build();
        var id = RawgDtoFixtures.dto("p", "P").metacritic(90).build();
        RawgGameDto merged = mapper.mergeSearchAndDetails(search, slug, id);
        assertThat(merged.metacritic()).isEqualTo(90);
    }

    @Test
    void pickIntObject_fallsBackToSlugThenSearch() {
        var search = RawgDtoFixtures.dto("p", "P").metacritic(85).build();
        var slug = RawgDtoFixtures.dto("p", "P").metacritic(88).build();
        var id = RawgDtoFixtures.dto("p", "P").build();
        RawgGameDto merged = mapper.mergeSearchAndDetails(search, slug, id);
        assertThat(merged.metacritic()).isEqualTo(88);
    }

    @Test
    void tbaIsTrueIfAnySourceIsTrue() {
        var search = RawgDtoFixtures.dto("p", "P").tba(false).build();
        var slug = RawgDtoFixtures.dto("p", "P").tba(true).build();
        var id = RawgDtoFixtures.dto("p", "P").tba(false).build();
        RawgGameDto merged = mapper.mergeSearchAndDetails(search, slug, id);
        assertThat(merged.tba()).isTrue();
    }

    @Test
    void suggestionsCountUsesMax() {
        var search = RawgDtoFixtures.dto("p", "P").suggestionsCount(2).build();
        var slug = RawgDtoFixtures.dto("p", "P").suggestionsCount(5).build();
        var id = RawgDtoFixtures.dto("p", "P").suggestionsCount(3).build();
        RawgGameDto merged = mapper.mergeSearchAndDetails(search, slug, id);
        assertThat(merged.suggestionsCount()).isEqualTo(5);
    }

    @Test
    void mergeThreeWayPreservesEmptyLists() {
        var search = RawgDtoFixtures.dto("p", "P").genres(List.of()).build();
        var slug = RawgDtoFixtures.dto("p", "P").genres(List.of()).build();
        var id = RawgDtoFixtures.dto("p", "P").genres(List.of()).build();
        RawgGameDto merged = mapper.mergeSearchAndDetails(search, slug, id);
        assertThat(merged.genres()).isEmpty();
    }

    @Test
    void unionShortScreenshots_skipsNullAndZeroId() {
        var s1 = new RawgScreenshotDto(1L, "https://x/1.jpg", 0, 0, false);
        var s2 = new RawgScreenshotDto(0L, "https://x/2.jpg", 0, 0, false);
        var search = RawgDtoFixtures.dto("p", "P").shortScreenshots(List.of(s1, s2)).build();
        RawgGameDto merged = mapper.mergeSearchAndDetails(search, search, null);
        assertThat(merged.shortScreenshots()).hasSize(1);
    }

    @Test
    void backgroundImageAdditional_fallsBackThroughChain() {
        var search = RawgDtoFixtures.dto("p", "P").backgroundImageAdditional("https://bg/extra.jpg").build();
        var slug = RawgDtoFixtures.dto("p", "P").build();
        var id = RawgDtoFixtures.dto("p", "P").build();
        RawgGameDto merged = mapper.mergeSearchAndDetails(search, slug, id);
        assertThat(merged.backgroundImageAdditional()).isEqualTo("https://bg/extra.jpg");
    }

    @Test
    void firstNonBlank_skipsEmptyStrings() {
        var search = RawgDtoFixtures.dto("p", "P").description("").build();
        var slug = RawgDtoFixtures.dto("p", "P").description("from slug").build();
        RawgGameDto merged = mapper.mergeSearchAndDetails(search, slug, null);
        assertThat(merged.description()).isEqualTo("from slug");
    }
}
