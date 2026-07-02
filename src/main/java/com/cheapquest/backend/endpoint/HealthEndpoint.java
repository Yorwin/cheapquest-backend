package com.cheapquest.backend.endpoint;

import com.cheapquest.backend.exception.GlobalExceptionHandler;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Objects;

/**
 * Liveness check at {@code GET /health}. No authentication: this
 * endpoint is meant to be polled by infrastructure (a load
 * balancer, a Kubernetes probe, a CI ping) and must succeed as
 * long as the JVM is up. Returns
 * {@code {"status":"ok","uptimeSeconds":N}}.
 */
public final class HealthEndpoint implements HttpHandler {

    private final long startMillis;

    public HealthEndpoint(Clock clock) {
        this.startMillis = Objects.requireNonNull(clock, "clock").millis();
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                throw new IllegalArgumentException("method not allowed: " + ex.getRequestMethod());
            }
            long uptimeSeconds = (System.currentTimeMillis() - startMillis) / 1000L;
            String body = "{\"status\":\"ok\",\"uptimeSeconds\":" + uptimeSeconds + "}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        } catch (Throwable t) {
            writeError(ex, t);
        }
    }

    static void writeError(HttpExchange ex, Throwable t) throws IOException {
        GlobalExceptionHandler.Mapped mapped = GlobalExceptionHandler.handle(t);
        String body = "{\"code\":\"" + mapped.body().code()
                + "\",\"message\":\"" + escape(mapped.body().message())
                + "\",\"timestamp\":\"" + mapped.body().timestamp() + "\"}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(mapped.status(), bytes.length);
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
}
