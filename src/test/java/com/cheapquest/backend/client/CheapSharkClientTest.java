package com.cheapquest.backend.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cheapquest.backend.config.HttpFetcher;
import com.cheapquest.backend.dto.cheapshark.CheapSharkGameDetailDto;
import com.cheapquest.backend.dto.cheapshark.CheapSharkGameSummaryDto;
import com.cheapquest.backend.dto.cheapshark.CheapSharkStoreDto;
import com.cheapquest.backend.exception.ApiUnavailableException;
import com.google.gson.Gson;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CheapSharkClientTest {

    private HttpFetcher fetcher;
    private Gson gson;
    private CheapSharkClient client;
    private static final String BASE = "https://www.cheapshark.com/api/1.0";

    @BeforeEach
    void setUp() {
        fetcher = mock(HttpFetcher.class);
        gson = new Gson();
        client = new CheapSharkClient(fetcher, gson, BASE);
    }

    @Test
    void getStores_returnsParsedList() {
        String body = "[{\"storeID\":\"1\",\"storeName\":\"Steam\",\"isActive\":1,"
                + "\"images\":{\"banner\":\"/b.png\",\"logo\":\"/l.png\",\"icon\":\"/i.png\"}}]";
        when(fetcher.get(BASE + "/stores")).thenReturn(body);

        List<CheapSharkStoreDto> result = client.getStores();

        assertThat(result).hasSize(1);
        CheapSharkStoreDto s = result.get(0);
        assertThat(s.storeId()).isEqualTo("1");
        assertThat(s.storeName()).isEqualTo("Steam");
        assertThat(s.isActive()).isEqualTo(1);
        assertThat(s.images().banner()).isEqualTo("/b.png");
    }

    @Test
    void getStores_propagatesApiError() {
        when(fetcher.get(anyString())).thenThrow(new ApiUnavailableException("HTTP 500", 500, "boom"));

        assertThatThrownBy(() -> client.getStores())
                .isInstanceOf(ApiUnavailableException.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    void findByTitle_encodesAndReturns() {
        String body = "[{\"gameID\":\"82\",\"steamAppID\":\"400\",\"cheapest\":\"1.99\","
                + "\"cheapestDealID\":\"x\",\"external\":\"Portal\",\"internalName\":\"PORTAL\","
                + "\"thumb\":\"http://x/portal.jpg\"}]";
        when(fetcher.get(BASE + "/games?title=Portal")).thenReturn(body);

        List<CheapSharkGameSummaryDto> result = client.findByTitle("Portal");

        assertThat(result).hasSize(1);
        CheapSharkGameSummaryDto s = result.get(0);
        assertThat(s.gameId()).isEqualTo("82");
        assertThat(s.external()).isEqualTo("Portal");
    }

    @Test
    void findByTitle_returnsEmptyOnBlank() {
        assertThat(client.findByTitle(null)).isEmpty();
        assertThat(client.findByTitle("")).isEmpty();
        assertThat(client.findByTitle("   ")).isEmpty();
    }

    @Test
    void getDetails_returnsParsed() {
        String body = "{\"info\":{\"title\":\"Anno 2070\",\"steamAppID\":\"48240\",\"thumb\":\"http://x.jpg\"},"
                + "\"cheapestPriceEver\":{\"price\":\"4.73\",\"date\":1775813777},"
                + "\"deals\":[{\"storeID\":\"1\",\"dealID\":\"d1\",\"price\":\"4.99\","
                + "\"retailPrice\":\"19.99\",\"savings\":\"75.037519\"}]}";
        when(fetcher.get(BASE + "/games?id=13")).thenReturn(body);

        Optional<CheapSharkGameDetailDto> result = client.getDetails("13");

        assertThat(result).isPresent();
        assertThat(result.get().info().title()).isEqualTo("Anno 2070");
        assertThat(result.get().cheapestPriceEver().price()).isEqualTo("4.73");
        assertThat(result.get().deals()).hasSize(1);
        assertThat(result.get().deals().get(0).storeId()).isEqualTo("1");
    }

    @Test
    void getDetails_returnsEmptyOnBlank() {
        assertThat(client.getDetails(null)).isEmpty();
        assertThat(client.getDetails("")).isEmpty();
    }
}
