package com.cheapquest.backend.dto.admin;

import java.util.List;

/**
 * Response body for {@code POST /admin/games} on a successful
 * (200) call. {@code status} is always {@code "completed"}.
 * {@code accepted} lists every name that was either created
 * or found already existing; {@code failed} lists every name
 * that could not be processed, with a short reason.
 *
 * <p>The HTTP layer always returns 200 when the request body
 * is well-formed, even if every name ended up in
 * {@code failed}: the caller is expected to inspect the
 * list. A 400 is only returned for malformed input (empty
 * body, non-JSON, missing {@code names}, wrong language).
 */
public record IngestGamesResponseDto(
        String status,
        List<IngestResult> accepted,
        List<IngestFailure> failed) {

    public record IngestResult(String name, String slug, String action) {
    }

    public record IngestFailure(String name, String error) {
    }

    public static IngestGamesResponseDto of(List<IngestResult> accepted,
            List<IngestFailure> failed) {
        return new IngestGamesResponseDto("completed", accepted, failed);
    }
}
