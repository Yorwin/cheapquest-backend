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
 * <p>The {@code cheapshark} and {@code rawg} fields are
 * {@code null} when the per-source cadence (see
 * {@code RefreshPolicy}) decided the source is fresh: the
 * patch then leaves the corresponding Firestore field alone
 * via the partial-update semantics of
 * {@code DocumentReference.update(Map)}. This is the critical
 * bit that prevents a partial refresh from clobbering the
 * fresh source's existing data with an empty block.
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
        if (cheapshark != null) {
            out.put("cheapshark", cheapshark);
        }
        if (rawg != null) {
            out.put("rawg", rawg);
        }
        out.put("locales", locales);
        out.put("validationReport", validationReport);
        return out;
    }
}
