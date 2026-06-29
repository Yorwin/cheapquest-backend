package com.cheapquest.backend.dto.rawg;

import com.google.gson.annotations.SerializedName;

public record RawgMovieDataDto(
        @SerializedName("480") String low,
        String max,
        String full) {
}
