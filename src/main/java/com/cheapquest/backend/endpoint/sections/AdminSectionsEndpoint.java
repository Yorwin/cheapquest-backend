package com.cheapquest.backend.endpoint.sections;

import com.cheapquest.backend.dto.admin.SectionsResponseDto;
import com.cheapquest.backend.exception.GlobalExceptionHandler;
import com.cheapquest.backend.mapper.PublicSectionMapper;
import com.cheapquest.backend.service.sections.SectionsService;
import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Triggers a full section recompute. Handler for
 * {@code POST /admin/sections} (exact match). The body is
 * ignored: this endpoint always recomputes all 5 sections.
 * Auth: same {@code Authorization: Bearer <token>} contract
 * as the other admin endpoints.
 *
 * <p>The response is a {@link SectionsResponseDto} with one
 * per-section summary plus top-level processed / failed
 * counters and a total duration. Status code is always
 * 200: a section that errored during recompute is reported
 * as {@code FAILED} in the summary, not as a 5xx. The only
 * 4xx / 5xx responses are 401 (auth), 405 (wrong method) and
 * 409 (a recompute is already in progress).
 */
public final class AdminSectionsEndpoint implements HttpHandler {

    private final String adminToken;
    private final SectionsService sectionsService;
    private final PublicSectionMapper mapper;
    private final Gson gson;

    public AdminSectionsEndpoint(String adminToken, SectionsService sectionsService,
            PublicSectionMapper mapper, Gson gson) {
        this.adminToken = Objects.requireNonNull(adminToken, "adminToken");
        this.sectionsService = Objects.requireNonNull(sectionsService, "sectionsService");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.gson = Objects.requireNonNull(gson, "gson");
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                throw new IllegalArgumentException(
                        "method not allowed: " + ex.getRequestMethod());
            }
            com.cheapquest.backend.endpoint.HttpAuth.requireBearer(
                    ex.getRequestHeaders(), adminToken);

            long start = System.currentTimeMillis();
            List<SectionsService.Report> reports = sectionsService.recomputeAll();
            long totalDurationMs = System.currentTimeMillis() - start;
            SectionsResponseDto body = mapper.toAdminResponse(reports, totalDurationMs);
            writeJson(ex, 200, gson.toJson(body));
        } catch (Throwable t) {
            com.cheapquest.backend.endpoint.HealthEndpoint.writeError(ex, t);
        }
    }

    private static void writeJson(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
