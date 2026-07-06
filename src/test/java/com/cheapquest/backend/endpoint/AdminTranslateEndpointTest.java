package com.cheapquest.backend.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cheapquest.backend.exception.UnauthorizedException;
import com.cheapquest.backend.service.TranslationService;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AdminTranslateEndpointTest {

    private static final String TOKEN = "test-admin-token";

    private TranslationService translationService;
    private AdminTranslateEndpoint endpoint;

    @BeforeEach
    void setUp() {
        translationService = mock(TranslationService.class);
        endpoint = new AdminTranslateEndpoint(TOKEN, translationService);
    }

    @Test
    void returns200WithTranslationCount() throws IOException {
        HttpExchange ex = postRequest();
        when(translationService.translateAll()).thenReturn(7);

        endpoint.handle(ex);

        verify(ex).sendResponseHeaders(org.mockito.ArgumentMatchers.eq(200), org.mockito.ArgumentMatchers.anyLong());
        ByteArrayOutputStream body = (ByteArrayOutputStream) ex.getResponseBody();
        assertThat(body.toString(StandardCharsets.UTF_8))
                .isEqualTo("{\"status\":\"completed\",\"translated\":7}");
    }

    @Test
    void returns401WhenTokenMissing() throws IOException {
        HttpExchange ex = postRequest();
        ex.getRequestHeaders().remove("Authorization");

        endpoint.handle(ex);

        verify(ex).sendResponseHeaders(org.mockito.ArgumentMatchers.eq(401), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void returns401WhenTokenWrong() throws IOException {
        HttpExchange ex = postRequest();
        ex.getRequestHeaders().replace("Authorization", java.util.List.of("Bearer wrong"));

        endpoint.handle(ex);

        verify(ex).sendResponseHeaders(org.mockito.ArgumentMatchers.eq(401), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void returns401WhenEndpointNotConfigured() throws IOException {
        HttpExchange ex = postRequest();
        AdminTranslateEndpoint noToken = new AdminTranslateEndpoint(null, translationService);

        noToken.handle(ex);

        verify(ex).sendResponseHeaders(org.mockito.ArgumentMatchers.eq(401), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void returns400OnGet() throws IOException {
        HttpExchange ex = mock(HttpExchange.class);
        Headers headers = new Headers();
        headers.add("Authorization", "Bearer " + TOKEN);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        when(ex.getRequestMethod()).thenReturn("GET");
        when(ex.getRequestHeaders()).thenReturn(headers);
        when(ex.getResponseHeaders()).thenReturn(new Headers());
        when(ex.getResponseBody()).thenReturn(body);

        endpoint.handle(ex);

        verify(ex).sendResponseHeaders(org.mockito.ArgumentMatchers.eq(400), org.mockito.ArgumentMatchers.anyLong());
    }

    private HttpExchange postRequest() throws IOException {
        HttpExchange ex = mock(HttpExchange.class);
        Headers headers = new Headers();
        headers.add("Authorization", "Bearer " + TOKEN);
        ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        when(ex.getRequestMethod()).thenReturn("POST");
        when(ex.getRequestHeaders()).thenReturn(headers);
        when(ex.getResponseHeaders()).thenReturn(new Headers());
        when(ex.getResponseBody()).thenReturn(responseBody);
        when(ex.getRequestBody()).thenReturn(new ByteArrayInputStream(new byte[0]));
        return ex;
    }
}
