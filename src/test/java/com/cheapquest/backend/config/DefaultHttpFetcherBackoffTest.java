package com.cheapquest.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cheapquest.backend.exception.ApiUnavailableException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class DefaultHttpFetcherBackoffTest {

    @Test
    void retries_on_429_then_succeeds() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> r429 = mock(HttpResponse.class);
        when(r429.statusCode()).thenReturn(429);
        when(r429.body()).thenReturn("rate limited");

        HttpResponse<String> r200 = mock(HttpResponse.class);
        when(r200.statusCode()).thenReturn(200);
        when(r200.body()).thenReturn("ok");

        doReturn(r429).doReturn(r200).when(http).send(any(HttpRequest.class), any());

        DefaultHttpFetcher fetcher = new DefaultHttpFetcher(http, 5, 3, 1L);

        long start = System.currentTimeMillis();
        String result = fetcher.get("http://x/api");
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result).isEqualTo("ok");
        assertThat(elapsed).isGreaterThanOrEqualTo(1L);
        verify(http, times(2)).send(any(HttpRequest.class), any());
    }

    @Test
    void throws_after_max_attempts_on_503() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> r503 = mock(HttpResponse.class);
        when(r503.statusCode()).thenReturn(503);
        when(r503.body()).thenReturn("down");

        doReturn(r503).when(http).send(any(HttpRequest.class), any());

        DefaultHttpFetcher fetcher = new DefaultHttpFetcher(http, 5, 3, 1L);

        assertThatThrownBy(() -> fetcher.get("http://x/api"))
                .isInstanceOf(ApiUnavailableException.class)
                .hasMessageContaining("HTTP 503");

        verify(http, times(3)).send(any(HttpRequest.class), any());
    }

    @Test
    void does_not_retry_on_404() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> r404 = mock(HttpResponse.class);
        when(r404.statusCode()).thenReturn(404);
        when(r404.body()).thenReturn("not found");

        doReturn(r404).when(http).send(any(HttpRequest.class), any());

        DefaultHttpFetcher fetcher = new DefaultHttpFetcher(http, 5, 3, 1L);

        assertThatThrownBy(() -> fetcher.get("http://x/api"))
                .isInstanceOf(ApiUnavailableException.class)
                .hasMessageContaining("HTTP 404");

        verify(http, times(1)).send(any(HttpRequest.class), any());
    }

    @Test
    void throws_when_max_attempts_less_than_one() {
        HttpClient http = mock(HttpClient.class);
        assertThatThrownBy(() -> new DefaultHttpFetcher(http, 5, 0, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
