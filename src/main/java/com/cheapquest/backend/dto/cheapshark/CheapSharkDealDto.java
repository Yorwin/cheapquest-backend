package com.cheapquest.backend.dto.cheapshark;

import com.google.gson.annotations.SerializedName;

public record CheapSharkDealDto(
        @SerializedName("storeID") String storeId,
        @SerializedName("dealID") String dealId,
        String price,
        String retailPrice,
        String savings) {
}
