package com.cheapquest.backend.domain.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ValidationReportTest {

    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void rejects_null_status() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ValidationReport(null, Set.of(), T, null))
                .withMessageContaining("status");
    }

    @Test
    void accepts_null_lastFullFetchAt_to_preserve_bootstrap_state() {
        // Post-fix: lastFullFetchAt is nullable so a partial refresh
        // on a freshly bootstrapped doc can keep the field null
        // instead of fabricating a fake 'now' timestamp that would
        // suggest a full refresh had occurred. See
        // GameHydrationService.composeReport for the contract.
        ValidationReport r = new ValidationReport(ValidationStatus.PARTIAL, Set.of(GameField.TRAILER), null, null);
        assertThat(r.status()).isEqualTo(ValidationStatus.PARTIAL);
        assertThat(r.missingFields()).containsExactly(GameField.TRAILER);
        assertThat(r.lastFullFetchAt()).isNull();
        assertThat(r.lastPartialFetchAt()).isNull();
    }

    @Test
    void accepts_null_missingFields_and_returns_emptySet() {
        ValidationReport r = new ValidationReport(ValidationStatus.COMPLETE, null, T, null);
        assertThat(r.missingFields()).isEmpty();
    }

    @Test
    void missingFields_is_defensive_copy() {
        Set<GameField> mutable = new HashSet<>();
        mutable.add(GameField.TAGS);
        ValidationReport r = new ValidationReport(ValidationStatus.PARTIAL, mutable, T, null);
        mutable.add(GameField.TRAILER);
        assertThat(r.missingFields()).containsExactly(GameField.TAGS);
    }

    @Test
    void missingFields_is_immutable() {
        ValidationReport r = ValidationReport.partial(EnumSet.of(GameField.TAGS), T);
        assertThat(r.missingFields()).isUnmodifiable();
    }

    @Test
    void complete_factory_builds_with_empty_missingFields() {
        ValidationReport r = ValidationReport.complete(Set.of(), T);
        assertThat(r.status()).isEqualTo(ValidationStatus.COMPLETE);
        assertThat(r.missingFields()).isEmpty();
        assertThat(r.lastFullFetchAt()).isEqualTo(T);
        assertThat(r.lastPartialFetchAt()).isNull();
    }

    @Test
    void partial_factory_builds_with_given_missingFields() {
        ValidationReport r = ValidationReport.partial(EnumSet.of(GameField.TRAILER), T);
        assertThat(r.status()).isEqualTo(ValidationStatus.PARTIAL);
        assertThat(r.missingFields()).containsExactly(GameField.TRAILER);
    }

    @Test
    void empty_factory_builds_with_empty_missingFields() {
        ValidationReport r = ValidationReport.empty(T);
        assertThat(r.status()).isEqualTo(ValidationStatus.EMPTY);
        assertThat(r.missingFields()).isEmpty();
    }
}
