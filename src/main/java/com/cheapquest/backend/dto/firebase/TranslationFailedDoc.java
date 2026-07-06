package com.cheapquest.backend.dto.firebase;

import java.time.Instant;

/**
 * DLQ entry for translations that exhausted their attempt
 * budget. Same shape as {@link TranslationPendingDoc} plus
 * {@code firstAttemptAt} so the operator can spot chronic
 * failures (a doc that has been in the DLQ for weeks) without
 * re-counting attempts from the current entry alone.
 */
public record TranslationFailedDoc(
        String slug,
        String locale,
        int attempts,
        Instant firstAttemptAt,
        Instant lastAttemptAt,
        String lastError) {
}
