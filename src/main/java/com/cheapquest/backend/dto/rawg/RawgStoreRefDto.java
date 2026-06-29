package com.cheapquest.backend.dto.rawg;

import com.google.gson.annotations.SerializedName;

public record RawgStoreRefDto(
        int id,
        String name,
        String slug,
        String domain,
        @SerializedName("games_count") int gamesCount,
        @SerializedName("image_background") String imageBackground) {
}
