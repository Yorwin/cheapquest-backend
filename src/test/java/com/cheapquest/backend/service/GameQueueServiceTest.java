package com.cheapquest.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cheapquest.backend.client.FirebaseClient;
import com.cheapquest.backend.dto.firebase.FailedDoc;
import com.cheapquest.backend.dto.firebase.PendingDoc;
import com.cheapquest.backend.service.GameQueueService.QueueEntry;
import com.cheapquest.backend.service.GameQueueService.Status;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GameQueueServiceTest {

    private static final Instant T1 = Instant.parse("2026-06-29T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-06-30T10:00:00Z");

    private FirebaseClient firebaseClient;
    private GameQueueService service;

    @BeforeEach
    void setUp() {
        firebaseClient = mock(FirebaseClient.class);
        service = new GameQueueService(firebaseClient);
    }

    @Test
    void list_pendingMapsPendingDocsWithNullFirstAttempt() {
        when(firebaseClient.readPending()).thenReturn(List.of(
                new PendingDoc("portal", 1, T2, null),
                new PendingDoc("hl2", 2, T2, "rawg 503")));

        List<QueueEntry> result = service.list(Status.PENDING);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).slug()).isEqualTo("portal");
        assertThat(result.get(0).attempts()).isEqualTo(1);
        assertThat(result.get(0).firstAttemptAt()).isNull();
        assertThat(result.get(0).lastAttemptAt()).isEqualTo(T2);
        assertThat(result.get(0).lastError()).isNull();
        assertThat(result.get(1).slug()).isEqualTo("hl2");
        assertThat(result.get(1).attempts()).isEqualTo(2);
        assertThat(result.get(1).lastError()).isEqualTo("rawg 503");
    }

    @Test
    void list_failedMapsFailedDocsWithFirstAttempt() {
        when(firebaseClient.readFailed()).thenReturn(List.of(
                new FailedDoc("portal", 3, T1, T2, "both sources returned empty")));

        List<QueueEntry> result = service.list(Status.FAILED);

        assertThat(result).hasSize(1);
        QueueEntry entry = result.get(0);
        assertThat(entry.slug()).isEqualTo("portal");
        assertThat(entry.attempts()).isEqualTo(3);
        assertThat(entry.firstAttemptAt()).isEqualTo(T1);
        assertThat(entry.lastAttemptAt()).isEqualTo(T2);
        assertThat(entry.lastError()).isEqualTo("both sources returned empty");
    }

    @Test
    void list_emptyQueueReturnsEmptyList() {
        when(firebaseClient.readPending()).thenReturn(List.of());

        assertThat(service.list(Status.PENDING)).isEmpty();
    }

    @Test
    void list_rejectsNullStatus() {
        assertThatThrownBy(() -> service.list(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("status");
    }

    @Test
    void constructor_rejectsNullFirebaseClient() {
        assertThatThrownBy(() -> new GameQueueService(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("firebaseClient");
    }
}
