package com.cheapquest.backend.service.sections;

import com.cheapquest.backend.domain.sections.GameView;
import com.cheapquest.backend.domain.sections.SectionName;
import com.cheapquest.backend.domain.sections.SectionSnapshot;
import com.cheapquest.backend.exception.ConflictException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates the daily section recompute. The single-flight
 * contract is owned here: one admin call (or one cron tick)
 * at a time, regardless of which section it targets.
 *
 * <p>Lifecycle of a {@link #recompute(SectionName)} call:
 * <ol>
 *   <li>Acquire the {@link SectionsLock}. If held by a
 *       concurrent caller, throw
 *       {@link ConflictException} so the endpoint can map
 *       it to HTTP 409 without doing any work.</li>
 *   <li>Fetch the catalog from the injected
 *       {@code Supplier<List<GameView>>}. One read per
 *       recompute call, regardless of which section is
 *       being built; the supplier is the only coupling
 *       to the games collection and can wrap any
 *       Firestore-backed source.</li>
 *   <li>Find the {@link SectionBuilder} for the requested
 *       name. If there is none, the run is recorded as
 *       {@link Status#SKIPPED_NO_BUILDER} (not an error:
 *       it just means the section is not implemented
 *       yet) and no Firestore write happens.</li>
 *   <li>Build, wrap in a {@link SectionSnapshot} with the
 *       UTC date and the current {@code Instant}, and
 *       persist via {@link SectionStore#write(SectionSnapshot)}.</li>
 *   <li>Release the lock in {@code finally} so an
 *       exception in any of the previous steps does not
 *       leave the lock held.</li>
 * </ol>
 *
 * <p>{@link #recomputeAll()} runs the same lifecycle once
 * for all five {@link SectionName} values, sharing one
 * catalog fetch and one lock acquisition. A per-section
 * exception is captured as a {@link Status#FAILED} report
 * so a single misbehaving builder does not abort the
 * rest of the batch.
 *
 * <p>Thread-safe: the lock guards concurrent access, the
 * builder map is immutable, and the store / lock / clock
 * are all stateless or otherwise concurrent-safe by
 * construction.
 */
public final class SectionsService {

    private static final Logger log = LoggerFactory.getLogger(SectionsService.class);

    private final SectionStore store;
    private final SectionsLock lock;
    private final Map<SectionName, SectionBuilder> builders;
    private final Supplier<List<GameView>> catalogSupplier;
    private final Clock clock;

    public SectionsService(SectionStore store, SectionsLock lock,
            List<SectionBuilder> builders,
            Supplier<List<GameView>> catalogSupplier,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.lock = Objects.requireNonNull(lock, "lock");
        this.catalogSupplier = Objects.requireNonNull(catalogSupplier, "catalogSupplier");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(builders, "builders");
        Map<SectionName, SectionBuilder> byName = new EnumMap<>(SectionName.class);
        for (SectionBuilder b : builders) {
            Objects.requireNonNull(b, "builder");
            SectionBuilder previous = byName.put(b.name(), b);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "duplicate builder for section: " + b.name()
                                + " (already had " + previous.getClass().getSimpleName() + ")");
            }
        }
        this.builders = Map.copyOf(byName);
        log.info("sections_service_initialized builderCount={} sections={}",
                this.builders.size(), this.builders.keySet());
    }

    public Report recompute(SectionName name) {
        Objects.requireNonNull(name, "name");
        if (!lock.tryAcquire()) {
            throw new ConflictException("section recompute already in progress");
        }
        try {
            return runOne(name, new SectionContext(loadCatalog()));
        } finally {
            lock.release();
        }
    }

    public List<Report> recomputeAll() {
        if (!lock.tryAcquire()) {
            throw new ConflictException("section recompute already in progress");
        }
        try {
            SectionContext ctx = new SectionContext(loadCatalog());
            List<Report> out = new ArrayList<>(SectionName.values().length);
            for (SectionName n : SectionName.values()) {
                out.add(runOne(n, ctx));
            }
            return List.copyOf(out);
        } finally {
            lock.release();
        }
    }

    private Report runOne(SectionName name, SectionContext ctx) {
        long start = clock.millis();
        SectionBuilder builder = builders.get(name);
        if (builder == null) {
            log.info("section_skipped name={} reason=no_builder", name.slug());
            return Report.skipped(name, clock.millis() - start);
        }
        try {
            BuildResult result = builder.build(ctx);
            SectionSnapshot snapshot = new SectionSnapshot(
                    name,
                    LocalDate.now(clock),
                    Instant.now(clock),
                    result.totalCandidates(),
                    result.items());
            store.write(snapshot);
            long durationMs = clock.millis() - start;
            log.info("section_recomputed name={} totalCandidates={} itemsKept={} durationMs={}",
                    name.slug(), result.totalCandidates(), result.items().size(), durationMs);
            return Report.completed(name, result.totalCandidates(), result.items().size(), durationMs);
        } catch (RuntimeException e) {
            long durationMs = clock.millis() - start;
            log.error("section_recompute_failed name={} durationMs={} error={}: {}",
                    name.slug(), durationMs, e.getClass().getSimpleName(), e.getMessage(), e);
            return Report.failed(name, durationMs,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private List<GameView> loadCatalog() {
        return catalogSupplier.get();
    }

    public record Report(SectionName name, Status status,
            int totalCandidates, int itemsKept,
            long durationMs, String error) {

        public static Report completed(SectionName name, int totalCandidates,
                int itemsKept, long durationMs) {
            return new Report(name, Status.COMPLETED, totalCandidates, itemsKept, durationMs, null);
        }

        public static Report skipped(SectionName name, long durationMs) {
            return new Report(name, Status.SKIPPED_NO_BUILDER, 0, 0, durationMs, null);
        }

        public static Report failed(SectionName name, long durationMs, String error) {
            return new Report(name, Status.FAILED, 0, 0, durationMs, error);
        }
    }

    public enum Status {
        COMPLETED,
        SKIPPED_NO_BUILDER,
        FAILED
    }
}
