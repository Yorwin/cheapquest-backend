package com.cheapquest.backend.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cheapquest.backend.config.DefaultHttpFetcher;
import com.cheapquest.backend.config.HttpClientFactory;
import com.cheapquest.backend.dto.rawg.RawgCreatorDto;
import com.cheapquest.backend.dto.rawg.RawgGameDto;
import com.cheapquest.backend.dto.rawg.RawgMovieDto;
import com.cheapquest.backend.dto.rawg.RawgScreenshotDto;
import com.cheapquest.backend.exception.ApiUnavailableException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.google.gson.Gson;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RawgClientTest {

    private static final String API_KEY = "test-key";

    private WireMockServer wm;
    private RawgClient client;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(wireMockConfig().dynamicPort());
        wm.start();
        HttpClient http = HttpClientFactory.create(5);
        DefaultHttpFetcher fetcher = new DefaultHttpFetcher(http, 5, 1, 1L);
        client = new RawgClient(fetcher, new Gson(), wm.baseUrl(), API_KEY);
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    @Test
    void searchByName_returnsParsedList() {
        String body = """
                {
                  "count": 2,
                  "next": null,
                  "previous": null,
                  "results": [
                    {"id": 1, "slug": "far-cry", "name": "Far Cry"},
                    {"id": 2, "slug": "far-cry-2", "name": "Far Cry 2"}
                  ]
                }
                """;
        wm.stubFor(get(urlPathEqualTo("/games"))
                .withQueryParam("search", equalTo("Far Cry"))
                .withQueryParam("page_size", equalTo("10"))
                .withQueryParam("key", equalTo(API_KEY))
                .willReturn(aResponse().withStatus(200).withBody(body)));

        List<RawgGameDto> results = client.searchByName("Far Cry", 10);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).slug()).isEqualTo("far-cry");
        assertThat(results.get(0).name()).isEqualTo("Far Cry");
        assertThat(results.get(1).slug()).isEqualTo("far-cry-2");
    }

    @Test
    void searchByName_encodesQueryParam() {
        wm.stubFor(get(urlPathEqualTo("/games"))
                .withQueryParam("search", equalTo("Half Life 2"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"count": 0, "next": null, "previous": null, "results": []}
                        """)));

        List<RawgGameDto> results = client.searchByName("Half Life 2", 10);

        assertThat(results).isEmpty();
    }

    @Test
    void searchByName_emptyOnBlankName() {
        assertThat(client.searchByName(null, 10)).isEmpty();
        assertThat(client.searchByName("", 10)).isEmpty();
        assertThat(client.searchByName("   ", 10)).isEmpty();
    }

    @Test
    void getDetails_returnsParsedGame() {
        String body = """
                {
                  "id": 13536,
                  "slug": "portal",
                  "name": "Portal",
                  "released": "2007-10-09",
                  "background_image": "https://x/p.jpg",
                  "clip": null
                }
                """;
        wm.stubFor(get(urlPathEqualTo("/games/portal"))
                .withQueryParam("key", equalTo(API_KEY))
                .willReturn(aResponse().withStatus(200).withBody(body)));

        Optional<RawgGameDto> result = client.getDetails("portal");

        assertThat(result).isPresent();
        assertThat(result.get().slug()).isEqualTo("portal");
        assertThat(result.get().name()).isEqualTo("Portal");
        assertThat(result.get().released()).isEqualTo("2007-10-09");
    }

    @Test
    void getDetails_returnsEmptyOn404() {
        wm.stubFor(get(urlPathEqualTo("/games/unknown"))
                .willReturn(aResponse().withStatus(404).withBody("not found")));

        assertThat(client.getDetails("unknown")).isEmpty();
    }

    @Test
    void getDetails_throwsOn5xx() {
        wm.stubFor(get(urlPathEqualTo("/games/error"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        assertThatThrownBy(() -> client.getDetails("error"))
                .isInstanceOf(ApiUnavailableException.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    void getDetails_emptyOnBlankInput() {
        assertThat(client.getDetails(null)).isEmpty();
        assertThat(client.getDetails("")).isEmpty();
        assertThat(client.getDetails("   ")).isEmpty();
    }

    @Test
    void getMovies_returnsList() {
        wm.stubFor(get(urlPathEqualTo("/games/portal/movies"))
                .withQueryParam("key", equalTo(API_KEY))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {
                          "count": 1,
                          "next": null,
                          "previous": null,
                          "results": [
                            {"id": 1, "name": "Trailer", "preview": "https://x/p.jpg", "data": {"480": "https://yt/480", "max": "https://yt/max"}}
                          ]
                        }
                        """)));

        List<RawgMovieDto> movies = client.getMovies("portal");

        assertThat(movies).hasSize(1);
        assertThat(movies.get(0).name()).isEqualTo("Trailer");
        assertThat(movies.get(0).data().max()).isEqualTo("https://yt/max");
    }

    @Test
    void getMovies_emptyOn404() {
        wm.stubFor(get(urlPathEqualTo("/games/portal/movies"))
                .willReturn(aResponse().withStatus(404).withBody("none")));

        assertThat(client.getMovies("portal")).isEmpty();
    }

    @Test
    void getMovies_emptyOnBlankInput() {
        assertThat(client.getMovies(null)).isEmpty();
        assertThat(client.getMovies("")).isEmpty();
    }

    @Test
    void getScreenshots_returnsList() {
        wm.stubFor(get(urlPathEqualTo("/games/portal/screenshots"))
                .withQueryParam("key", equalTo(API_KEY))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {
                          "count": 2,
                          "next": null,
                          "previous": null,
                          "results": [
                            {"id": 1, "image": "https://x/1.jpg", "width": 1920, "height": 1080, "is_deleted": false},
                            {"id": 2, "image": "https://x/2.jpg", "width": 1920, "height": 1080, "is_deleted": false}
                          ]
                        }
                        """)));

        List<RawgScreenshotDto> screenshots = client.getScreenshots("portal");

        assertThat(screenshots).hasSize(2);
        assertThat(screenshots.get(0).image()).isEqualTo("https://x/1.jpg");
    }

    @Test
    void getAdditions_returnsListOfGameDtos() {
        wm.stubFor(get(urlPathEqualTo("/games/portal/additions"))
                .withQueryParam("key", equalTo(API_KEY))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {
                          "count": 1,
                          "next": null,
                          "previous": null,
                          "results": [
                            {"id": 100, "slug": "portal-with-rtx", "name": "Portal with RTX", "released": "2022-12-08"}
                          ]
                        }
                        """)));

        List<RawgGameDto> additions = client.getAdditions("portal");

        assertThat(additions).hasSize(1);
        assertThat(additions.get(0).slug()).isEqualTo("portal-with-rtx");
        assertThat(additions.get(0).released()).isEqualTo("2022-12-08");
    }

    @Test
    void getDevelopmentTeam_returnsListOfCreators() {
        wm.stubFor(get(urlPathEqualTo("/games/portal/development-team"))
                .withQueryParam("key", equalTo(API_KEY))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {
                          "count": 2,
                          "next": null,
                          "previous": null,
                          "results": [
                            {"id": 1, "name": "Gabe Newell", "slug": "gabe-newell", "position": "Founder", "games_count": 1},
                            {"id": 2, "name": "Kim Swift", "slug": "kim-swift", "position": "Designer", "games_count": 1}
                          ]
                        }
                        """)));

        List<RawgCreatorDto> team = client.getDevelopmentTeam("portal");

        assertThat(team).hasSize(2);
        assertThat(team.get(0).name()).isEqualTo("Gabe Newell");
        assertThat(team.get(0).position()).isEqualTo("Founder");
        assertThat(team.get(1).name()).isEqualTo("Kim Swift");
    }

    @Test
    void getList_throwsOnMalformedJson() {
        wm.stubFor(get(urlPathEqualTo("/games/portal/movies"))
                .willReturn(aResponse().withStatus(200).withBody("{ not valid json")));

        assertThatThrownBy(() -> client.getMovies("portal"))
                .isInstanceOf(ApiUnavailableException.class)
                .hasMessageContaining("Failed to parse");
    }
}
