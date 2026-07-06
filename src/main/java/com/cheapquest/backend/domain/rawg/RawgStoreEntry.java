package com.cheapquest.backend.domain.rawg;

public record RawgStoreEntry(
        long id,
        String url,
        RawgStoreRef store) {
}
