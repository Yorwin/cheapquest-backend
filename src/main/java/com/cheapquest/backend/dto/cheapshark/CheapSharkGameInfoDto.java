package com.cheapquest.backend.dto.cheapshark;

import com.google.gson.annotations.SerializedName;

public record CheapSharkGameInfoDto(
        String title,
        @SerializedName("steamAppID") String steamAppId,
        String thumb) {
}
