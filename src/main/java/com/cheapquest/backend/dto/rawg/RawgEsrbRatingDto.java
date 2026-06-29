package com.cheapquest.backend.dto.rawg;

import com.google.gson.annotations.SerializedName;

public record RawgEsrbRatingDto(
        int id,
        String name,
        String slug,
        @SerializedName("name_en") String nameEn) {
}
