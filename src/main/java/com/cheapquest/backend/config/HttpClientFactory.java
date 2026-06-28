package com.cheapquest.backend.config;

import java.net.http.HttpClient;
import java.time.Duration;

public final class HttpClientFactory {

    private HttpClientFactory() {
    }

    public static HttpClient create(int timeoutSeconds) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }
}
