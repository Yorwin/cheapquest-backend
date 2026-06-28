package com.cheapquest.backend.dto.cheapshark;

import com.google.gson.annotations.SerializedName;

public record CheapSharkStoreDto(
        String storeName,
        int isActive,
        CheapSharkStoreImagesDto images,
        @SerializedName("storeID") String storeId) {
}
