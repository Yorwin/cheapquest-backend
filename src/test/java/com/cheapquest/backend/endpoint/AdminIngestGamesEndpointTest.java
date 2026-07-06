package com.cheapquest.backend.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cheapquest.backend.service.GameIngestService;
import com.cheapquest.backend.service.GameIngestService.IngestAction;
import com.cheapquest.backend.service.GameIngestService.IngestFailure;
import com.cheapquest.backend.service.GameIngestService.IngestItem;
import com.cheapquest.backend.service.GameIngestService.IngestOutcome;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AdminIngestGamesEndpointTest {

    private static final String TOKEN = "secret-admin-token";
    private static final Gson GSON = new Gson();

    private GameIngestService ingestService;
    private AdminIngestGamesEndpoint endpoint;

    @BeforeEach
    void setUp() {
        ingestService = mock(GameIngestService.class);
        endpoint = new AdminIngestGamesEndpoint(TOKEN, ingestService, GSON);
    }

    @Test
    void handle_returns200WithAcceptedAndFailedLists() throws Exception {
        IngestOutcome outcome = new IngestOutcome(
                List.of(
                        new IngestItem("Portal", "portal", IngestAction.CREATED),
                        new IngestItem("Hades", "hades", IngestAction.ALREADY_EXISTED)),
                List.of(new IngestFailure("!!!", "empty slug: \"!!!\"")));
        when(ingestService.ingest(anyList(), any())).thenReturn(outcome);

        HttpExchange ex = postWithBody(
                "{\"names\":[\"Portal\",\"Hades\",\"!!!\"]}", "application/json", TOKEN);
        endpoint.handle(ex);

        assertThat(capturedStatus(ex)).isEqualTo(200);
        JsonObject body = capturedJson(ex);
        assertThat(body.get("status").getAsString()).isEqualTo("completed");

        JsonArray accepted = body.getAsJsonArray("accepted");
        assertThat(accepted).hasSize(2);
        assertThat(accepted.get(0).getAsJsonObject().get("name").getAsString()).isEqualTo("Portal");
        assertThat(accepted.get(0).getAsJsonObject().get("slug").getAsString()).isEqualTo("portal");
        assertThat(accepted.get(0).getAsJsonObject().get("action").getAsString())
                .isEqualTo("CREATED");
        assertThat(accepted.get(1).getAsJsonObject().get("action").getAsString())
                .isEqualTo("ALREADY_EXISTED");

        JsonArray failed = body.getAsJsonArray("failed");
        assertThat(failed).hasSize(1);
        assertThat(failed.get(0).getAsJsonObject().get("name").getAsString()).isEqualTo("!!!");
        assertThat(failed.get(0).getAsJsonObject().get("error").getAsString())
                .contains("empty slug");
    }

    @Test
    void handle_returns200EvenWhenAllNamesFail() throws Exception {
        IngestOutcome outcome = new IngestOutcome(
                List.of(),
                List.of(new IngestFailure("!!!", "empty slug")));
        when(ingestService.ingest(anyList(), any())).thenReturn(outcome);

        HttpExchange ex = postWithBody("{\"names\":[\"!!!\"]}", "application/json", TOKEN);
        endpoint.handle(ex);

        assertThat(capturedStatus(ex)).isEqualTo(200);
        JsonObject body = capturedJson(ex);
        assertThat(body.getAsJsonArray("accepted")).isEmpty();
        assertThat(body.getAsJsonArray("failed")).hasSize(1);
    }

    @Test
    void handle_forwardsExplicitLanguageToService() throws Exception {
        when(ingestService.ingest(anyList(), eq("en"))).thenReturn(emptyOutcome());

        HttpExchange ex = postWithBody(
                "{\"names\":[\"Portal\"],\"language\":\"en\"}", "application/json", TOKEN);
        endpoint.handle(ex);

        assertThat(capturedStatus(ex)).isEqualTo(200);
    }

    @Test
    void handle_passesNullLanguageWhenAbsentInBody() throws Exception {
        when(ingestService.ingest(anyList(), isNull())).thenReturn(emptyOutcome());

        HttpExchange ex = postWithBody("{\"names\":[\"Portal\"]}", "application/json", TOKEN);
        endpoint.handle(ex);

        assertThat(capturedStatus(ex)).isEqualTo(200);
    }

    @Test
    void handle_returns400WhenLanguageNotEnglish() throws Exception {
        when(ingestService.ingest(anyList(), any()))
                .thenThrow(new IllegalArgumentException(
                        "language must be one of [en], got \"es\""));

        HttpExchange ex = postWithBody(
                "{\"names\":[\"Portal\"],\"language\":\"es\"}", "application/json", TOKEN);
        endpoint.handle(ex);

        assertThat(capturedStatus(ex)).isEqualTo(400);
        JsonObject body = capturedJson(ex);
        assertThat(body.get("code").getAsString()).isEqualTo("bad_request");
        assertThat(body.get("message").getAsString()).contains("language");
    }

    @Test
    void handle_returns400WhenServiceRejectsBatchSize() throws Exception {
        when(ingestService.ingest(anyList(), any()))
                .thenThrow(new IllegalArgumentException("batch size 101 exceeds maximum 100"));

        HttpExchange ex = postWithBody("{\"names\":[\"x\"]}", "application/json", TOKEN);
        endpoint.handle(ex);

        assertThat(capturedStatus(ex)).isEqualTo(400);
        assertThat(capturedJson(ex).get("message").getAsString()).contains("batch size");
    }

    @Test
    void handle_returns401WhenAuthorizationHeaderMissing() throws Exception {
        HttpExchange ex = postWithBody("{\"names\":[\"Portal\"]}", "application/json", null);
        endpoint.handle(ex);

        assertThat(capturedStatus(ex)).isEqualTo(401);
    }

    @Test
    void handle_returns401WhenBearerTokenIsWrong() throws Exception {
        HttpExchange ex = postWithBody(
                "{\"names\":[\"Portal\"]}", "application/json", "wrong-token");
        endpoint.handle(ex);

        assertThat(capturedStatus(ex)).isEqualTo(401);
    }

    @Test
    void handle_returns400WhenBodyIsEmpty() throws Exception {
        HttpExchange ex = postWithBody("", "application/json", TOKEN);
        endpoint.handle(ex);

        assertThat(capturedStatus(ex)).isEqualTo(400);
        assertThat(capturedJson(ex).get("message").getAsString()).contains("body is required");
    }

    @Test
    void handle_returns400WhenBodyIsNotJson() throws Exception {
        // Unterminated object: GSON fails to parse and the
        // catch (Throwable) maps it to 400 via the JSON
        // parsing error path.
        HttpExchange ex = postWithBody("{not json", "application/json", TOKEN);
        endpoint.handle(ex);

        assertThat(capturedStatus(ex)).isEqualTo(400);
    }

    @Test
    void handle_returns400WhenContentTypeIsNotJson() throws Exception {
        HttpExchange ex = postWithBody("{\"names\":[\"Portal\"]}", "text/plain", TOKEN);
        endpoint.handle(ex);

        assertThat(capturedStatus(ex)).isEqualTo(400);
        assertThat(capturedJson(ex).get("message").getAsString()).contains("Content-Type");
    }

    @Test
    void handle_acceptsContentTypeWithCharset() throws Exception {
        when(ingestService.ingest(anyList(), any())).thenReturn(emptyOutcome());

        HttpExchange ex = postWithBody(
                "{\"names\":[\"Portal\"]}", "application/json; charset=utf-8", TOKEN);
        endpoint.handle(ex);

        assertThat(capturedStatus(ex)).isEqualTo(200);
    }

    @Test
    void handle_returns400WhenNamesFieldIsMissing() throws Exception {
        HttpExchange ex = postWithBody("{}", "application/json", TOKEN);
        endpoint.handle(ex);

        assertThat(capturedStatus(ex)).isEqualTo(400);
        assertThat(capturedJson(ex).get("message").getAsString()).contains("names");
    }

    @Test
    void handle_returns400WhenNamesIsEmpty() throws Exception {
        HttpExchange ex = postWithBody("{\"names\":[]}", "application/json", TOKEN);
        endpoint.handle(ex);

        assertThat(capturedStatus(ex)).isEqualTo(400);
    }

    @Test
    void handle_returns400ForNonPostMethod() throws Exception {
        HttpExchange ex = exchangeWithMethod("GET", TOKEN, "{\"names\":[\"Portal\"]}",
                "application/json");
        endpoint.handle(ex);

        assertThat(capturedStatus(ex)).isEqualTo(400);
    }

    @Test
    void handle_returns500WhenServiceThrowsUnexpectedRuntime() throws Exception {
        when(ingestService.ingest(anyList(), any()))
                .thenThrow(new RuntimeException("firestore down"));

        HttpExchange ex = postWithBody("{\"names\":[\"Portal\"]}", "application/json", TOKEN);
        endpoint.handle(ex);

        assertThat(capturedStatus(ex)).isEqualTo(500);
        JsonObject body = capturedJson(ex);
        assertThat(body.get("code").getAsString()).isEqualTo("internal_error");
    }

    @Test
    void handle_returns401WhenAdminTokenIsNotConfigured() throws Exception {
        // Fail closed: a blank admin token must reject every
        // request, matching HttpAuth.requireBearer's contract.
        AdminIngestGamesEndpoint unconfigured = new AdminIngestGamesEndpoint(
                "", ingestService, GSON);
        HttpExchange ex = postWithBody("{\"names\":[\"Portal\"]}", "application/json", TOKEN);
        unconfigured.handle(ex);

        assertThat(capturedStatus(ex)).isEqualTo(401);
    }

    @Test
    void handle_passesNamesListVerbatimToService() throws Exception {
        when(ingestService.ingest(anyList(), any())).thenReturn(emptyOutcome());

        HttpExchange ex = postWithBody(
                "{\"names\":[\"Portal\",\"Hades\"]}", "application/json", TOKEN);
        endpoint.handle(ex);

        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(ingestService).ingest(captor.capture(), any());
        assertThat(captor.getValue()).containsExactly("Portal", "Hades");
    }

    private static IngestOutcome emptyOutcome() {
        return new IngestOutcome(List.of(), List.of());
    }

    private static HttpExchange postWithBody(String body, String contentType, String token)
            throws IOException {
        return exchangeWithMethod("POST", token, body, contentType);
    }

    private static HttpExchange exchangeWithMethod(String method, String token, String body,
            String contentType) throws IOException {
        HttpExchange ex = mock(HttpExchange.class);
        Headers headers = new Headers();
        if (token != null) {
            headers.add("Authorization", "Bearer " + token);
        }
        if (contentType != null) {
            headers.add("Content-Type", contentType);
        }
        ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        when(ex.getRequestMethod()).thenReturn(method);
        when(ex.getRequestHeaders()).thenReturn(headers);
        when(ex.getResponseHeaders()).thenReturn(new Headers());
        InputStream requestBody = body == null
                ? new ByteArrayInputStream(new byte[0])
                : new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        when(ex.getRequestBody()).thenReturn(requestBody);
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
