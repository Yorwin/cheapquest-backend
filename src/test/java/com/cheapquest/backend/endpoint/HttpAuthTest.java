package com.cheapquest.backend.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HttpAuthTest {

    @Test
    void rejects_when_configured_token_is_null() {
        HttpExchange ex = mock(HttpExchange.class);
        Headers headers = new Headers();
        headers.add("Authorization", "Bearer anything");
        when(ex.getRequestHeaders()).thenReturn(headers);

        try {
            HttpAuth.requireBearer(headers, null);
        } catch (Exception e) {
            assertThat(e).isInstanceOf(com.cheapquest.backend.exception.UnauthorizedException.class);
            assertThat(e.getMessage()).contains("not configured");
            return;
        }
        throw new AssertionError("expected UnauthorizedException");
    }

    @Test
    void rejects_when_configured_token_is_blank() {
        Headers headers = new Headers();
        try {
            HttpAuth.requireBearer(headers, "   ");
        } catch (Exception e) {
            assertThat(e).isInstanceOf(com.cheapquest.backend.exception.UnauthorizedException.class);
            return;
        }
        throw new AssertionError("expected UnauthorizedException");
    }

    @Test
    void rejects_when_header_missing() {
        Headers headers = new Headers();
        try {
            HttpAuth.requireBearer(headers, "secret");
        } catch (Exception e) {
            assertThat(e).isInstanceOf(com.cheapquest.backend.exception.UnauthorizedException.class);
            assertThat(e.getMessage()).contains("missing");
            return;
        }
        throw new AssertionError("expected UnauthorizedException");
    }

    @Test
    void rejects_when_header_malformed() {
        Headers headers = new Headers();
        headers.add("Authorization", "Basic abcdef");
        try {
            HttpAuth.requireBearer(headers, "secret");
        } catch (Exception e) {
            assertThat(e).isInstanceOf(com.cheapquest.backend.exception.UnauthorizedException.class);
            assertThat(e.getMessage()).contains("malformed");
            return;
        }
        throw new AssertionError("expected UnauthorizedException");
    }

    @Test
    void rejects_when_token_empty() {
        Headers headers = new Headers();
        headers.add("Authorization", "Bearer    ");
        try {
            HttpAuth.requireBearer(headers, "secret");
        } catch (Exception e) {
            assertThat(e).isInstanceOf(com.cheapquest.backend.exception.UnauthorizedException.class);
            assertThat(e.getMessage()).contains("empty");
            return;
        }
        throw new AssertionError("expected UnauthorizedException");
    }

    @Test
    void rejects_when_token_mismatches() {
        Headers headers = new Headers();
        headers.add("Authorization", "Bearer wrong-token");
        try {
            HttpAuth.requireBearer(headers, "right-token");
        } catch (Exception e) {
            assertThat(e).isInstanceOf(com.cheapquest.backend.exception.UnauthorizedException.class);
            assertThat(e.getMessage()).contains("invalid");
            return;
        }
        throw new AssertionError("expected UnauthorizedException");
    }

    @Test
    void accepts_when_token_matches() {
        Headers headers = new Headers();
        headers.add("Authorization", "Bearer correct-token");
        String returned = HttpAuth.requireBearer(headers, "correct-token");
        assertThat(returned).isEqualTo("correct-token");
    }

    @Test
    void rejects_when_token_differs_only_by_length() {
        // Constant-time guard: a length mismatch must not throw
        // an early-out that reveals the timing.
        Headers headers = new Headers();
        headers.add("Authorization", "Bearer short");
        try {
            HttpAuth.requireBearer(headers, "a-much-longer-token");
        } catch (Exception e) {
            assertThat(e).isInstanceOf(com.cheapquest.backend.exception.UnauthorizedException.class);
            return;
        }
        throw new AssertionError("expected UnauthorizedException");
    }
}
