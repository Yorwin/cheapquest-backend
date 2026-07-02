package com.cheapquest.backend.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cheapquest.backend.dto.HydrationReport;
import com.cheapquest.backend.exception.GlobalExceptionHandler;
import com.cheapquest.backend.mapper.FirebaseMapper;
import com.cheapquest.backend.service.GameHydrationService;
import com.google.gson.Gson;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AdminRefreshEndpointTest {

    private static final String TOKEN = "test-admin-token";
    private final Gson gson = FirebaseMapper.newGson();
    private final GameHydrationService hydration = mock(GameHydrationService.class);
    private final RefreshLock lock = new InMemoryRefreshLock();
    private final RefreshService refreshService = new RefreshService(lock, hydration, Clock.systemUTC());

    @Test
    void returns_200_with_outcome_on_valid_post() throws IOException {
        when(hydration.hydrateAll(anyBoolean()))
                .thenReturn(new HydrationReport(5, 4, 1, 0, 0, 0, 2, 4, 0, 250L, List.of(), List.of()));
        HttpExchange ex = postWithBody("");

        new AdminRefreshEndpoint(TOKEN, refreshService, gson).handle(ex);

        String body = capturedBody(ex);
        assertThat(body).contains("\"status\":\"completed\"");
        assertThat(body).contains("\"processed\":5");
        assertThat(body).contains("\"failed\":0");
    }

    @Test
    void returns_401_when_token_missing() throws IOException {
        HttpExchange ex = postWithBody("");
        ex.getRequestHeaders().remove("Authorization");

        new AdminRefreshEndpoint(TOKEN, refreshService, gson).handle(ex);

        ArgumentCaptor<Integer> status = ArgumentCaptor.forClass(Integer.class);
        org.mockito.Mockito.verify(ex).sendResponseHeaders(status.capture(), org.mockito.ArgumentMatchers.anyLong());
        assertThat(status.getValue()).isEqualTo(GlobalExceptionHandler.SC_UNAUTHORIZED);
        assertThat(capturedBody(ex)).contains("\"code\":\"unauthorized\"");
    }

    @Test
    void returns_401_when_token_wrong() throws IOException {
        HttpExchange ex = postWithBody("");
        ex.getRequestHeaders().replace("Authorization", List.of("Bearer wrong"));

        new AdminRefreshEndpoint(TOKEN, refreshService, gson).handle(ex);

        ArgumentCaptor<Integer> status = ArgumentCaptor.forClass(Integer.class);
        org.mockito.Mockito.verify(ex).sendResponseHeaders(status.capture(), org.mockito.ArgumentMatchers.anyLong());
        assertThat(status.getValue()).isEqualTo(GlobalExceptionHandler.SC_UNAUTHORIZED);
    }

    @Test
    void returns_401_when_endpoint_not_configured() throws IOException {
        HttpExchange ex = postWithBody("");
        new AdminRefreshEndpoint(null, refreshService, gson).handle(ex);

        ArgumentCaptor<Integer> status = ArgumentCaptor.forClass(Integer.class);
        org.mockito.Mockito.verify(ex).sendResponseHeaders(status.capture(), org.mockito.ArgumentMatchers.anyLong());
        assertThat(status.getValue()).isEqualTo(GlobalExceptionHandler.SC_UNAUTHORIZED);
    }

    @Test
    void returns_409_when_lock_held() throws IOException {
        // Hold the lock manually; the endpoint must surface a 409
        // without calling hydration.
        lock.tryAcquire();
        HttpExchange ex = postWithBody("");

        new AdminRefreshEndpoint(TOKEN, refreshService, gson).handle(ex);

        ArgumentCaptor<Integer> status = ArgumentCaptor.forClass(Integer.class);
        org.mockito.Mockito.verify(ex).sendResponseHeaders(status.capture(), org.mockito.ArgumentMatchers.anyLong());
        assertThat(status.getValue()).isEqualTo(GlobalExceptionHandler.SC_CONFLICT);
        assertThat(capturedBody(ex)).contains("\"code\":\"conflict\"");
    }

    @Test
    void returns_400_on_get() throws IOException {
        HttpExchange ex = mock(HttpExchange.class);
        Headers headers = new Headers();
        headers.add("Authorization", "Bearer " + TOKEN);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        when(ex.getRequestMethod()).thenReturn("GET");
        when(ex.getRequestHeaders()).thenReturn(headers);
        when(ex.getResponseHeaders()).thenReturn(new Headers());
        when(ex.getResponseBody()).thenReturn(body);

        new AdminRefreshEndpoint(TOKEN, refreshService, gson).handle(ex);

        ArgumentCaptor<Integer> status = ArgumentCaptor.forClass(Integer.class);
        org.mockito.Mockito.verify(ex).sendResponseHeaders(status.capture(), org.mockito.ArgumentMatchers.anyLong());
        assertThat(status.getValue()).isEqualTo(GlobalExceptionHandler.SC_BAD_REQUEST);
    }

    @Test
    void parses_force_from_body() throws IOException {
        when(hydration.hydrateAll(anyBoolean()))
                .thenReturn(new HydrationReport(0, 0, 0, 0, 0, 0, 0, 0, 0, 0L, List.of(), List.of()));
        HttpExchange ex = postWithBody("{\"force\":true}");

        new AdminRefreshEndpoint(TOKEN, refreshService, gson).handle(ex);

        org.mockito.Mockito.verify(hydration).hydrateAll(true);
    }

    @Test
    void empty_body_treated_as_default_request() throws IOException {
        when(hydration.hydrateAll(anyBoolean()))
                .thenReturn(new HydrationReport(0, 0, 0, 0, 0, 0, 0, 0, 0, 0L, List.of(), List.of()));
        HttpExchange ex = postWithBody("");

        new AdminRefreshEndpoint(TOKEN, refreshService, gson).handle(ex);

        org.mockito.Mockito.verify(hydration).hydrateAll(false);
    }

    @Test
    void releases_lock_after_successful_run() throws IOException {
        when(hydration.hydrateAll(anyBoolean()))
                .thenReturn(new HydrationReport(0, 0, 0, 0, 0, 0, 0, 0, 0, 0L, List.of(), List.of()));
        new AdminRefreshEndpoint(TOKEN, refreshService, gson).handle(postWithBody(""));

        assertThat(lock.isHeld()).isFalse();
    }

    private HttpExchange postWithBody(String body) throws IOException {
        HttpExchange ex = mock(HttpExchange.class);
        Headers headers = new Headers();
        headers.add("Authorization", "Bearer " + TOKEN);
        ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        when(ex.getRequestMethod()).thenReturn("POST");
        when(ex.getRequestHeaders()).thenReturn(headers);
        when(ex.getResponseHeaders()).thenReturn(new Headers());
        InputStream requestBody = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        when(ex.getRequestBody()).thenReturn(requestBody);
        when(ex.getResponseBody()).thenReturn(responseBody);
        return ex;
    }

    private String capturedBody(HttpExchange ex) throws IOException {
        return ((ByteArrayOutputStream) ex.getResponseBody()).toString(StandardCharsets.UTF_8);
    }
}
