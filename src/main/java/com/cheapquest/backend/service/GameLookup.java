package com.cheapquest.backend.service;

import com.cheapquest.backend.domain.AggregatedGame;
import com.cheapquest.backend.domain.GameDeals;

/**
 * Orchestrates the lookup of a single game across every registered
 * source. Each source is called independently; a per-source failure
 * (game not found, transient API error) does not abort the others.
 * The returned {@link GameLookupResult} carries whichever payloads
 * succeeded, with the failing slots left as null. This is the
 * "two sources in parallel" semantics the hydration pipeline has
 * used since the start - adding a third or fourth source means
 * wrapping it here without growing any caller's argument list.
 */
public interface GameLookup {

    GameLookupResult lookupByTitle(String title);

    record GameLookupResult(GameDeals deals, AggregatedGame rawgAgg) {

        public static GameLookupResult empty() {
            return new GameLookupResult(null, null);
        }

        public boolean isEmpty() {
            return deals == null && rawgAgg == null;
        }
    }
}
