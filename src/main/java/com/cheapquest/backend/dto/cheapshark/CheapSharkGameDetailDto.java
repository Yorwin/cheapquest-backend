package com.cheapquest.backend.dto.cheapshark;

import java.util.List;

public record CheapSharkGameDetailDto(
        CheapSharkGameInfoDto info,
        CheapSharkCheapestPriceDto cheapestPriceEver,
        List<CheapSharkDealDto> deals) {
}
