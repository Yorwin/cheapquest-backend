package com.cheapquest.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.cheapquest.backend.domain.validation.GameField;
import com.cheapquest.backend.dto.firebase.CheapsharkBlock;
import com.cheapquest.backend.dto.firebase.GameDocumentDto;
import com.cheapquest.backend.dto.firebase.LocaleBlock;
import com.cheapquest.backend.dto.firebase.RawgBlock;
import com.cheapquest.backend.dto.firebase.ValidationReportDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ValidationConsistencyCheckerTest {

    private static final String ADDED_AT = "2026-06-30T10:00:00Z";
    private static final String FETCHED_AT = "2026-06-30T10:05:00Z";

    private final ValidationConsistencyChecker checker = new ValidationConsistencyChecker();

    @Test
    void rejects_null_iterable() {
        assertThatNullPointerException()
                .isThrownBy(() -> checker.check(null))
                .withMessageContaining("docs");
    }

    @Test
    void consistent_complete_when_all_fields_populated() {
        GameDocumentDto doc = fullDoc("portal");
        GameDocumentDto withReport = docWithReport(doc, "COMPLETE", List.of(), FETCHED_AT, null);
        List<ValidationConsistencyChecker.Inconsistency> incs = checker.check(List.of(withReport));
        assertThat(incs).isEmpty();
    }

    @Test
    void consistent_partial_when_only_trailer_missing() {
        GameDocumentDto base = fullDoc("portal");
        GameDocumentDto noTrailer = docWithData(base, withoutKey("trailerUrl"));
        GameDocumentDto withReport = docWithReport(noTrailer, "PARTIAL", List.of("TRAILER"), FETCHED_AT, null);
        List<ValidationConsistencyChecker.Inconsistency> incs = checker.check(List.of(withReport));
        assertThat(incs).isEmpty();
    }

    @Test
    void consistent_partial_when_multiple_fields_missing() {
        GameDocumentDto base = fullDoc("portal");
        GameDocumentDto slim = docWithData(base, data -> {
            data.remove("trailerUrl");
            data.remove("description");
            data.remove("descriptionRaw");
            data.put("screenshots", List.of());
            return data;
        });
        GameDocumentDto withReport = docWithReport(slim, "PARTIAL",
                List.of("DESCRIPTION", "TRAILER", "SCREENSHOTS"), FETCHED_AT, null);
        List<ValidationConsistencyChecker.Inconsistency> incs = checker.check(List.of(withReport));
        assertThat(incs).isEmpty();
    }

    @Test
    void inconsistent_when_stored_has_bogus_fields_pre_fix_bug() {
        // The pre-fix cadence run wrote 9 RAWG fields as missing
        // when only deals was refreshed. Real data is full. Stored
        // says missing, actual is empty.
        GameDocumentDto full = fullDoc("half-life-2");
        GameDocumentDto withReport = docWithReport(full, "PARTIAL",
                List.of("DEVELOPER", "GENRES", "HEADER_IMAGE", "TRAILER",
                        "PUBLISHER", "TAGS", "RELEASED", "DESCRIPTION", "SCREENSHOTS"),
                FETCHED_AT, null);
        List<ValidationConsistencyChecker.Inconsistency> incs = checker.check(List.of(withReport));
        assertThat(incs).hasSize(1);
        ValidationConsistencyChecker.Inconsistency inc = incs.get(0);
        assertThat(inc.slug()).isEqualTo("half-life-2");
        assertThat(inc.actualMissing()).isEmpty();
        assertThat(inc.storedMissing()).hasSize(9);
        assertThat(inc.missingMismatch()).isTrue();
    }

    @Test
    void inconsistent_when_stored_omits_a_real_missing_field() {
        // Real data has TRAILER missing but the report was
        // generated on a partial refresh that did not evaluate
        // it. Stored says nothing is missing; actual is {TRAILER}.
        GameDocumentDto base = fullDoc("portal");
        GameDocumentDto noTrailer = docWithData(base, withoutKey("trailerUrl"));
        GameDocumentDto withReport = docWithReport(noTrailer, "COMPLETE", List.of(), FETCHED_AT, null);
        List<ValidationConsistencyChecker.Inconsistency> incs = checker.check(List.of(withReport));
        assertThat(incs).hasSize(1);
        ValidationConsistencyChecker.Inconsistency inc = incs.get(0);
        assertThat(inc.actualMissing()).containsExactly(GameField.TRAILER);
        assertThat(inc.storedMissing()).isEmpty();
        assertThat(inc.expectedStatus()).isEqualTo(com.cheapquest.backend.domain.validation.ValidationStatus.PARTIAL);
        assertThat(inc.storedStatus()).isEqualTo("COMPLETE");
    }

    @Test
    void inconsistent_when_stored_status_disagrees_with_actual() {
        // Data is empty, actual missing = all 10 fields. Stored
        // status says COMPLETE but missingFields is empty. The
        // status and the missing-set disagree, but here we assert
        // that the missing-set mismatch alone flags the doc.
        GameDocumentDto empty = emptyDoc("new-game");
        GameDocumentDto withReport = docWithReport(empty, "COMPLETE", List.of(), FETCHED_AT, null);
        List<ValidationConsistencyChecker.Inconsistency> incs = checker.check(List.of(withReport));
        assertThat(incs).hasSize(1);
        ValidationConsistencyChecker.Inconsistency inc = incs.get(0);
        assertThat(inc.actualMissing()).hasSize(10);
        assertThat(inc.storedMissing()).isEmpty();
        assertThat(inc.expectedStatus()).isEqualTo(com.cheapquest.backend.domain.validation.ValidationStatus.PARTIAL);
        assertThat(inc.storedStatus()).isEqualTo("COMPLETE");
        assertThat(inc.missingMismatch()).isTrue();
    }

    @Test
    void consistent_when_both_blocks_empty_bootstrap_state() {
        // Fresh bootstrap: data is empty, missingFields list is
        // empty, status is missing too. The check should treat
        // validationReport=null as consistent with all-fields-
        // missing because the previous run had no opportunity to
        // evaluate. (We do not flag a null report as an
        // inconsistency by itself: a null report is the bootstrap
        // state and the next full refresh will populate it.)
        GameDocumentDto empty = emptyDoc("portal");
        List<ValidationConsistencyChecker.Inconsistency> incs = checker.check(List.of(empty));
        assertThat(incs).isEmpty();
    }

    @Test
    void reports_unknown_missing_field_name_silently() {
        // Defensive: an old schema version may have a renamed
        // field. The stored missingFields contains "LEGACY_FIELD"
        // which is not a valid GameField. The checker must skip
        // it without raising; the remaining fields are compared
        // normally.
        GameDocumentDto full = fullDoc("portal");
        GameDocumentDto withReport = docWithReport(full, "PARTIAL",
                List.of("TRAILER", "LEGACY_FIELD"),
                FETCHED_AT, null);
        // Actual missing = empty (data is full). Stored missing
        // = {TRAILER} (LEGACY_FIELD is filtered out). Disagreement
        // is flagged.
        List<ValidationConsistencyChecker.Inconsistency> incs = checker.check(List.of(withReport));
        assertThat(incs).hasSize(1);
        ValidationConsistencyChecker.Inconsistency inc = incs.get(0);
        assertThat(inc.actualMissing()).isEmpty();
        assertThat(inc.storedMissing()).containsExactly(GameField.TRAILER);
    }

    @Test
    void treats_null_rawg_block_as_all_rawg_fields_missing() {
        // If rawg.synced is false and data is null, all 9 RAWG
        // fields are missing. STORES is present (cheapshark has
        // offers), so STORES is not in missing. The stored report
        // must mirror the actual missing set to be consistent.
        GameDocumentDto onlyDeals = onlyDealsDoc("portal");
        GameDocumentDto consistentReport = docWithReport(onlyDeals, "PARTIAL",
                List.of("DESCRIPTION", "HEADER_IMAGE", "TRAILER", "GENRES", "TAGS",
                        "SCREENSHOTS", "RELEASED", "DEVELOPER", "PUBLISHER"),
                FETCHED_AT, null);
        List<ValidationConsistencyChecker.Inconsistency> incs = checker.check(List.of(consistentReport));
        assertThat(incs).isEmpty();
    }

    @Test
    void treats_null_cheapshark_block_as_stores_missing() {
        // Symmetric: rawg is full, cheapshark is null. STORES is
        // missing; the 9 RAWG fields are present.
        GameDocumentDto onlyRawg = onlyRawgDoc("portal");
        GameDocumentDto withReport = docWithReport(onlyRawg, "PARTIAL",
                List.of("STORES"), FETCHED_AT, null);
        List<ValidationConsistencyChecker.Inconsistency> incs = checker.check(List.of(withReport));
        assertThat(incs).isEmpty();
    }

    @Test
    void flags_null_doc_in_iterable() {
        // Defensive: a null entry in the iterable should be
        // surfaced as an inconsistency with a placeholder slug.
        List<ValidationConsistencyChecker.Inconsistency> incs = checker.check(java.util.Arrays.asList(
                fullDoc("portal"), null));
        assertThat(incs).hasSize(1);
        assertThat(incs.get(0).slug()).isEqualTo("<null>");
    }

    @Test
    void treats_blank_string_as_missing() {
        // "   " description should be treated as missing by
        // both the writer (ValidationService) and the checker,
        // so a doc with a blank description AND blank
        // descriptionRaw and stored missingFields=[DESCRIPTION]
        // is consistent.
        GameDocumentDto base = fullDoc("portal");
        GameDocumentDto blankDesc = docWithData(base, data -> {
            data.put("description", "   ");
            data.put("descriptionRaw", "   ");
            return data;
        });
        GameDocumentDto withReport = docWithReport(blankDesc, "PARTIAL",
                List.of("DESCRIPTION"), FETCHED_AT, null);
        List<ValidationConsistencyChecker.Inconsistency> incs = checker.check(List.of(withReport));
        assertThat(incs).isEmpty();
    }

    @Test
    void treats_descriptionRaw_as_alternative_to_description() {
        // DESCRIPTION is considered present if EITHER description
        // or descriptionRaw is non-blank. Mirrors the writer
        // rule in ValidationService.
        GameDocumentDto base = fullDoc("portal");
        GameDocumentDto onlyRaw = docWithData(base, data -> {
            data.put("description", null);
            data.put("descriptionRaw", "raw description");
            return data;
        });
        List<ValidationConsistencyChecker.Inconsistency> incs = checker.check(List.of(onlyRaw));
        assertThat(incs).isEmpty();
    }

    // -- helpers --

    private static GameDocumentDto fullDoc(String slug) {
        GameDocumentDto empty = emptyDoc(slug);
        GameDocumentDto withRawg = docWithData(empty, ValidationConsistencyCheckerTest::populateAllFields);
        return docWithCheapshark(withRawg, fullDeals(slug));
    }

    private static GameDocumentDto emptyDoc(String slug) {
        return new GameDocumentDto(
                slug, slug, "en", true, ADDED_AT,
                CheapsharkBlock.empty(),
                RawgBlock.empty(),
                Map.of("es", LocaleBlock.unsynced(),
                        "en", LocaleBlock.unsynced(),
                        "fr", LocaleBlock.unsynced()),
                null);
    }

    private static GameDocumentDto onlyDealsDoc(String slug) {
        return new GameDocumentDto(
                slug, slug, "en", true, ADDED_AT,
                new CheapsharkBlock(
                        true, "82", new BigDecimal("0.99"), null, 1, List.of(), FETCHED_AT),
                RawgBlock.empty(),
                Map.of("es", LocaleBlock.unsynced(),
                        "en", LocaleBlock.unsynced(),
                        "fr", LocaleBlock.unsynced()),
                new ValidationReportDto("PARTIAL", List.of(), FETCHED_AT, null));
    }

    private static GameDocumentDto onlyRawgDoc(String slug) {
        return new GameDocumentDto(
                slug, slug, "en", true, ADDED_AT,
                CheapsharkBlock.empty(),
                new RawgBlock(true, FETCHED_AT, populateAllFields(new java.util.HashMap<>())),
                Map.of("es", LocaleBlock.unsynced(),
                        "en", LocaleBlock.unsynced(),
                        "fr", LocaleBlock.unsynced()),
                new ValidationReportDto("PARTIAL", List.of("STORES"), FETCHED_AT, null));
    }

    private static Map<String, Object> populateAllFields(Map<String, Object> data) {
        data.put("description", "Lorem ipsum dolor sit amet.");
        data.put("descriptionRaw", "Lorem ipsum dolor sit amet.");
        data.put("headerImage", "https://example.com/header.jpg");
        data.put("trailerUrl", "https://example.com/trailer.mp4");
        data.put("released", "2007-10-10");
        data.put("developers", List.of(Map.of("name", "Valve", "slug", "valve")));
        data.put("publishers", List.of(Map.of("name", "Valve", "slug", "valve")));
        data.put("genres", List.of(Map.of("name", "Action", "slug", "action")));
        data.put("tags", List.of(Map.of("name", "fps", "slug", "fps")));
        data.put("screenshots", List.of("https://example.com/s1.jpg"));
        return data;
    }

    private static GameDocumentDto docWithCheapshark(GameDocumentDto base, CheapsharkBlock cheap) {
        return new GameDocumentDto(
                base.title(), base.slug(), base.originalLanguage(), base.active(), base.addedAt(),
                cheap,
                base.rawg(),
                base.locales(),
                base.validationReport());
    }

    private static CheapsharkBlock fullDeals(String slug) {
        return new CheapsharkBlock(
                true, "82", new BigDecimal("0.99"), null, 1, List.of(), FETCHED_AT);
    }

    private static GameDocumentDto docWithData(GameDocumentDto base,
            java.util.function.Function<Map<String, Object>, Map<String, Object>> transform) {
        Map<String, Object> baseData = base.rawg() == null || base.rawg().data() == null
                ? new java.util.HashMap<>()
                : new java.util.HashMap<>(base.rawg().data());
        Map<String, Object> newData = transform.apply(baseData);
        return new GameDocumentDto(
                base.title(), base.slug(), base.originalLanguage(), base.active(), base.addedAt(),
                base.cheapshark(),
                new RawgBlock(newData.isEmpty() ? false : true,
                        newData.isEmpty() ? null : Instant.parse(FETCHED_AT).toString(),
                        newData),
                base.locales(),
                base.validationReport());
    }

    private static GameDocumentDto docWithReport(GameDocumentDto base, String status,
            List<String> missingFields, String lastFull, String lastPartial) {
        return new GameDocumentDto(
                base.title(), base.slug(), base.originalLanguage(), base.active(), base.addedAt(),
                base.cheapshark(), base.rawg(), base.locales(),
                new ValidationReportDto(status, missingFields, lastFull, lastPartial));
    }

    private static java.util.function.Function<Map<String, Object>, Map<String, Object>> withoutKey(String key) {
        return data -> {
            data.remove(key);
            return data;
        };
    }
}
