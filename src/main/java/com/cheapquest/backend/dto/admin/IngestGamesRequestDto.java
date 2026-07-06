package com.cheapquest.backend.dto.admin;

import java.util.List;

/**
 * Request body for {@code POST /admin/games}.
 *
 * <p>{@code names} is the list of game titles to ingest; it is
 * required (an empty list or {@code null} is a 400). Titles
 * must be in English because RAWG and CheapShark are queried
 * by name and the pipeline uses the title as the search key.
 *
 * <p>{@code language} is optional and defaults to {@code "en"}.
 * Any other value is rejected with 400: providing a non-English
 * title by accident would silently miss every lookup and
 * end up in {@code /games/failed} on the first hydration.
 */
public record IngestGamesRequestDto(List<String> names, String language) {
}
