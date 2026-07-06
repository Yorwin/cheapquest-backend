package com.cheapquest.backend.domain.rawg;

public record RawgStoreRef(
        int id,
        String name,
        String slug,
        String domain,
        int gamesCount,
        String imageBackground) {
}
