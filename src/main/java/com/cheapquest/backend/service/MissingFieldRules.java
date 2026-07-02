package com.cheapquest.backend.service;

import com.cheapquest.backend.domain.validation.GameField;
import com.cheapquest.backend.util.StringUtils;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Single source of truth for the "is this field empty?" rules shared
 * by {@link ValidationService} (writer side) and
 * {@link ValidationConsistencyChecker} (auditor side).
 *
 * <p>Both evaluators build a {@link Snapshot} from their respective
 * data shapes (typed domain objects vs Firestore document blocks)
 * and call {@link #evaluate(Snapshot)} to obtain the canonical set
 * of missing fields. Adding a new field means: extend
 * {@link Snapshot}, add one entry to {@link #RULES}. Both
 * evaluators automatically pick up the change.
 */
public final class MissingFieldRules {

    private MissingFieldRules() {
    }

    /**
     * Flattened view of a game's data blocks. Each entry is the
     * value as observed in the corresponding block; rules treat
     * {@code null}, blank strings and empty collections as
     * "missing". The projection is intentionally lossy: a value
     * that is not of the expected shape (e.g. a non-String where
     * a String is expected) collapses to {@code null} and is
     * therefore treated as missing.
     */
    public record Snapshot(
            String description,
            String descriptionRaw,
            String headerImage,
            String trailerUrl,
            String released,
            Collection<?> genres,
            Collection<?> tags,
            Collection<?> screenshots,
            Collection<?> developers,
            Collection<?> publishers,
            Integer offerCount) {
    }

    public record Rule(GameField field, Predicate<Snapshot> isMissing) {
    }

    public static final List<Rule> RULES = List.of(
            new Rule(GameField.DESCRIPTION, s ->
                    StringUtils.isBlank(s.description()) && StringUtils.isBlank(s.descriptionRaw())),
            new Rule(GameField.HEADER_IMAGE, s -> StringUtils.isBlank(s.headerImage())),
            new Rule(GameField.TRAILER, s -> StringUtils.isBlank(s.trailerUrl())),
            new Rule(GameField.GENRES, s -> isEmpty(s.genres())),
            new Rule(GameField.TAGS, s -> isEmpty(s.tags())),
            new Rule(GameField.SCREENSHOTS, s -> isEmpty(s.screenshots())),
            new Rule(GameField.STORES, s -> s.offerCount() == null || s.offerCount() == 0),
            new Rule(GameField.RELEASED, s -> StringUtils.isBlank(s.released())),
            new Rule(GameField.DEVELOPER, s -> isEmpty(s.developers())),
            new Rule(GameField.PUBLISHER, s -> isEmpty(s.publishers())));

    /**
     * Evaluate all rules against the snapshot and return the
     * canonical set of fields that are considered missing.
     */
    public static Set<GameField> evaluate(Snapshot snapshot) {
        EnumSet<GameField> missing = EnumSet.noneOf(GameField.class);
        for (Rule rule : RULES) {
            if (rule.isMissing().test(snapshot)) {
                missing.add(rule.field());
            }
        }
        return missing;
    }

    private static boolean isEmpty(Collection<?> c) {
        return c == null || c.isEmpty();
    }
}
