package com.cheapquest.backend.dto.rawg;

public record RawgRatingDto(
        int id,
        String title,
        int count,
        double percent) {
}
