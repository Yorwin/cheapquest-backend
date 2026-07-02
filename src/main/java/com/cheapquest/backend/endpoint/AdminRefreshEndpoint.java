package com.cheapquest.backend.endpoint;

import com.cheapquest.backend.dto.admin.RefreshRequestDto;
import com.cheapquest.backend.dto.admin.RefreshResponseDto;
import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Trigger endpoint at {@code POST /admin/refresh}. Requires a
 * bearer token that matches the configured
 * {@code admin.refresh.token} (see {@link HttpAuth}). The body
 * is optional; an empty body is treated as a fully-default
 * request (no force, all languages).
 *
 * <p>On success returns
 * {@code {"status":"completed","processed":N,"failed":M,"durationMs":T}}
 * with HTTP 200. On a concurrent refresh (lock held) returns
 * HTTP 409. On a missing/bad token returns HTTP 401. Any other
 * error returns HTTP 500 with a generic body and a server-side
 * stack trace (see {@link GlobalExceptionHandler}).
 */
public final class AdminRefreshEndpoint implements HttpHandler {

    private final String adminToken;
    private final RefreshService refreshService;
    private final Gson gson;

    public AdminRefreshEndpoint(String adminToken, RefreshService refreshService, Gson gson) {
        this.adminToken = adminToken;
        this.refreshService = Objects.requireNonNull(refreshService, "refreshService");
        this.gson = Objects.requireNonNull(gson, "gson");
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                throw new IllegalArgumentException("method not allowed: " + ex.getRequestMethod());
            }
            HttpAuth.requireBearer(ex.getRequestHeaders(), adminToken);

            RefreshRequestDto request = parseRequest(ex);
            RefreshService.Outcome outcome = refreshService.refresh(request.forceOrFalse());

            RefreshResponseDto body = new RefreshResponseDto(
                    outcome.status(), outcome.processed(), outcome.failed(), outcome.durationMs());
            byte[] bytes = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        } catch (Throwable t) {
            HealthEndpoint.writeError(ex, t);
        }
    }

    private RefreshRequestDto parseRequest(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] bytes = in.readAllBytes();
            if (bytes.length == 0) {
                return new RefreshRequestDto(null, null);
            }
            RefreshRequestDto parsed = gson.fromJson(new String(bytes, StandardCharsets.UTF_8), RefreshRequestDto.class);
            return parsed == null ? new RefreshRequestDto(null, null) : parsed;
        }
    }
}
