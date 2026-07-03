package com.cheapquest.backend.dto.firebase;

/**
 * Per-locale sub-object. Tracks the translation state of one locale
 * for one game document.
 *
 * <p><b>Semantics:</b> {@code synced = true} means the locale has a
 * translation that is current with the source RAWG data the
 * backend last fetched. The contract has three states:
 *
 * <ul>
 *   <li>{@code synced=false}, {@code sourceFetchedAt=null}: never
 *       translated (the initial bootstrap state).</li>
 *   <li>{@code synced=false}, {@code sourceFetchedAt=<ISO>}: the
 *       locale was translated, but the source data has been
 *       re-fetched since then ({@code rawg.fetchedAt > sourceFetchedAt}).
 *       The translation needs a re-run.</li>
 *   <li>{@code synced=true}, {@code sourceFetchedAt=<ISO>}: the
 *       locale's translation is current.</li>
 * </ul>
 *
 * <p>{@code updatedAt} is the timestamp of the most recent write
 * to this block (translation success, stale-mark, or re-sync);
 * the consistency check operator uses it to spot locales that have
 * not been touched in a long time.
 */
public record LocaleBlock(
        Boolean synced,
        String updatedAt,
        String sourceFetchedAt) {

    public static LocaleBlock unsynced() {
        return new LocaleBlock(false, null, null);
    }
}
