package com.cheapquest.backend.dto.rawg;

import com.google.gson.annotations.SerializedName;

public record RawgTagDto(
        int id,
        String name,
        String slug,
        String language,
        @SerializedName("games_count") int gamesCount,
        @SerializedName("image_background") String imageBackground) {
}
