package com.cheapquest.backend.endpoint;

import com.cheapquest.backend.service.TranslationService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/**
 * Trigger endpoint at {@code POST /admin/translate}. Requires a
 * bearer token that matches the configured
 * {@code admin.refresh.token} (see {@link HttpAuth}). Drains the
 * {@code translations-pending} queue: each (slug, locale) entry
 * is translated via DeepL and the result is written back into
 * the game document. Failures are recorded against the per-entry
 * attempt counter; entries that exhaust the budget move to
 * {@code translations-failed}.
 *
 * <p>On success returns
 * {@code {"status":"completed","translated":N}} with HTTP 200.
 * On a missing/bad token returns HTTP 401. Any other error
 * returns HTTP 500 with a generic body and a server-side stack
 * trace (see {@link GlobalExceptionHandler}).
 */
public final class AdminTranslateEndpoint implements HttpHandler {

    private final String adminToken;
    private final TranslationService translationService;

    public AdminTranslateEndpoint(String adminToken, TranslationService translationService) {
        this.adminToken = adminToken;
        this.translationService = Objects.requireNonNull(translationService, "translationService");
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                throw new IllegalArgumentException("method not allowed: " + ex.getRequestMethod());
            }
            HttpAuth.requireBearer(ex.getRequestHeaders(), adminToken);

            int done = translationService.translateAll();
            String body = "{\"status\":\"completed\",\"translated\":" + done + "}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        } catch (Throwable t) {
            HealthEndpoint.writeError(ex, t);
        }
    }
}
