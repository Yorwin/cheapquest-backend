package com.cheapquest.backend.dto.firebase;

/**
 * Per-locale sub-object. For now only carries the sync flag and the
 * timestamp of the last successful update; the translatable payload
 * (description, tagNames, genreNames, reviews) will be added when
 * {@code TranslationService} lands.
 */
public record LocaleBlock(
        Boolean synced,
        String updatedAt) {

    public static LocaleBlock unsynced() {
        return new LocaleBlock(false, null);
    }
}
