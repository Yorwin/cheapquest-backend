package com.cheapquest.backend.dto.firebase;

/**
 * The {@code rawg} sub-object of a game document. {@code data} is a
 * typed projection of the RAWG payload, not a free-form map: a
 * rename in {@link com.cheapquest.backend.domain.rawg.RawgDetails}
 * is a compile error here, so the Firestore schema cannot drift
 * silently. See {@link RawgDocumentDto} for the field-by-field
 * shape and the rationale for the differences from
 * {@code RawgDetails} (string timestamp, boxed counts).
 */
public record RawgBlock(
        Boolean synced,
        String fetchedAt,
        RawgDocumentDto data) {

    public static RawgBlock empty() {
        return new RawgBlock(false, null, null);
    }
}
