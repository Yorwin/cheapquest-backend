package com.cheapquest.backend.dto.rawg;

import com.google.gson.annotations.SerializedName;

public record RawgPublisherDto(
        int id,
        String name,
        String slug,
        @SerializedName("games_count") int gamesCount,
        @SerializedName("image_background") String imageBackground) {
}
