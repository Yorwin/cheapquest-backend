package com.cheapquest.backend.dto.rawg;

public record RawgMovieDto(
        int id,
        String name,
        String preview,
        RawgMovieDataDto data) {
}
