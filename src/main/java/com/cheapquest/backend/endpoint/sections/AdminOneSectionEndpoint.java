package com.cheapquest.backend.endpoint.sections;

import com.cheapquest.backend.domain.sections.SectionName;
import com.cheapquest.backend.dto.admin.SectionsResponseDto;
import com.cheapquest.backend.endpoint.HealthEndpoint;
import com.cheapquest.backend.endpoint.HttpAuth;
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
import java.util.Optional;

/**
 * Triggers a recompute for a single section. Handler for
 * {@code POST /admin/sections/{name}} (registered with the
 * trailing-slash context so the prefix match captures
 * everything under {@code /admin/sections/}).
 *
 * <p>The slug in the URL is resolved via
 * {@link SectionName#fromSlug(String)}; an unknown slug
 * becomes HTTP 400, not 404, because the path syntax was
 * syntactically valid but did not point to a known section.
 * The recompute report is serialised through the same
 * {@link PublicSectionMapper#toAdminResponse} pipeline as
 * the all-sections endpoint, so the wire shape is
 * consistent.
 */
public final class AdminOneSectionEndpoint implements HttpHandler {

    private static final String PREFIX = "/admin/sections/";

    private final String adminToken;
    private final SectionsService sectionsService;
    private final PublicSectionMapper mapper;
    private final Gson gson;

    public AdminOneSectionEndpoint(String adminToken, SectionsService sectionsService,
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
            HttpAuth.requireBearer(ex.getRequestHeaders(), adminToken);

            String slug = SectionsPathUtils.lastSegment(ex.getRequestURI().getPath(), PREFIX);
            Optional<SectionName> name = SectionName.fromSlug(slug);
            if (name.isEmpty()) {
                throw new IllegalArgumentException("unknown section slug: " + slug);
            }

            long start = System.currentTimeMillis();
            SectionsService.Report report = sectionsService.recompute(name.get());
            long totalDurationMs = System.currentTimeMillis() - start;
            SectionsResponseDto body = mapper.toAdminResponse(List.of(report), totalDurationMs);
            writeJson(ex, 200, gson.toJson(body));
        } catch (Throwable t) {
            HealthEndpoint.writeError(ex, t);
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
