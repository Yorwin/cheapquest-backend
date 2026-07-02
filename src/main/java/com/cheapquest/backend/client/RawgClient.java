package com.cheapquest.backend.client;

import com.cheapquest.backend.config.HttpFetcher;
import com.cheapquest.backend.dto.rawg.RawgCreatorDto;
import com.cheapquest.backend.dto.rawg.RawgGameDto;
import com.cheapquest.backend.dto.rawg.RawgListResponseDto;
import com.cheapquest.backend.dto.rawg.RawgMovieDto;
import com.cheapquest.backend.dto.rawg.RawgScreenshotDto;
import com.cheapquest.backend.exception.ApiUnavailableException;
import com.cheapquest.backend.util.StringUtils;
import com.cheapquest.backend.util.Urls;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RawgClient {

    private static final Logger log = LoggerFactory.getLogger(RawgClient.class);
    private static final String SEARCH_PATH = "/games";
    private static final String DETAILS_PATH_PREFIX = "/games/";
    private static final String MOVIES_SUFFIX = "/movies";
    private static final String SCREENSHOTS_SUFFIX = "/screenshots";
    private static final String ADDITIONS_SUFFIX = "/additions";
    private static final String DEVELOPMENT_TEAM_SUFFIX = "/development-team";

    private final HttpFetcher fetcher;
    private final Gson gson;
    private final String baseUrl;
    private final String apiKey;
    private final String keyParam;
    private final java.util.Map<Class<?>, Type> listTypeCache = new java.util.concurrent.ConcurrentHashMap<>();

    public RawgClient(HttpFetcher fetcher, Gson gson, String baseUrl, String apiKey) {
        this.fetcher = fetcher;
        this.gson = gson;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.keyParam = Urls.buildKeyParam(apiKey);
    }

    public List<RawgGameDto> searchByName(String name, int pageSize) {
        if (StringUtils.isBlank(name)) {
            return List.of();
        }
        String url = baseUrl + SEARCH_PATH
                + "?search=" + Urls.encode(name)
                + "&page_size=" + pageSize
                + "&" + keyParam;
        log.debug("rawg_search_by_name name={} pageSize={}", name, pageSize);
        return getList(url, RawgGameDto.class);
    }

    public Optional<RawgGameDto> getDetails(String slugOrId) {
        if (StringUtils.isBlank(slugOrId)) {
            return Optional.empty();
        }
        String url = buildDetailsUrl(slugOrId);
        log.debug("rawg_get_details slugOrId={}", slugOrId);
        return getSingle(url, RawgGameDto.class);
    }

    public List<RawgMovieDto> getMovies(String slugOrId) {
        return getSubList(slugOrId, MOVIES_SUFFIX, RawgMovieDto.class, "movies");
    }

    public List<RawgScreenshotDto> getScreenshots(String slugOrId) {
        return getSubList(slugOrId, SCREENSHOTS_SUFFIX, RawgScreenshotDto.class, "screenshots");
    }

    public List<RawgGameDto> getAdditions(String slugOrId) {
        return getSubList(slugOrId, ADDITIONS_SUFFIX, RawgGameDto.class, "additions");
    }

    public List<RawgCreatorDto> getDevelopmentTeam(String slugOrId) {
        return getSubList(slugOrId, DEVELOPMENT_TEAM_SUFFIX, RawgCreatorDto.class, "development-team");
    }

    private String buildDetailsUrl(String slugOrId) {
        return baseUrl + DETAILS_PATH_PREFIX + Urls.encode(slugOrId) + "?" + keyParam;
    }

    private String buildSubUrl(String slugOrId, String suffix) {
        return baseUrl + DETAILS_PATH_PREFIX + Urls.encode(slugOrId) + suffix + "?" + keyParam;
    }

    private <T> List<T> getSubList(String slugOrId, String suffix, Class<T> elementType, String label) {
        if (StringUtils.isBlank(slugOrId)) {
            return List.of();
        }
        String url = buildSubUrl(slugOrId, suffix);
        log.debug("rawg_get_{} slugOrId={}", label, slugOrId);
        return getList(url, elementType);
    }

    private <T> Optional<T> getSingle(String url, Class<T> type) {
        try {
            String body = fetcher.get(url);
            T parsed = parseOrThrow(body, type, url);
            return Optional.ofNullable(parsed);
        } catch (ApiUnavailableException e) {
            if (e.status() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    private <T> List<T> getList(String url, Class<T> elementType) {
        try {
            String body = fetcher.get(url);
            Type listType = listTypeFor(elementType);
            RawgListResponseDto<T> response = gson.fromJson(body, listType);
            if (response == null || response.results() == null) {
                return List.of();
            }
            return response.results();
        } catch (ApiUnavailableException e) {
            if (e.status() == 404) {
                return List.of();
            }
            throw e;
        } catch (Exception e) {
            throw new ApiUnavailableException(
                    "Failed to parse list response from " + url + ": " + e.getMessage(), e);
        }
    }

    private Type listTypeFor(Class<?> elementType) {
        return listTypeCache.computeIfAbsent(elementType,
                et -> TypeToken.getParameterized(RawgListResponseDto.class, et).getType());
    }

    private <T> T parseOrThrow(String body, Class<T> type, String url) {
        try {
            return gson.fromJson(body, type);
        } catch (Exception e) {
            throw new ApiUnavailableException(
                    "Failed to parse response from " + url + ": " + e.getMessage(), e);
        }
    }
}
