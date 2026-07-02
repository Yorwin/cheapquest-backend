package com.cheapquest.backend.service;

import com.cheapquest.backend.domain.AggregatedGame;
import com.cheapquest.backend.domain.GameDeals;
import com.cheapquest.backend.exception.GameNotFoundException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GameLookupService implements GameLookup {

    private static final Logger log = LoggerFactory.getLogger(GameLookupService.class);

    private final GameAggregationService csService;
    private final RawgAggregationService rawgService;

    public GameLookupService(GameAggregationService csService, RawgAggregationService rawgService) {
        this.csService = java.util.Objects.requireNonNull(csService, "csService");
        this.rawgService = java.util.Objects.requireNonNull(rawgService, "rawgService");
    }

    @Override
    public GameLookupResult lookupByTitle(String title, Set<Source> sourcesToFetch) {
        GameDeals deals = null;
        if (sourcesToFetch.contains(Source.CHEAPSHARK)) {
            deals = tryCheapShark(title);
        }
        AggregatedGame rawgAgg = null;
        if (sourcesToFetch.contains(Source.RAWG)) {
            rawgAgg = tryRawg(title);
        }
        return new GameLookupResult(deals, rawgAgg);
    }

    private GameDeals tryCheapShark(String title) {
        try {
            return csService.aggregateByName(title);
        } catch (GameNotFoundException e) {
            log.warn("lookup_cheapshark_not_found title=\"{}\": {}", title, e.getMessage());
            return null;
        } catch (RuntimeException e) {
            log.warn("lookup_cheapshark_failed title=\"{}\" err={}: {}",
                    title, e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    private AggregatedGame tryRawg(String title) {
        try {
            return rawgService.aggregate(title);
        } catch (GameNotFoundException e) {
            log.warn("lookup_rawg_not_found title=\"{}\": {}", title, e.getMessage());
            return null;
        } catch (RuntimeException e) {
            log.warn("lookup_rawg_failed title=\"{}\" err={}: {}",
                    title, e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }
}
