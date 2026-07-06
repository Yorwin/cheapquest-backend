package com.cheapquest.backend.domain.rawg;

public record RawgRating(
        int id,
        String title,
        int count,
        double percent) {
}
