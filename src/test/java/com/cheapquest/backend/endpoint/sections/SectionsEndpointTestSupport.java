package com.cheapquest.backend.endpoint.sections;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import org.mockito.ArgumentCaptor;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Shared scaffolding for the four sections endpoint tests.
 * The endpoint handlers are tested through a mocked
 * {@link HttpExchange}: the request method, headers, URI
 * and body are stubbed; the response body is captured via a
 * real {@link ByteArrayOutputStream} so {@link #bodyOf}
 * returns what the endpoint wrote.
 */
final class SectionsEndpointTestSupport {

    private SectionsEndpointTestSupport() {
    }

    static final String TOKEN = "test-admin-token";
    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    static HttpExchange exchange(String method, String path, String query,
            String authHeader, String body) {
        HttpExchange ex = mock(HttpExchange.class);
        Headers headers = new Headers();
        if (authHeader != null) {
            headers.add("Authorization", authHeader);
        }
        ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        InputStream requestBody = new ByteArrayInputStream(
                body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
        try {
            when(ex.getRequestURI()).thenReturn(query == null
                    ? new URI(path)
                    : new URI(path + "?" + query));
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
        when(ex.getRequestMethod()).thenReturn(method);
        when(ex.getRequestHeaders()).thenReturn(headers);
        when(ex.getResponseHeaders()).thenReturn(new Headers());
        when(ex.getRequestBody()).thenReturn(requestBody);
        when(ex.getResponseBody()).thenReturn(responseBody);
        return ex;
    }

    static int statusOf(HttpExchange ex) throws IOException {
        ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
        org.mockito.Mockito.verify(ex).sendResponseHeaders(
                captor.capture(), org.mockito.ArgumentMatchers.anyLong());
        return captor.getValue();
    }

    static String bodyOf(HttpExchange ex) throws IOException {
        return ((ByteArrayOutputStream) ex.getResponseBody()).toString(StandardCharsets.UTF_8);
    }

    static String authHeader() {
        return "Bearer " + TOKEN;
    }
}
