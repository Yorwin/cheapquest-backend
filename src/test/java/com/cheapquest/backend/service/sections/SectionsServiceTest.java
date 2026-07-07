package com.cheapquest.backend.service.sections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.atMostOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cheapquest.backend.domain.Offer;
import com.cheapquest.backend.domain.sections.GameView;
import com.cheapquest.backend.domain.sections.SectionItem;
import com.cheapquest.backend.domain.sections.SectionName;
import com.cheapquest.backend.domain.sections.SectionSnapshot;
import com.cheapquest.backend.exception.ConflictException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SectionsServiceTest {

    private static final Instant T = Instant.parse("2026-07-06T00:00:05Z");
    private static final LocalDate DAY = LocalDate.parse("2026-07-06");
    private static final Clock CLOCK = Clock.fixed(T, ZoneOffset.UTC);

    private static final Offer OFFER = new Offer(
            "1", "Steam", null,
            new BigDecimal("9.99"), new BigDecimal("29.99"),
            new BigDecimal("66.70"), null, null);

    private static final SectionItem ITEM = new SectionItem(
            "slug", "Title", OFFER, new BigDecimal("66.70"),
            Map.of("savingsPct", "66.70"), null);

    private SectionStore store;
    private SectionsLock lock;
    private Supplier<List<GameView>> catalogSupplier;

    @BeforeEach
    void setUp() {
        store = mock(SectionStore.class);
        lock = mock(SectionsLock.class);
        when(lock.tryAcquire()).thenReturn(true);
        catalogSupplier = () -> List.of();
    }

    private SectionsService serviceWith(SectionBuilder... builders) {
        return new SectionsService(store, lock, List.of(builders), catalogSupplier, CLOCK);
    }

    // -------- recompute(name) ------------------------------------------------

    @Test
    void recompute_runs_the_matching_builder_and_writes_snapshot() {
        SectionBuilder builder = stubBuilder(SectionName.MEJORES_PROMOS,
                new BuildResult(3, List.of(ITEM)));
        SectionsService svc = serviceWith(builder);

        SectionsService.Report report = svc.recompute(SectionName.MEJORES_PROMOS);

        assertThat(report.status()).isEqualTo(SectionsService.Status.COMPLETED);
        assertThat(report.name()).isEqualTo(SectionName.MEJORES_PROMOS);
        assertThat(report.totalCandidates()).isEqualTo(3);
        assertThat(report.itemsKept()).isEqualTo(1);
        assertThat(report.error()).isNull();

        ArgumentCaptor<SectionSnapshot> captor = ArgumentCaptor.forClass(SectionSnapshot.class);
        verify(store, times(1)).write(captor.capture());
        SectionSnapshot snap = captor.getValue();
        assertThat(snap.name()).isEqualTo(SectionName.MEJORES_PROMOS);
        assertThat(snap.date()).isEqualTo(DAY);
        assertThat(snap.computedAt()).isEqualTo(T);
        assertThat(snap.totalCandidates()).isEqualTo(3);
        assertThat(snap.items()).containsExactly(ITEM);
    }

    @Test
    void recompute_with_no_builder_for_name_returns_skipped() {
        SectionsService svc = serviceWith();  // empty builder list
        SectionsService.Report report = svc.recompute(SectionName.MEJORES_PROMOS);

        assertThat(report.status()).isEqualTo(SectionsService.Status.SKIPPED_NO_BUILDER);
        assertThat(report.totalCandidates()).isZero();
        assertThat(report.itemsKept()).isZero();
        verify(store, never()).write(any());
    }

    @Test
    void recompute_catches_builder_exception_and_returns_failed() {
        SectionBuilder builder = mock(SectionBuilder.class);
        when(builder.name()).thenReturn(SectionName.MEJORES_PROMOS);
        when(builder.build(any())).thenThrow(new RuntimeException("boom"));

        SectionsService svc = serviceWith(builder);
        SectionsService.Report report = svc.recompute(SectionName.MEJORES_PROMOS);

        assertThat(report.status()).isEqualTo(SectionsService.Status.FAILED);
        assertThat(report.error()).contains("boom");
        verify(store, never()).write(any());
    }

    @Test
    void recompute_catches_store_exception_and_returns_failed() {
        SectionBuilder builder = stubBuilder(SectionName.MEJORES_PROMOS,
                new BuildResult(1, List.of(ITEM)));
        org.mockito.Mockito.doThrow(new RuntimeException("firestore down"))
                .when(store).write(any(SectionSnapshot.class));

        SectionsService svc = serviceWith(builder);
        SectionsService.Report report = svc.recompute(SectionName.MEJORES_PROMOS);

        assertThat(report.status()).isEqualTo(SectionsService.Status.FAILED);
        assertThat(report.error()).contains("firestore down");
    }

    @Test
    void recompute_releases_lock_in_finally_even_on_failure() {
        SectionBuilder builder = mock(SectionBuilder.class);
        when(builder.name()).thenReturn(SectionName.MEJORES_PROMOS);
        when(builder.build(any())).thenThrow(new RuntimeException("boom"));

        SectionsService svc = serviceWith(builder);
        svc.recompute(SectionName.MEJORES_PROMOS);

        verify(lock, times(1)).release();
    }

    @Test
    void recompute_releases_lock_in_finally_on_success() {
        SectionBuilder builder = stubBuilder(SectionName.MEJORES_PROMOS,
                new BuildResult(1, List.of(ITEM)));
        SectionsService svc = serviceWith(builder);
        svc.recompute(SectionName.MEJORES_PROMOS);
        verify(lock, times(1)).release();
    }

    @Test
    void recompute_throws_ConflictException_when_lock_held() {
        when(lock.tryAcquire()).thenReturn(false);
        SectionsService svc = serviceWith();
        assertThatThrownBy(() -> svc.recompute(SectionName.MEJORES_PROMOS))
                .isInstanceOf(ConflictException.class);
        verify(lock, never()).release();
    }

    @Test
    void recompute_fetches_catalog_once_per_call() {
        AtomicInteger calls = new AtomicInteger(0);
        catalogSupplier = () -> {
            calls.incrementAndGet();
            return List.of();
        };
        SectionBuilder builder = stubBuilder(SectionName.MEJORES_PROMOS,
                new BuildResult(0, List.of()));
        SectionsService svc = serviceWith(builder);

        svc.recompute(SectionName.MEJORES_PROMOS);
        svc.recompute(SectionName.MEJORES_PROMOS);

        assertThat(calls.get()).isEqualTo(2);
    }

    // -------- recomputeAll ---------------------------------------------------

    @Test
    void recomputeAll_runs_every_section_through_its_builder() {
        SectionBuilder promos = stubBuilder(SectionName.MEJORES_PROMOS,
                new BuildResult(5, List.of(ITEM)));
        SectionsService svc = serviceWith(promos);

        List<SectionsService.Report> reports = svc.recomputeAll();

        // Without builders for the other 4 sections, they come back SKIPPED.
        assertThat(reports).hasSize(SectionName.values().length);
        long completed = reports.stream()
                .filter(r -> r.status() == SectionsService.Status.COMPLETED).count();
        long skipped = reports.stream()
                .filter(r -> r.status() == SectionsService.Status.SKIPPED_NO_BUILDER).count();
        assertThat(completed).isEqualTo(1);
        assertThat(skipped).isEqualTo(4);
    }

    @Test
    void recomputeAll_fetches_catalog_only_once() {
        AtomicInteger calls = new AtomicInteger(0);
        catalogSupplier = () -> {
            calls.incrementAndGet();
            return List.of();
        };
        SectionBuilder promos = stubBuilder(SectionName.MEJORES_PROMOS,
                new BuildResult(0, List.of()));
        SectionsService svc = serviceWith(promos);

        svc.recomputeAll();

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void recomputeAll_continues_after_a_builder_throws() {
        SectionBuilder promos = stubBuilder(SectionName.MEJORES_PROMOS,
                new BuildResult(1, List.of(ITEM)));
        SectionBuilder populares = mock(SectionBuilder.class);
        when(populares.name()).thenReturn(SectionName.POPULARES);
        when(populares.build(any())).thenThrow(new RuntimeException("nope"));

        SectionsService svc = serviceWith(promos, populares);
        List<SectionsService.Report> reports = svc.recomputeAll();

        SectionsService.Report promosReport = reports.stream()
                .filter(r -> r.name() == SectionName.MEJORES_PROMOS).findFirst().orElseThrow();
        SectionsService.Report popularesReport = reports.stream()
                .filter(r -> r.name() == SectionName.POPULARES).findFirst().orElseThrow();
        assertThat(promosReport.status()).isEqualTo(SectionsService.Status.COMPLETED);
        assertThat(popularesReport.status()).isEqualTo(SectionsService.Status.FAILED);
        assertThat(popularesReport.error()).contains("nope");
    }

    @Test
    void recomputeAll_throws_ConflictException_when_lock_held() {
        when(lock.tryAcquire()).thenReturn(false);
        SectionsService svc = serviceWith();
        assertThatThrownBy(svc::recomputeAll).isInstanceOf(ConflictException.class);
        verify(lock, never()).release();
    }

    @Test
    void recomputeAll_releases_lock_in_finally_even_on_failure() {
        // The catalog supplier throws before the per-section loop runs.
        catalogSupplier = () -> { throw new RuntimeException("catalog down"); };
        SectionsService svc = serviceWith();

        assertThatThrownBy(svc::recomputeAll).isInstanceOf(RuntimeException.class);
        verify(lock, times(1)).release();
    }

    // -------- construction ---------------------------------------------------

    @Test
    void rejects_duplicate_builder_for_same_section() {
        SectionBuilder a = stubBuilder(SectionName.MEJORES_PROMOS, new BuildResult(0, List.of()));
        SectionBuilder b = stubBuilder(SectionName.MEJORES_PROMOS, new BuildResult(0, List.of()));
        assertThatThrownBy(() -> serviceWith(a, b))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void rejects_null_builder_in_list() {
        assertThatThrownBy(() -> serviceWith((SectionBuilder) null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void accepts_empty_builder_list() {
        SectionsService svc = serviceWith();
        List<SectionsService.Report> reports = svc.recomputeAll();
        assertThat(reports).extracting(r -> r.status())
                .containsOnly(SectionsService.Status.SKIPPED_NO_BUILDER);
    }

    // -------- helpers --------------------------------------------------------

    private static SectionBuilder stubBuilder(SectionName name, BuildResult result) {
        SectionBuilder b = mock(SectionBuilder.class);
        when(b.name()).thenReturn(name);
        when(b.build(any())).thenReturn(result);
        return b;
    }
}
