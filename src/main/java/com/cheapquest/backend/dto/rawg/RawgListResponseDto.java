package com.cheapquest.backend.dto.rawg;

import java.util.List;

public record RawgListResponseDto<T>(
        int count,
        String next,
        String previous,
        List<T> results) {
}
