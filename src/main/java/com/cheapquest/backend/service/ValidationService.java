package com.cheapquest.backend.service;

import com.cheapquest.backend.domain.AggregatedGame;
import com.cheapquest.backend.domain.GameDeals;
import com.cheapquest.backend.domain.rawg.RawgDetails;
import com.cheapquest.backend.domain.validation.GameField;
import com.cheapquest.backend.domain.validation.ValidationReport;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Inspects an {@link AggregatedGame} and produces a {@link ValidationReport}
 * describing which fields are empty so the future employee-facing UI can
 * pinpoint deficiencies and trigger the appropriate refetch (AGENTS.md §7).
 *
 * <p>REVIEWS is part of the {@link GameField} enum but is never added to
 * {@code missingFields} because no review source has been wired yet.
 */
public final class ValidationService {

    private static final Logger log = LoggerFactory.getLogger(ValidationService.class);

    private final Clock clock;

    public ValidationService() {
        this(Clock.systemUTC());
    }

    public ValidationService(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ValidationReport evaluate(AggregatedGame game) {
        Objects.requireNonNull(game, "game");
        Instant fetchedAt = game.fetchedAt();
        Set<GameField> missing = evaluateMissing(game);

        if (game.rawg() == null && game.cheapShark() == null) {
            log.debug("validation_empty target=\"{}\"", game.cheapSharkTitle());
            return ValidationReport.empty(fetchedAt);
        }
        if (missing.isEmpty()) {
            log.debug("validation_complete target=\"{}\"", game.cheapSharkTitle());
            return ValidationReport.complete(missing, fetchedAt);
        }
        log.info("validation_partial target=\"{}\" missing={}", game.cheapSharkTitle(), missing);
        return ValidationReport.partial(missing, fetchedAt);
    }

    private static Set<GameField> evaluateMissing(AggregatedGame game) {
        RawgDetails rawg = game.rawg();
        GameDeals cheap = game.cheapShark();
        MissingFieldRules.Snapshot snap = new MissingFieldRules.Snapshot(
                rawg == null ? null : rawg.description(),
                rawg == null ? null : rawg.descriptionRaw(),
                rawg == null ? null : rawg.headerImage(),
                rawg == null ? null : rawg.trailerUrl(),
                rawg == null ? null : rawg.released(),
                rawg == null ? null : rawg.genres(),
                rawg == null ? null : rawg.tags(),
                rawg == null ? null : rawg.screenshots(),
                rawg == null ? null : rawg.developers(),
                rawg == null ? null : rawg.publishers(),
                cheap == null ? null : cheap.offerCount());
        return MissingFieldRules.evaluate(snap);
    }
}
