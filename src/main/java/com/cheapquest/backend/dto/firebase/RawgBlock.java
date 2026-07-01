package com.cheapquest.backend.dto.firebase;

import java.util.Map;

/**
 * The {@code rawg} sub-object of a game document. {@code data} is the
 * rawg details payload kept as a generic map (the RAWG DTO has 25+ fields
 * and the frontend may need any of them; mirroring them all into a typed
 * record would just be a maintenance burden for no current benefit).
 */
public record RawgBlock(
        Boolean synced,
        String fetchedAt,
        Map<String, Object> data) {

    public static RawgBlock empty() {
        return new RawgBlock(false, null, null);
    }
}
