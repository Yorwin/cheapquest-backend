package com.cheapquest.backend.endpoint;

import com.cheapquest.backend.dto.admin.IngestGamesRequestDto;
import com.cheapquest.backend.dto.admin.IngestGamesResponseDto;
import com.cheapquest.backend.dto.admin.IngestGamesResponseDto.IngestFailure;
import com.cheapquest.backend.dto.admin.IngestGamesResponseDto.IngestResult;
import com.cheapquest.backend.service.GameIngestService;
import com.cheapquest.backend.service.GameIngestService.IngestItem;
import com.cheapquest.backend.service.GameIngestService.IngestOutcome;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Trigger endpoint at {@code POST /admin/games}. Requires a
 * bearer token that matches the configured
 * {@code admin.refresh.token} (see {@link HttpAuth}). Ingests
 * a batch of game titles into the hydration pipeline: each
 * title is normalised, slugified, written to
 * {@code games/{slug}} (atomic, idempotent) and enqueued at
 * {@code pending/{slug}} for the next hydration run.
 *
 * <p>Request body:
 * <pre>
 * { "names": ["Stardew Valley", "Hades"], "language": "en" }
 * </pre>
 * {@code language} is optional and defaults to {@code "en"};
 * the only supported value is {@code "en"}.
 *
 * <p>On success returns
 * {@code {"status":"completed","accepted":[…],"failed":[…]}}
 * with HTTP 200, where each entry of {@code accepted} carries
 * a {@code slug} and an {@code action} of
 * {@code "CREATED"} or {@code "ALREADY_EXISTED"}. A
 * well-formed request always returns 200 even if every name
 * failed; inspect {@code failed} for per-item reasons.
 *
 * <p>Errors:
 * <ul>
 *   <li>400 — empty body, non-JSON, missing/empty
 *       {@code names}, non-{@code "en"} {@code language},
 *       batch size above {@link GameIngestService#MAX_BATCH_SIZE},
 *       non-{@code POST} method.</li>
 *   <li>401 — missing/bad bearer token.</li>
 *   <li>500 — any other error (Firestore unavailable, etc.).</li>
 * </ul>
 *
 * <p><b>Idempotency</b>: re-submitting the same batch is safe
 * and cheap. Names whose doc was already in {@code games/{slug}}
 * come back as {@code ALREADY_EXISTED}, and the
 * {@code addToPending} call is a no-op when a pending entry
 * already exists for the slug.
 */
public final class AdminIngestGamesEndpoint implements HttpHandler {

    private final String adminToken;
    private final GameIngestService ingestService;
    private final Gson gson;

    public AdminIngestGamesEndpoint(String adminToken,
            GameIngestService ingestService, Gson gson) {
        this.adminToken = Objects.requireNonNull(adminToken, "adminToken");
        this.ingestService = Objects.requireNonNull(ingestService, "ingestService");
        this.gson = Objects.requireNonNull(gson, "gson");
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                throw new IllegalArgumentException("method not allowed: " + ex.getRequestMethod());
            }
            HttpAuth.requireBearer(ex.getRequestHeaders(), adminToken);
            requireJsonContentType(ex);

            IngestGamesRequestDto request = parseRequest(ex);
            if (request.names() == null || request.names().isEmpty()) {
                throw new IllegalArgumentException("names must be a non-empty list");
            }

            IngestOutcome outcome = ingestService.ingest(request.names(), request.language());
            IngestGamesResponseDto body = IngestGamesResponseDto.of(
                    outcome.accepted().stream().map(this::toResult).toList(),
                    outcome.failed().stream().map(this::toFailure).toList());

            writeJson(ex, 200, body);
        } catch (Throwable t) {
            HealthEndpoint.writeError(ex, t);
        }
    }

    private IngestResult toResult(IngestItem item) {
        return new IngestResult(item.name(), item.slug(), item.action().name());
    }

    private IngestFailure toFailure(GameIngestService.IngestFailure f) {
        return new IngestFailure(f.name(), f.error());
    }

    private void requireJsonContentType(HttpExchange ex) {
        String contentType = ex.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase().contains("application/json")) {
            throw new IllegalArgumentException("Content-Type must be application/json");
        }
    }

    private IngestGamesRequestDto parseRequest(HttpExchange ex) throws IOException {
        byte[] bytes;
        try (InputStream in = ex.getRequestBody()) {
            bytes = in.readAllBytes();
        }
        if (bytes.length == 0) {
            throw new IllegalArgumentException("request body is required");
        }
        IngestGamesRequestDto parsed;
        try {
            parsed = gson.fromJson(
                    new String(bytes, StandardCharsets.UTF_8),
                    IngestGamesRequestDto.class);
        } catch (JsonSyntaxException e) {
            // Malformed JSON: surface as a 400 (bad_request) via
            // the existing IllegalArgumentException mapping,
            // not a 500. The on-the-wire message intentionally
            // hides the parser detail; the stack is logged by
            // GlobalExceptionHandler.
            throw new IllegalArgumentException("request body is not valid JSON");
        }
        if (parsed == null) {
            throw new IllegalArgumentException("request body is not a JSON object");
        }
        return parsed;
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
