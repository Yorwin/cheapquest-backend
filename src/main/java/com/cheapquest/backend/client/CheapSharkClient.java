package com.cheapquest.backend.client;

import com.cheapquest.backend.config.HttpFetcher;
import com.cheapquest.backend.dto.cheapshark.CheapSharkGameDetailDto;
import com.cheapquest.backend.dto.cheapshark.CheapSharkGameSummaryDto;
import com.cheapquest.backend.dto.cheapshark.CheapSharkStoreDto;
import com.cheapquest.backend.exception.ApiUnavailableException;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CheapSharkClient {

    private static final Logger log = LoggerFactory.getLogger(CheapSharkClient.class);
    private static final Type STORE_LIST_TYPE = new TypeToken<List<CheapSharkStoreDto>>() { }.getType();
    private static final Type SUMMARY_LIST_TYPE = new TypeToken<List<CheapSharkGameSummaryDto>>() { }.getType();

    private final HttpFetcher fetcher;
    private final Gson gson;
    private final String baseUrl;

    public CheapSharkClient(HttpFetcher fetcher, Gson gson, String baseUrl) {
        this.fetcher = fetcher;
        this.gson = gson;
        this.baseUrl = baseUrl;
    }

    public List<CheapSharkStoreDto> getStores() {
        String url = baseUrl + "/stores";
        String body = fetcher.get(url);
        try {
            return gson.fromJson(body, STORE_LIST_TYPE);
        } catch (Exception e) {
            throw new ApiUnavailableException("Failed to parse /stores response: " + e.getMessage(), e);
        }
    }

    public List<CheapSharkGameSummaryDto> findByTitle(String title) {
        if (title == null || title.isBlank()) {
            return List.of();
        }
        String encoded = URLEncoder.encode(title, StandardCharsets.UTF_8);
        String url = baseUrl + "/games?title=" + encoded;
        log.debug("cheapshark_find_by_title title={} url={}", title, url);
        String body = fetcher.get(url);
        try {
            return gson.fromJson(body, SUMMARY_LIST_TYPE);
        } catch (Exception e) {
            throw new ApiUnavailableException("Failed to parse /games?title response: " + e.getMessage(), e);
        }
    }

    public Optional<CheapSharkGameDetailDto> getDetails(String gameId) {
        if (gameId == null || gameId.isBlank()) {
            return Optional.empty();
        }
        String url = baseUrl + "/games?id=" + URLEncoder.encode(gameId, StandardCharsets.UTF_8);
        log.debug("cheapshark_get_details gameId={} url={}", gameId, url);
        String body = fetcher.get(url);
        try {
            return Optional.ofNullable(gson.fromJson(body, CheapSharkGameDetailDto.class));
        } catch (Exception e) {
            throw new ApiUnavailableException("Failed to parse /games?id response: " + e.getMessage(), e);
        }
    }
}
