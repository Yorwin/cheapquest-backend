package com.cheapquest.backend.domain.validation;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

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
     * Outcome of {@link #parseAll(Iterable)}: the recognised fields
     * plus the names that did not map to any enum value. Unknown
     * names are returned (not thrown) so callers can decide
     * whether to log, surface, or ignore them. An older schema
     * version may legitimately contain a renamed value; flagging
     * it as a hard error would break a hydration that is
     * otherwise valid.
     */
    public record ParseResult(Set<GameField> fields, Set<String> unknown) {
        public static final ParseResult EMPTY = new ParseResult(Set.of(), Set.of());

        public ParseResult {
            fields = Set.copyOf(fields);
            unknown = Set.copyOf(unknown);
        }
    }

    /**
     * Convert a list of stored field names (the shape persisted in
     * {@code validationReport.missingFields}) back to a set of
     * {@link GameField}. {@code null} entries and unknown names
     * are tolerated; the former are skipped, the latter are
     * reported in {@link ParseResult#unknown()} so the caller can
     * log a schema-migration warning.
     */
    public static ParseResult parseAll(Iterable<String> names) {
        if (names == null) {
            return ParseResult.EMPTY;
        }
        EnumSet<GameField> fields = EnumSet.noneOf(GameField.class);
        Set<String> unknown = new LinkedHashSet<>();
        for (String name : names) {
            if (name == null) {
                continue;
            }
            try {
                fields.add(GameField.valueOf(name));
            } catch (IllegalArgumentException e) {
                unknown.add(name);
            }
        }
        return new ParseResult(fields, unknown);
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
