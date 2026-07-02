package com.cheapquest.backend.endpoint;

import static org.assertj.core.api.Assertions.assertThat;

import com.cheapquest.backend.exception.ApiUnavailableException;
import com.cheapquest.backend.exception.ConflictException;
import com.cheapquest.backend.exception.FirebaseUnavailableException;
import com.cheapquest.backend.exception.GameNotFoundException;
import com.cheapquest.backend.exception.GlobalExceptionHandler;
import com.cheapquest.backend.exception.GlobalExceptionHandler.Mapped;
import com.cheapquest.backend.exception.UnauthorizedException;
import org.junit.jupiter.api.Test;

class GlobalExceptionHandlerTest {

    @Test
    void unauthorized_maps_to_401() {
        Mapped mapped = GlobalExceptionHandler.handle(new UnauthorizedException("nope"));
        assertThat(mapped.status()).isEqualTo(401);
        assertThat(mapped.body().code()).isEqualTo("unauthorized");
        assertThat(mapped.body().message()).isEqualTo("nope");
        assertThat(mapped.body().timestamp()).isNotNull();
    }

    @Test
    void conflict_maps_to_409() {
        Mapped mapped = GlobalExceptionHandler.handle(new ConflictException("already running"));
        assertThat(mapped.status()).isEqualTo(409);
        assertThat(mapped.body().code()).isEqualTo("conflict");
    }

    @Test
    void game_not_found_maps_to_404() {
        Mapped mapped = GlobalExceptionHandler.handle(new GameNotFoundException("missing"));
        assertThat(mapped.status()).isEqualTo(404);
        assertThat(mapped.body().code()).isEqualTo("not_found");
    }

    @Test
    void illegal_argument_maps_to_400() {
        Mapped mapped = GlobalExceptionHandler.handle(new IllegalArgumentException("bad"));
        assertThat(mapped.status()).isEqualTo(400);
        assertThat(mapped.body().code()).isEqualTo("bad_request");
    }

    @Test
    void firebase_unavailable_maps_to_500_with_generic_message() {
        // Internal errors must not leak SDK internals on the wire.
        Mapped mapped = GlobalExceptionHandler.handle(
                new FirebaseUnavailableException("FIRESTORE_INTERNAL: gRPC stream broken"));
        assertThat(mapped.status()).isEqualTo(500);
        assertThat(mapped.body().code()).isEqualTo("internal_error");
        assertThat(mapped.body().message()).isEqualTo("internal error");
    }

    @Test
    void api_unavailable_maps_to_500() {
        Mapped mapped = GlobalExceptionHandler.handle(
                new ApiUnavailableException("RAWG 503"));
        assertThat(mapped.status()).isEqualTo(500);
        assertThat(mapped.body().code()).isEqualTo("internal_error");
    }

    @Test
    void unknown_runtime_maps_to_500() {
        Mapped mapped = GlobalExceptionHandler.handle(new RuntimeException("kaboom"));
        assertThat(mapped.status()).isEqualTo(500);
        assertThat(mapped.body().code()).isEqualTo("internal_error");
    }

    @Test
    void null_message_is_tolerated() {
        Mapped mapped = GlobalExceptionHandler.handle(new IllegalArgumentException());
        assertThat(mapped.status()).isEqualTo(400);
    }
}
