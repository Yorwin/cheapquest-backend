package com.cheapquest.backend.dto.cheapshark;

import com.google.gson.annotations.SerializedName;

public record CheapSharkGameSummaryDto(
        @SerializedName("gameID") String gameId,
        @SerializedName("steamAppID") String steamAppId,
        String cheapest,
        @SerializedName("cheapestDealID") String cheapestDealId,
        String external,
        String internalName,
        String thumb) {
}
