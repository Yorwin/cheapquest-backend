package com.cheapquest.backend.service.sections;

import com.cheapquest.backend.domain.sections.SectionName;
import com.cheapquest.backend.domain.sections.SectionSnapshot;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * Persistence boundary for the daily section snapshots. The
 * store knows how to read and write the
 * {@code sections/{YYYY-MM-DD}/items/{slug}} (history) and
 * {@code sections/latest/items/{slug}} (live mirror)
 * documents, and nothing else; the section building logic
 * lives in {@code SectionBuilder} and the orchestration in
 * {@code SectionsService}.
 *
 * <p>Methods that read a single section return
 * {@link Optional} so the caller can distinguish a missing
 * document from a Firestore error. {@link #readAllLatest()}
 * returns a {@link Map} keyed by {@link SectionName} (the
 * enum, not the slug) so the order is the canonical
 * {@code SectionName} order and a missing section is simply
 * absent from the map.
 *
 * <p>The store does <strong>not</strong> read game data: the
 * catalog is read by the caller (typically
 * {@code SectionsService} via the section store and
 * {@code GameViewMapper}). Keeping the two concerns separate
 * means the section store can be swapped (e.g. for a memory
 * backend in tests) without touching the games side.
 */
public interface SectionStore {

    /**
     * Persist a snapshot. The implementation is responsible
     * for writing to both the per-day history doc and the
     * {@code latest} mirror in a single atomic batch, so a
     * half-failed write does not leave the live mirror stale.
     *
     * @throws com.cheapquest.backend.exception.FirebaseUnavailableException
     *     if the underlying Firestore call fails
     */
    void write(SectionSnapshot snapshot);

    /**
     * Read a snapshot for a specific day. Returns empty if
     * the doc is missing (the day has not been recomputed yet
     * for that section, or the date is wrong).
     */
    Optional<SectionSnapshot> read(SectionName name, LocalDate date);

    /**
     * Read the most recent snapshot for a section. The
     * {@code latest} mirror is overwritten on every
     * {@link #write}, so this is the "what the front would
     * see right now" entry point.
     */
    Optional<SectionSnapshot> readLatest(SectionName name);

    /**
     * Read every {@code latest} snapshot in one go. Sections
     * that have never been computed are absent from the
     * returned map; the caller should treat that as "section
     * unavailable" and fall back gracefully.
     */
    Map<SectionName, SectionSnapshot> readAllLatest();
}
