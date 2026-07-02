package com.cheapquest.backend.service;

import com.cheapquest.backend.domain.validation.GameField;
import com.cheapquest.backend.domain.validation.ValidationStatus;
import com.cheapquest.backend.dto.firebase.CheapsharkBlock;
import com.cheapquest.backend.dto.firebase.GameDocumentDto;
import com.cheapquest.backend.dto.firebase.ValidationReportDto;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read-only auditor that compares every game document's stored
 * {@code validationReport.missingFields} against the set of fields
 * that are actually empty in the document's data blocks. A document
 * is <b>consistent</b> when:
 * <ul>
 *   <li>the stored set of missing fields equals the freshly
 *       computed set (a full evaluation of the current data);</li>
 *   <li>the stored status matches the freshly computed one
 *       ({@code COMPLETE} when the set is empty,
 *       {@code PARTIAL} otherwise).</li>
 * </ul>
 *
 * <p>This is the read-side counterpart of
 * {@link ValidationService#evaluate(com.cheapquest.backend.domain.AggregatedGame)}.
 * The hydration service writes {@code missingFields} when it merges
 * fresh data into a doc; this checker answers the question: "is the
 * report we wrote yesterday still the truth about today's data?".
 * It is the safety net that catches a stale or corrupted report
 * before it is acted on (a future UI may use the report to trigger
 * selective re-fetches; acting on a wrong report would be wasted
 * work or, worse, missed re-fetches).
 *
 * <p>The checker is pure: it takes an {@link Iterable} of docs and
 * returns a list of {@link Inconsistency} records. It performs no
 * I/O and has no external dependencies. It is runnable from the
 * smoke test ({@code -Dapp.mode=validate}) and from CI without any
 * Firebase credentials (pass in a list of manually-built
 * {@link GameDocumentDto}s).
 */
public final class ValidationConsistencyChecker {

    private static final Logger log = LoggerFactory.getLogger(ValidationConsistencyChecker.class);

    public ValidationConsistencyChecker() {
    }

    public List<Inconsistency> check(Iterable<GameDocumentDto> docs) {
        Objects.requireNonNull(docs, "docs");
        List<Inconsistency> inconsistencies = new ArrayList<>();
        int total = 0;
        for (GameDocumentDto doc : docs) {
            total++;
            if (doc == null || doc.slug() == null) {
                inconsistencies.add(Inconsistency.nullDoc(doc == null ? "<null>" : doc.slug()));
                continue;
            }
            Inconsistency inc = checkOne(doc);
            if (inc != null) {
                inconsistencies.add(inc);
            }
        }
        log.info("validation_consistency_check docs={} inconsistencies={}", total, inconsistencies.size());
        return inconsistencies;
    }

    private static Inconsistency checkOne(GameDocumentDto doc) {
        if (doc.validationReport() == null) {
            // Bootstrap state: no report yet. The next full refresh
            // will populate it. There is no stored claim to
            // contradict, so this is not an inconsistency.
            return null;
        }
        Set<GameField> actualMissing = computeActualMissing(doc);
        Set<GameField> storedMissing = parseStoredMissing(doc.validationReport());
        ValidationStatus expectedStatus = actualMissing.isEmpty()
                ? ValidationStatus.COMPLETE
                : ValidationStatus.PARTIAL;
        String storedStatus = doc.validationReport().status();
        boolean missingMismatch = !storedMissing.equals(actualMissing);
        boolean statusMismatch = !storedStatus.equals(expectedStatus.name());
        if (!missingMismatch && !statusMismatch) {
            return null;
        }
        log.warn("validation_inconsistency slug={} stored_missing={} actual_missing={} "
                        + "stored_status={} expected_status={}",
                doc.slug(), storedMissing, actualMissing, storedStatus, expectedStatus);
        return Inconsistency.of(doc.slug(), storedMissing, actualMissing, expectedStatus, storedStatus);
    }

    /**
     * Reproduce {@link ValidationService#evaluate} against the
     * persisted data blocks. The rules are identical to the
     * writer-side check, so a hydration that ends with this
     * method's output as the report is by construction consistent.
     */
    static Set<GameField> computeActualMissing(GameDocumentDto doc) {
        EnumSet<GameField> missing = EnumSet.noneOf(GameField.class);
        Map<String, Object> data = doc.rawg() == null ? null : doc.rawg().data();
        CheapsharkBlock cheap = doc.cheapshark();

        if (!hasNonBlankString(data, "description")
                && !hasNonBlankString(data, "descriptionRaw")) {
            missing.add(GameField.DESCRIPTION);
        }
        if (!hasNonBlankString(data, "headerImage")) {
            missing.add(GameField.HEADER_IMAGE);
        }
        if (!hasNonBlankString(data, "trailerUrl")) {
            missing.add(GameField.TRAILER);
        }
        if (!hasNonEmptyList(data, "genres")) {
            missing.add(GameField.GENRES);
        }
        if (!hasNonEmptyList(data, "tags")) {
            missing.add(GameField.TAGS);
        }
        if (!hasNonEmptyList(data, "screenshots")) {
            missing.add(GameField.SCREENSHOTS);
        }
        if (cheap == null || cheap.offerCount() == null || cheap.offerCount() == 0) {
            missing.add(GameField.STORES);
        }
        if (!hasNonBlankString(data, "released")) {
            missing.add(GameField.RELEASED);
        }
        if (!hasNonEmptyList(data, "developers")) {
            missing.add(GameField.DEVELOPER);
        }
        if (!hasNonEmptyList(data, "publishers")) {
            missing.add(GameField.PUBLISHER);
        }
        return missing;
    }

    private static Set<GameField> parseStoredMissing(ValidationReportDto report) {
        if (report == null || report.missingFields() == null) {
            return Set.of();
        }
        Set<GameField> result = new LinkedHashSet<>();
        for (String name : report.missingFields()) {
            if (name == null) {
                continue;
            }
            try {
                result.add(GameField.valueOf(name));
            } catch (IllegalArgumentException e) {
                // unknown / renamed field: skip, do not flag as missing
            }
        }
        return Set.copyOf(result);
    }

    private static boolean hasNonBlankString(Map<String, Object> data, String key) {
        if (data == null) {
            return false;
        }
        Object value = data.get(key);
        return value instanceof String s && !s.isBlank();
    }

    private static boolean hasNonEmptyList(Map<String, Object> data, String key) {
        if (data == null) {
            return false;
        }
        Object value = data.get(key);
        return value instanceof List<?> list && !list.isEmpty();
    }

    public record Inconsistency(
            String slug,
            Set<GameField> storedMissing,
            Set<GameField> actualMissing,
            ValidationStatus expectedStatus,
            String storedStatus,
            boolean missingMismatch,
            boolean statusMismatch) {

        public Inconsistency {
            storedMissing = Set.copyOf(storedMissing);
            actualMissing = Set.copyOf(actualMissing);
        }

        static Inconsistency of(String slug,
                Set<GameField> stored, Set<GameField> actual,
                ValidationStatus expected, String storedStatusName) {
            boolean missingMismatch = !stored.equals(actual);
            boolean statusMismatch = storedStatusName == null
                    ? false
                    : !storedStatusName.equals(expected.name());
            return new Inconsistency(
                    slug, stored, actual, expected, storedStatusName,
                    missingMismatch, statusMismatch);
        }

        static Inconsistency nullDoc(String slug) {
            return new Inconsistency(slug, Set.of(), Set.of(), ValidationStatus.COMPLETE,
                    null, true, false);
        }
    }
}
