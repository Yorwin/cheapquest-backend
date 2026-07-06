package com.cheapquest.backend.endpoint.sections;

import com.cheapquest.backend.domain.sections.SectionName;
import com.cheapquest.backend.domain.sections.SectionSnapshot;
import com.cheapquest.backend.dto.public_.PublicSectionDto;
import com.cheapquest.backend.endpoint.HealthEndpoint;
import com.cheapquest.backend.mapper.PublicSectionMapper;
import com.cheapquest.backend.service.sections.SectionStore;
import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads one section's snapshot. Handler for
 * {@code GET /sections/{name}?date=YYYY-MM-DD|live}.
 *
 * <p>Path: the trailing segment is the section slug, parsed
 * via {@link SectionName#fromSlug(String)}; an unknown slug
 * is HTTP 400.
 *
 * <p>Query: {@code date=YYYY-MM-DD} reads the per-day
 * history doc; {@code date=live} (the default when the
 * query string is absent or empty) reads the {@code latest}
 * mirror. A malformed date is HTTP 400.
 *
 * <p>A missing snapshot (the day has no doc, or the latest
 * mirror has not been written yet) is HTTP 404 with the
 * usual error envelope.
 */
public final class PublicSectionReadEndpoint implements HttpHandler {

    private static final String PREFIX = "/sections/";
    private static final String DATE_PARAM = "date";
    private static final String LIVE_VALUE = "live";

    private final SectionStore store;
    private final PublicSectionMapper mapper;
    private final Gson gson;

    public PublicSectionReadEndpoint(SectionStore store, PublicSectionMapper mapper, Gson gson) {
        this.store = Objects.requireNonNull(store, "store");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.gson = Objects.requireNonNull(gson, "gson");
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                throw new IllegalArgumentException(
                        "method not allowed: " + ex.getRequestMethod());
            }

            String slug = SectionsPathUtils.lastSegment(ex.getRequestURI().getPath(), PREFIX);
            Optional<SectionName> name = SectionName.fromSlug(slug);
            if (name.isEmpty()) {
                throw new IllegalArgumentException("unknown section slug: " + slug);
            }

            String dateParam = queryParam(ex.getRequestURI(), DATE_PARAM);
            Optional<SectionSnapshot> snap;
            if (dateParam == null || dateParam.isBlank() || LIVE_VALUE.equalsIgnoreCase(dateParam)) {
                snap = store.readLatest(name.get());
            } else {
                LocalDate date;
                try {
                    date = LocalDate.parse(dateParam);
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException(
                            "invalid date (expected YYYY-MM-DD or 'live'): " + dateParam);
                }
                snap = store.read(name.get(), date);
            }

            if (snap.isEmpty()) {
                writeNotFound(ex, name.get(), dateParam);
                return;
            }

            PublicSectionDto body = mapper.toPublic(snap.get());
            writeJson(ex, 200, gson.toJson(body));
        } catch (Throwable t) {
            HealthEndpoint.writeError(ex, t);
        }
    }

    private void writeNotFound(HttpExchange ex, SectionName name, String dateParam)
            throws IOException {
        String message = (dateParam == null || dateParam.isBlank()
                || "live".equalsIgnoreCase(dateParam))
                ? "no latest snapshot for section=" + name.slug()
                : "no snapshot for section=" + name.slug() + " on date=" + dateParam;
        // Build the JSON manually rather than going through Gson.
        // ErrorResponse carries an Instant, and the shared Gson
        // instance has no Instant type adapter (the project
        // works around this in HealthEndpoint.writeError by
        // string-building the body). Mirror that approach so
        // the 404 path does not depend on reflective access to
        // java.time fields.
        com.cheapquest.backend.endpoint.ErrorResponse errBody =
                com.cheapquest.backend.endpoint.ErrorResponse.of("not_found", message);
        String body = "{\"code\":\"" + errBody.code()
                + "\",\"message\":\"" + escape(errBody.message())
                + "\",\"timestamp\":\"" + errBody.timestamp() + "\"}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(404, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String queryParam(URI uri, String name) {
        String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = pair.substring(0, eq);
            if (key.equals(name)) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
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
