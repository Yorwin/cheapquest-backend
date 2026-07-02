package com.cheapquest.backend.domain.validation;

/**
 * Single field-level deficiency that the future employee-facing UI will
 * surface so a missing piece of data can be located and re-fetched or
 * hand-edited. Each value corresponds to one refetch strategy in
 * AGENTS.md §7.
 *
 * <p>Each field belongs to exactly one {@link Source}. The mapping
 * drives the partial-refresh report composition in
 * {@code GameHydrationService.composeReport}: a CheapShark-only
 * refresh cannot mark a RAWG field as missing, and a RAWG-only
 * refresh cannot mark {@code STORES} as missing, because the
 * refreshed source has no authority to evaluate the other side.
 */
public enum GameField {
    STORES(Source.CHEAPSHARK),
    DESCRIPTION(Source.RAWG),
    HEADER_IMAGE(Source.RAWG),
    TRAILER(Source.RAWG),
    GENRES(Source.RAWG),
    TAGS(Source.RAWG),
    SCREENSHOTS(Source.RAWG),
    RELEASED(Source.RAWG),
    DEVELOPER(Source.RAWG),
    PUBLISHER(Source.RAWG),
    REVIEWS(Source.RAWG);

    private final Source source;

    GameField(Source source) {
        this.source = source;
    }

    public Source source() {
        return source;
    }

    /**
     * Which data source is the authority for this field. A refresh
     * decision that does not include a given source cannot evaluate
     * its fields; the report composer carries the previous value
     * forward instead of re-checking.
     */
    public enum Source {
        CHEAPSHARK,
        RAWG
    }
}
