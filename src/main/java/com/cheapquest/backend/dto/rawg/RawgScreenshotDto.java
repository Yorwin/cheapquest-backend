package com.cheapquest.backend.dto.rawg;

import com.google.gson.annotations.SerializedName;

public record RawgScreenshotDto(
        long id,
        String image,
        int width,
        int height,
        @SerializedName("is_deleted") boolean isDeleted) {
}
