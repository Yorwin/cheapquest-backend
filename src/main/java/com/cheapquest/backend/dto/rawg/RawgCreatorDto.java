package com.cheapquest.backend.dto.rawg;

import com.google.gson.annotations.SerializedName;

public record RawgCreatorDto(
        int id,
        String name,
        String slug,
        String image,
        @SerializedName("image_background") String imageBackground,
        String position,
        @SerializedName("games_count") int gamesCount) {
}
