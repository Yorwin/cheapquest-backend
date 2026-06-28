package com.cheapquest.backend.service;

import com.cheapquest.backend.client.CheapSharkClient;
import com.cheapquest.backend.domain.GameDeals;
import com.cheapquest.backend.dto.cheapshark.CheapSharkGameDetailDto;
import com.cheapquest.backend.dto.cheapshark.CheapSharkGameSummaryDto;
import com.cheapquest.backend.dto.cheapshark.CheapSharkStoreDto;
import com.cheapquest.backend.exception.GameNotFoundException;
import com.cheapquest.backend.mapper.CheapSharkMapper;
import com.cheapquest.backend.mapper.StoreInfo;
import java.util.List;
import java.util.Map;

public final class GameAggregationService {

    private final CheapSharkClient client;
    private final CheapSharkMapper mapper;
    private final Map<String, StoreInfo> storeInfo;

    public GameAggregationService(
            CheapSharkClient client,
            CheapSharkMapper mapper,
            List<CheapSharkStoreDto> stores) {
        this.client = client;
        this.mapper = mapper;
        this.storeInfo = mapper.toStoreIdToInfo(stores);
    }

    public GameDeals aggregateByName(String name) {
        List<CheapSharkGameSummaryDto> matches = client.findByTitle(name);
        CheapSharkGameSummaryDto match = mapper.pickExactMatch(matches, name)
                .orElseThrow(() -> new GameNotFoundException(
                        "no exact match for \"" + name + "\" (got " + matches.size() + " candidates)"));

        CheapSharkGameDetailDto detail = client.getDetails(match.gameId())
                .orElseThrow(() -> new GameNotFoundException(
                        "no detail for gameId=" + match.gameId()));

        return mapper.toGameDeals(match, detail, storeInfo, name);
    }
}
