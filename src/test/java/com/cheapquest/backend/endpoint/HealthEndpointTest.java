package com.cheapquest.backend.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cheapquest.backend.exception.GlobalExceptionHandler;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HealthEndpointTest {

    @Test
    void returns_200_with_status_and_uptime_on_get() throws IOException {
        HttpExchange ex = mock(HttpExchange.class);
        Headers headers = new Headers();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        when(ex.getRequestMethod()).thenReturn("GET");
        when(ex.getRequestHeaders()).thenReturn(headers);
        when(ex.getResponseHeaders()).thenReturn(new Headers());
        when(ex.getResponseBody()).thenReturn(body);

        new HealthEndpoint(Clock.systemUTC()).handle(ex);

        String response = body.toString(StandardCharsets.UTF_8);
        assertThat(response).startsWith("{\"status\":\"ok\",\"uptimeSeconds\":");
        org.mockito.Mockito.verify(ex).sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void returns_400_on_non_get() throws IOException {
        HttpExchange ex = mock(HttpExchange.class);
        Headers headers = new Headers();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        when(ex.getRequestMethod()).thenReturn("POST");
        when(ex.getRequestHeaders()).thenReturn(headers);
        when(ex.getResponseHeaders()).thenReturn(new Headers());
        when(ex.getResponseBody()).thenReturn(body);

        new HealthEndpoint(Clock.systemUTC()).handle(ex);

        ArgumentCaptor<Integer> statusCaptor = ArgumentCaptor.forClass(Integer.class);
        org.mockito.Mockito.verify(ex).sendResponseHeaders(statusCaptor.capture(), org.mockito.ArgumentMatchers.anyLong());
        assertThat(statusCaptor.getValue()).isEqualTo(GlobalExceptionHandler.SC_BAD_REQUEST);
    }
}
