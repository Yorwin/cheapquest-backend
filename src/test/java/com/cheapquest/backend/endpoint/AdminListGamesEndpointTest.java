package com.cheapquest.backend.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cheapquest.backend.dto.admin.ListGamesResponseDto;
import com.cheapquest.backend.service.GameQueueService;
import com.cheapquest.backend.service.GameQueueService.QueueEntry;
import com.cheapquest.backend.service.GameQueueService.Status;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AdminListGamesEndpointTest {

    private static final String TOKEN = "secret-admin-token";
    private static final Instant T1 = Instant.parse("2026-06-29T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-06-30T10:00:00Z");
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private GameQueueService queueService;
    private AdminListGamesEndpoint endpoint;

    @BeforeEach
    void setUp() {
        queueService = mock(GameQueueService.class);
        endpoint = new AdminListGamesEndpoint(TOKEN, queueService, GSON);
    }

    @Test
    void handle_returns200WithPendingEntries() throws Exception {
        when(queueService.list(eq(Status.PENDING))).thenReturn(List.of(
                new QueueEntry("portal", 1, null, T2, null),
                new QueueEntry("hl2", 2, null, T2, "rawg 503")));

        HttpExchange ex = getWithQuery("?status=pending", TOKEN);
        endpoint.handle(ex);

        assertThat(capturedStatus(ex)).isEqualTo(200);
        JsonObject body = capturedJson(ex);
        assertThat(body.get("status").getAsString()).isEqualTo("pending");
        assertThat(body.get("count").getAsInt()).isEqualTo(2);
        JsonArray entries = body.getAsJsonArray("entries");
        assertThat(entries).hasSize(2);
        JsonObject first = entries.get(0).getAsJsonObject();
        assertThat(first.get("slug").getAsString()).isEqualTo("portal");
        assertThat(first.get("attempts").getAsInt()).isEqualTo(1);
        assertThat(first.get("firstAttemptAt").isJsonNull()).isTrue();
        assertThat(first.get("lastAttemptAt").getAsString()).isEqualTo("2026-06-30T10:00:00Z");
        assertThat(first.get("lastError").isJsonNull()).isTrue();
    }

    @Test
    void handle_returns200WithFailedEntriesIncludingFirstAttemptAt() throws Exception {
        when(queueService.list(eq(Status.FAILED))).thenReturn(List.of(
                new QueueEntry("portal", 3, T1, T2, "both sources returned empty")));

        HttpExchange ex = getWithQuery("?status=failed", TOKEN);
        endpoint.handle(ex);

        assertThat(capturedStatus(ex)).isEqualTo(200);
        JsonObject body = capturedJson(ex);
        assertThat(body.get("status").getAsString()).isEqualTo("failed");
        assertThat(body.get("count").getAsInt()).isEqualTo(1);
        JsonObject first = body.getAsJsonArray("entries").get(0).getAsJsonObject();
        assertThat(first.get("firstAttemptAt").getAsString()).isEqualTo("2026-06-29T10:00:00Z");
        assertThat(first.get("lastAttemptAt").getAsString()).isEqualTo("2026-06-30T10:00:00Z");
        assertThat(first.get("lastError").getAsString()).isEqualTo("both sources returned empty");
    }

    @Test
    void handle_returns200WithEmptyListWhenQueueEmpty() throws Exception {
        when(queueService.list(eq(Status.PENDING))).thenReturn(List.of());

        HttpExchange ex = getWithQuery("?status=pending", TOKEN);
        endpoint.handle(ex);

        assertThat(capturedStatus(ex)).isEqualTo(200);
        JsonObject body = capturedJson(ex);
        assertThat(body.get("count").getAsInt()).isZero();
        assertThat(body.getAsJsonArray("entries")).isEmpty();
    }

    @Test
    void handle_acceptsUppercaseStatus() throws Exception {
        when(queueService.list(eq(Status.PENDING))).thenReturn(List.of());

        HttpExchange ex = getWithQuery("?status=PENDING", TOKEN);
        endpoint.handle(ex);

        assertThat(capturedStatus(ex)).isEqualTo(200);
    }

    @Test
    void handle_returns400WhenStatusParamMissing() throws Exception {
        HttpExchange ex = getWithQuery("", TOKEN);
        endpoint.handle(ex);

        assertThat(capturedStatus(ex)).isEqualTo(400);
        assertThat(capturedJson(ex).get("message").getAsString()).contains("status");
    }

    @Test
    void handle_returns400WhenStatusParamUnknown() throws Exception {
        HttpExchange ex = getWithQuery("?status=banana", TOKEN);
        endpoint.handle(ex);

        assertThat(capturedStatus(ex)).isEqualTo(400);
        assertThat(capturedJson(ex).get("message").getAsString())
                .contains("banana")
                .contains("pending")
                .contains("failed");
    }

    @Test
    void handle_returns400WhenStatusParamEmpty() throws Exception {
        HttpExchange ex = getWithQuery("?status=", TOKEN);
        endpoint.handle(ex);

        assertThat(capturedStatus(ex)).isEqualTo(400);
    }

    @Test
    void handle_returns401WhenAuthorizationHeaderMissing() throws Exception {
        HttpExchange ex = getWithQuery("?status=pending", null);
        endpoint.handle(ex);

        assertThat(capturedStatus(ex)).isEqualTo(401);
    }

    @Test
    void handle_returns401WhenBearerTokenIsWrong() throws Exception {
        HttpExchange ex = getWithQuery("?status=pending", "wrong-token");
        endpoint.handle(ex);

        assertThat(capturedStatus(ex)).isEqualTo(401);
    }

    @Test
    void handle_returns400ForNonGetMethod() throws Exception {
        HttpExchange ex = exchangeWithMethod("POST", "?status=pending", TOKEN);
        endpoint.handle(ex);

        assertThat(capturedStatus(ex)).isEqualTo(400);
    }

    @Test
    void handle_returns500WhenServiceThrows() throws Exception {
        when(queueService.list(eq(Status.PENDING)))
                .thenThrow(new RuntimeException("firestore down"));

        HttpExchange ex = getWithQuery("?status=pending", TOKEN);
        endpoint.handle(ex);

        assertThat(capturedStatus(ex)).isEqualTo(500);
        assertThat(capturedJson(ex).get("code").getAsString()).isEqualTo("internal_error");
    }

    @Test
    void handle_forwardsCorrectStatusEnumToService() throws Exception {
        when(queueService.list(eq(Status.FAILED))).thenReturn(List.of());

        HttpExchange ex = getWithQuery("?status=failed", TOKEN);
        endpoint.handle(ex);

        verify(queueService).list(eq(Status.FAILED));
    }

    @Test
    void handle_acceptsStatusAcrossOtherQueryParams() throws Exception {
        when(queueService.list(eq(Status.PENDING))).thenReturn(List.of());

        HttpExchange ex = getWithQuery("?foo=bar&status=pending&baz=qux", TOKEN);
        endpoint.handle(ex);

        assertThat(capturedStatus(ex)).isEqualTo(200);
    }

    @Test
    void handle_urlDecodesStatusParam() throws Exception {
        when(queueService.list(eq(Status.PENDING))).thenReturn(List.of());

        // %70ending -> "pending" once URL-decoded.
        HttpExchange ex = getWithQuery("?status=%70ending", TOKEN);
        endpoint.handle(ex);

        assertThat(capturedStatus(ex)).isEqualTo(200);
    }

    @Test
    void handle_ignoresUnknownQueryParamWithoutStatus() throws Exception {
        HttpExchange ex = getWithQuery("?foo=bar", TOKEN);
        endpoint.handle(ex);

        // No "status" present -> 400.
        assertThat(capturedStatus(ex)).isEqualTo(400);
    }

    @Test
    void handle_returns401WhenAdminTokenIsNotConfigured() throws Exception {
        AdminListGamesEndpoint unconfigured = new AdminListGamesEndpoint(
                "", queueService, GSON);
        HttpExchange ex = getWithQuery("?status=pending", TOKEN);
        unconfigured.handle(ex);

        assertThat(capturedStatus(ex)).isEqualTo(401);
    }

    private static HttpExchange getWithQuery(String query, String token) throws IOException {
        return exchangeWithMethod("GET", query, token);
    }

    private static HttpExchange exchangeWithMethod(String method, String query, String token)
            throws IOException {
        HttpExchange ex = mock(HttpExchange.class);
        Headers headers = new Headers();
        if (token != null) {
            headers.add("Authorization", "Bearer " + token);
        }
        ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        when(ex.getRequestMethod()).thenReturn(method);
        when(ex.getRequestHeaders()).thenReturn(headers);
        when(ex.getResponseHeaders()).thenReturn(new Headers());
        when(ex.getRequestURI()).thenReturn(URI.create("/admin/games" + query));
        when(ex.getResponseBody()).thenReturn(responseBody);
        return ex;
    }

    private static int capturedStatus(HttpExchange ex) throws IOException {
        ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
        verify(ex).sendResponseHeaders(captor.capture(),
                org.mockito.ArgumentMatchers.anyLong());
        return captor.getValue();
    }

    private static JsonObject capturedJson(HttpExchange ex) throws IOException {
        ByteArrayOutputStream out = (ByteArrayOutputStream) ex.getResponseBody();
        return JsonParser.parseString(out.toString(StandardCharsets.UTF_8))
                .getAsJsonObject();
    }
}
