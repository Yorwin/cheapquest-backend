package com.cheapquest.backend.endpoint;

import com.cheapquest.backend.dto.admin.ListGamesResponseDto;
import com.cheapquest.backend.dto.admin.ListGamesResponseDto.QueueEntryDto;
import com.cheapquest.backend.service.GameQueueService;
import com.cheapquest.backend.service.GameQueueService.QueueEntry;
import com.cheapquest.backend.service.GameQueueService.Status;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Operator endpoint at {@code GET /admin/games?status=pending|failed}.
 * Returns the contents of one of the hydration queues so the
 * operator can inspect what is in flight or stuck in the DLQ
 * without opening the Firebase console.
 *
 * <p>Example:
 * <pre>
 * GET /admin/games?status=pending
 * Authorization: Bearer ${ADMIN_REFRESH_TOKEN}
 *
 * 200 OK
 * {
 *   "status": "pending",
 *   "count": 2,
 *   "entries": [
 *     { "slug": "portal", "attempts": 1,
 *       "firstAttemptAt": null,
 *       "lastAttemptAt": "2026-07-06T10:00:00Z",
 *       "lastError": null },
 *     { "slug": "hl2",    "attempts": 2,
 *       "firstAttemptAt": null,
 *       "lastAttemptAt": "2026-07-06T10:05:00Z",
 *       "lastError": "rawg 503" }
 *   ]
 * }
 * </pre>
 *
 * <p>Errors:
 * <ul>
 *   <li>400 — missing or unknown {@code status} query
 *       parameter, non-{@code GET} method.</li>
 *   <li>401 — missing/bad bearer token.</li>
 *   <li>500 — Firestore unavailable or any other error.</li>
 * </ul>
 */
public final class AdminListGamesEndpoint implements HttpHandler {

    private static final String STATUS_PARAM = "status";
    private static final Map<String, Status> STATUS_BY_NAME = Map.of(
            "pending", Status.PENDING,
            "failed", Status.FAILED);

    private final String adminToken;
    private final GameQueueService queueService;
    private final Gson gson;

    public AdminListGamesEndpoint(String adminToken,
            GameQueueService queueService) {
        this(adminToken, queueService, new GsonBuilder().serializeNulls().create());
    }

    public AdminListGamesEndpoint(String adminToken,
            GameQueueService queueService, Gson gson) {
        this.adminToken = Objects.requireNonNull(adminToken, "adminToken");
        this.queueService = Objects.requireNonNull(queueService, "queueService");
        this.gson = Objects.requireNonNull(gson, "gson");
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                throw new IllegalArgumentException("method not allowed: " + ex.getRequestMethod());
            }
            HttpAuth.requireBearer(ex.getRequestHeaders(), adminToken);

            String raw = queryParam(ex.getRequestURI(), STATUS_PARAM);
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException(
                        "query parameter \"" + STATUS_PARAM + "\" is required (pending|failed)");
            }
            Status status = STATUS_BY_NAME.get(raw.toLowerCase());
            if (status == null) {
                throw new IllegalArgumentException(
                        "query parameter \"" + STATUS_PARAM + "\" must be one of "
                                + STATUS_BY_NAME.keySet() + ", got \"" + raw + "\"");
            }

            List<QueueEntry> entries = queueService.list(status);
            List<QueueEntryDto> dtoEntries = entries.stream()
                    .map(this::toDto)
                    .toList();
            ListGamesResponseDto body = ListGamesResponseDto.of(status.name().toLowerCase(), dtoEntries);

            writeJson(ex, 200, body);
        } catch (Throwable t) {
            HealthEndpoint.writeError(ex, t);
        }
    }

    private QueueEntryDto toDto(QueueEntry entry) {
        return new QueueEntryDto(
                entry.slug(),
                entry.attempts(),
                toIso(entry.firstAttemptAt()),
                toIso(entry.lastAttemptAt()),
                entry.lastError());
    }

    private static String toIso(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static String queryParam(URI uri, String name) {
        String query = uri.getRawQuery();
        if (query == null || query.isEmpty()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = pair.substring(0, eq);
            if (!name.equals(key)) {
                continue;
            }
            String value = pair.substring(eq + 1);
            return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
        }
        return null;
    }

    private void writeJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
