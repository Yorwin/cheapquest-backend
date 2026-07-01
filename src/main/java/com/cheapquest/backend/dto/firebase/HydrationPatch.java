package com.cheapquest.backend.dto.firebase;

import java.util.HashMap;
import java.util.Map;

/**
 * The partial Firestore document written on every hydration pass.
 * Carries only the fields that change: the canonical title, the
 * cheapshark / rawg / locales payloads and the latest
 * {@link ValidationReportDto}. The {@code slug}, {@code addedAt}
 * and {@code active} flags set at bootstrap are never included
 * here and never rewritten.
 *
 * <p>This is the typed counterpart of what used to be a
 * {@code Map<String, Object>} flowing between the mapper and the
 * client: a HydrationPatch is the value the mapper hands back,
 * the value the service passes to {@code FirebaseClient.update},
 * and the only place in the codebase that knows the
 * {@code Map<String, Object>} shape the Firestore SDK wants.
 */
public record HydrationPatch(
        String title,
        CheapsharkBlock cheapshark,
        RawgBlock rawg,
        Map<String, LocaleBlock> locales,
        ValidationReportDto validationReport) {

    public Map<String, Object> toFirestoreMap() {
        Map<String, Object> out = new HashMap<>();
        out.put("title", title);
        out.put("cheapshark", cheapshark);
        out.put("rawg", rawg);
        out.put("locales", locales);
        out.put("validationReport", validationReport);
        return out;
    }
}
