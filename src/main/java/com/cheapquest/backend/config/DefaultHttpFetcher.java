package com.cheapquest.backend.config;

import com.cheapquest.backend.exception.ApiUnavailableException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DefaultHttpFetcher implements HttpFetcher {

    private static final Logger log = LoggerFactory.getLogger(DefaultHttpFetcher.class);
    private static final int BODY_TRUNCATE = 500;

    private final HttpClient http;
    private final int timeoutSeconds;
    private final int maxAttempts;
    private final long baseDelayMillis;

    public DefaultHttpFetcher(HttpClient http, int timeoutSeconds, int maxAttempts, long baseDelayMillis) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        this.http = http;
        this.timeoutSeconds = timeoutSeconds;
        this.maxAttempts = maxAttempts;
        this.baseDelayMillis = baseDelayMillis;
    }

    @Override
    public String get(String url) {
        int attempt = 0;
        int lastStatus = 0;
        String lastBody = null;
        while (attempt < maxAttempts) {
            attempt++;
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .GET()
                        .build();
                HttpResponse<String> response = http.send(request, BodyHandlers.ofString());
                int status = response.statusCode();
                String body = response.body();
                if (status == 200) {
                    return body;
                }
                lastStatus = status;
                lastBody = body;
                if (!isRetryable(status) || attempt >= maxAttempts) {
                    throw new ApiUnavailableException(
                            "HTTP " + status + " on " + maskSecretQueryParam(url), status, truncate(body, BODY_TRUNCATE));
                }
                long delay = computeDelay(attempt);
                log.warn("http_backoff url={} attempt={} status={} delayMs={}", maskSecretQueryParam(url), attempt, status, delay);
                sleep(delay);
            } catch (ApiUnavailableException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiUnavailableException("Interrupted while fetching " + url, e);
            } catch (Exception e) {
                if (attempt >= maxAttempts) {
                    throw new ApiUnavailableException(
                            "Failed to fetch " + url + " after " + maxAttempts + " attempts: " + e.getMessage(), e);
                }
                long delay = computeDelay(attempt);
                log.warn("http_backoff url={} attempt={} error={} delayMs={}",
                        maskSecretQueryParam(url), attempt, e.getClass().getSimpleName(), delay);
                sleep(delay);
            }
        }
        throw new ApiUnavailableException(
                "Exhausted " + maxAttempts + " attempts for " + maskSecretQueryParam(url),
                lastStatus, truncate(lastBody, BODY_TRUNCATE));
    }

    static String maskSecretQueryParam(String url) {
        if (url == null) {
            return null;
        }
        return url.replaceAll("([?&])key=[^&]*", "$1key=***");
    }

    private static boolean isRetryable(int status) {
        return status == 429 || status == 503;
    }

    private long computeDelay(int attempt) {
        long multiplier = 1L << (attempt - 1);
        return baseDelayMillis * multiplier;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiUnavailableException("Interrupted during backoff", e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
