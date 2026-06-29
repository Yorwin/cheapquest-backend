package com.cheapquest.backend.dto.rawg;

public record RawgStoreEntryDto(
        long id,
        String url,
        RawgStoreRefDto store) {
}
