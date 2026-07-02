package com.cheapquest.backend.dto.admin;

/**
 * Request body for {@code POST /admin/refresh}. Both fields are
 * optional. {@code language} selects the target translation locale
 * ({@code es}, {@code en}, {@code fr}); when absent, all three
 * are refreshed. {@code force} bypasses the per-source cadence
 * and re-fetches every source unconditionally. A missing or
 * malformed body is treated as a fully-default request.
 */
public record RefreshRequestDto(String language, Boolean force) {

    /**
     * Read {@link #force} with a sensible default. {@code null} is
     * treated as {@code false} so an absent or {@code "force":null}
     * body does not bypass the cadence by accident.
     */
    public boolean forceOrFalse() {
        return force != null && force;
    }
}
