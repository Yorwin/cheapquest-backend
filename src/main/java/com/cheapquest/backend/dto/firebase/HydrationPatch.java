package com.cheapquest.backend.dto.firebase;

import java.util.HashMap;
import java.util.Map;

/**
 * The partial Firestore document written on every hydration pass.
 * Carries only the fields that change: the canonical title, the
 * cheapshark / rawg payloads and the latest
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
 *
 * <p><b>{@code locales} is intentionally not in the patch.</b>
 * The hydration pipeline writes the english data; the
 * corresponding {@code locales.en} flag is set to
 * {@code synced=true} by a separate partial update issued by
 * the hydration service. Translation services (e.g. DeepL) are
 * the only writers of {@code locales.es} and {@code locales.fr}.
 * Bundling the locales into this patch would silently clobber
 * a previously-saved translation on every refresh.
 */
public record HydrationPatch(
        String title,
        CheapsharkBlock cheapshark,
        RawgBlock rawg,
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
        out.put("validationReport", validationReport);
        return out;
    }
}
