package com.cheapquest.backend.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cheapquest.backend.dto.HydrationReport;
import com.cheapquest.backend.exception.ConflictException;
import com.cheapquest.backend.service.GameHydrationService;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

class RefreshServiceTest {

    private final RefreshLock lock = new InMemoryRefreshLock();
    private final GameHydrationService hydration = mock(GameHydrationService.class);
    private final Clock clock = Clock.systemUTC();
    private final RefreshService service = new RefreshService(lock, hydration, clock);

    @Test
    void returns_completed_outcome_on_success() {
        when(hydration.hydrateAll(anyBoolean()))
                .thenReturn(new HydrationReport(10, 7, 2, 1, 0, 0, 3, 8, 0, 1234L, List.of(), List.of()));

        RefreshService.Outcome outcome = service.refresh(false);

        assertThat(outcome.status()).isEqualTo("completed");
        assertThat(outcome.processed()).isEqualTo(10);
        assertThat(outcome.failed()).isEqualTo(0);
        assertThat(outcome.durationMs()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void releases_lock_after_success() {
        when(hydration.hydrateAll(anyBoolean()))
                .thenReturn(new HydrationReport(0, 0, 0, 0, 0, 0, 0, 0, 0, 0L, List.of(), List.of()));

        service.refresh(false);

        assertThat(lock.isHeld()).isFalse();
    }

    @Test
    void releases_lock_even_when_hydration_throws() {
        when(hydration.hydrateAll(anyBoolean())).thenThrow(new RuntimeException("Firestore down"));

        assertThatThrownBy(() -> service.refresh(false))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Firestore down");

        assertThat(lock.isHeld()).isFalse();
    }

    @Test
    void throws_conflict_when_lock_already_held() {
        when(hydration.hydrateAll(anyBoolean()))
                .thenReturn(new HydrationReport(0, 0, 0, 0, 0, 0, 0, 0, 0, 0L, List.of(), List.of()));
        // Hold the lock manually to simulate a concurrent caller.
        lock.tryAcquire();

        assertThatThrownBy(() -> service.refresh(false))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("in progress");

        // The losing caller must not have called hydration.
    }
}
