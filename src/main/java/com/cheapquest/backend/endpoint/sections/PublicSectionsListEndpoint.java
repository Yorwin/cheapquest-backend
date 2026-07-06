package com.cheapquest.backend.endpoint.sections;

import com.cheapquest.backend.domain.sections.SectionName;
import com.cheapquest.backend.domain.sections.SectionSnapshot;
import com.cheapquest.backend.dto.public_.PublicSectionListDto;
import com.cheapquest.backend.endpoint.HealthEndpoint;
import com.cheapquest.backend.mapper.PublicSectionMapper;
import com.cheapquest.backend.service.sections.SectionStore;
import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/**
 * Lists the latest snapshot of every section. Handler for
 * {@code GET /sections} (exact match). Always returns 200
 * with a JSON body; sections that have never been computed
 * are simply absent from the {@code sections} array.
 *
 * <p>The order is the canonical {@link SectionName} order,
 * not the iteration order of the underlying Firestore
 * query, because the store returns an {@code EnumMap} that
 * preserves enum order.
 */
public final class PublicSectionsListEndpoint implements HttpHandler {

    private final SectionStore store;
    private final PublicSectionMapper mapper;
    private final Gson gson;

    public PublicSectionsListEndpoint(SectionStore store, PublicSectionMapper mapper, Gson gson) {
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

            Map<SectionName, SectionSnapshot> latest = store.readAllLatest();
            PublicSectionListDto body = mapper.toPublicList(latest);
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
