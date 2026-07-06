package com.cheapquest.backend.domain.sections;

/**
 * Domain projection of the RAWG block, narrowed to the fields
 * that the current section builders use:
 * {@code released} (release date, ISO string),
 * {@code metacritic} (0-100) and {@code rating} (0-5).
 *
 * <p>This is intentionally a v1 minimum. The "populares"
 * section will need additional fields (e.g.
 * {@code additionsCount}, {@code ratingsCount},
 * {@code added_by_status}); they will land here together with
 * the {@code RawgClient} and {@code RawgDocumentDto} changes
 * that surface them. Keeping the view small now means the
 * store does not need a placeholder field for data we do not
 * yet fetch.
 */
public record RawgView(
        String released,
        Integer metacritic,
        Double rating) {
}
