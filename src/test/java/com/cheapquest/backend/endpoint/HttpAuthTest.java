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

    // --- X-Admin-Token header tests ---

    @Test
    void accepts_xAdminToken_when_matches() {
        Headers headers = new Headers();
        headers.add("X-Admin-Token", "my-secret");
        String returned = HttpAuth.requireBearer(headers, "my-secret");
        assertThat(returned).isEqualTo("my-secret");
    }

    @Test
    void xAdminToken_takes_priority_over_authorization() {
        // X-Admin-Token matches, Authorization has wrong token
        Headers headers = new Headers();
        headers.add("Authorization", "Bearer wrong-token");
        headers.add("X-Admin-Token", "correct-token");
        String returned = HttpAuth.requireBearer(headers, "correct-token");
        assertThat(returned).isEqualTo("correct-token");
    }

    @Test
    void rejects_when_xAdminToken_mismatches() {
        Headers headers = new Headers();
        headers.add("X-Admin-Token", "wrong");
        try {
            HttpAuth.requireBearer(headers, "expected-token");
        } catch (Exception e) {
            assertThat(e).isInstanceOf(com.cheapquest.backend.exception.UnauthorizedException.class);
            assertThat(e.getMessage()).contains("invalid admin token");
            return;
        }
        throw new AssertionError("expected UnauthorizedException");
    }

    @Test
    void ignores_empty_xAdminToken_and_fallsBack() {
        Headers headers = new Headers();
        headers.add("X-Admin-Token", "");
        headers.add("Authorization", "Bearer fallback-token");
        String returned = HttpAuth.requireBearer(headers, "fallback-token");
        assertThat(returned).isEqualTo("fallback-token");
    }

    @Test
    void ignores_blank_xAdminToken_and_fallsBack() {
        Headers headers = new Headers();
        headers.add("X-Admin-Token", "   ");
        headers.add("Authorization", "Bearer blank-xadmin-fallback");
        String returned = HttpAuth.requireBearer(headers, "blank-xadmin-fallback");
        assertThat(returned).isEqualTo("blank-xadmin-fallback");
    }

    @Test
    void xAdminToken_rejects_when_differs_only_by_length() {
        Headers headers = new Headers();
        headers.add("X-Admin-Token", "short");
        try {
            HttpAuth.requireBearer(headers, "a-much-longer-token");
        } catch (Exception e) {
            assertThat(e).isInstanceOf(com.cheapquest.backend.exception.UnauthorizedException.class);
            return;
        }
        throw new AssertionError("expected UnauthorizedException");
    }
}
