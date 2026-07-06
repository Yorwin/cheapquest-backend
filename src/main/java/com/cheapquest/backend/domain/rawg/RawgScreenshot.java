package com.cheapquest.backend.domain.rawg;

public record RawgScreenshot(
        long id,
        String image,
        int width,
        int height,
        boolean isDeleted) {
}
