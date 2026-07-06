package com.cheapquest.backend.endpoint.sections;

import static com.cheapquest.backend.endpoint.sections.SectionsEndpointTestSupport.GSON;
import static com.cheapquest.backend.endpoint.sections.SectionsEndpointTestSupport.bodyOf;
import static com.cheapquest.backend.endpoint.sections.SectionsEndpointTestSupport.exchange;
import static com.cheapquest.backend.endpoint.sections.SectionsEndpointTestSupport.statusOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cheapquest.backend.domain.Offer;
import com.cheapquest.backend.domain.sections.SectionItem;
import com.cheapquest.backend.domain.sections.SectionName;
import com.cheapquest.backend.domain.sections.SectionSnapshot;
import com.cheapquest.backend.exception.GlobalExceptionHandler;
import com.cheapquest.backend.mapper.PublicSectionMapper;
import com.cheapquest.backend.service.sections.SectionStore;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PublicSectionReadEndpointTest {

    private static final Instant T = Instant.parse("2026-07-06T00:00:05Z");
    private static final LocalDate DAY = LocalDate.parse("2026-07-06");
    private static final Offer OFFER = new Offer(
            "1", "Steam", null,
            new BigDecimal("9.99"), new BigDecimal("29.99"),
            new BigDecimal("66.70"), null);
    private static final SectionSnapshot SNAPSHOT = new SectionSnapshot(
            SectionName.MEJORES_PROMOS, DAY, T, 5,
            List.of(new SectionItem("slug", "Title", OFFER,
                    new BigDecimal("66.70"), Map.of("savingsPct", "66.70"))));

    private final SectionStore store = mock(SectionStore.class);
    private final PublicSectionMapper mapper = new PublicSectionMapper();
    private final PublicSectionReadEndpoint endpoint =
            new PublicSectionReadEndpoint(store, mapper, GSON);

    @Test
    void returns_200_with_snapshot_on_valid_get() throws IOException {
        when(store.readLatest(SectionName.MEJORES_PROMOS)).thenReturn(Optional.of(SNAPSHOT));
        HttpExchange ex = exchange("GET",
                "/sections/mejores-promos", null, null, "");

        endpoint.handle(ex);

        assertThat(statusOf(ex)).isEqualTo(200);
        String body = bodyOf(ex);
        assertThat(body).contains("\"name\":\"mejores-promos\"");
        assertThat(body).contains("\"slug\":\"slug\"");
    }

    @Test
    void default_date_param_reads_latest() throws IOException {
        when(store.readLatest(SectionName.MEJORES_PROMOS)).thenReturn(Optional.of(SNAPSHOT));
        HttpExchange ex = exchange("GET",
                "/sections/mejores-promos", null, null, "");

        endpoint.handle(ex);

        org.mockito.Mockito.verify(store).readLatest(SectionName.MEJORES_PROMOS);
        org.mockito.Mockito.verify(store, org.mockito.Mockito.never())
                .read(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void date_live_reads_latest() throws IOException {
        when(store.readLatest(SectionName.MEJORES_PROMOS)).thenReturn(Optional.of(SNAPSHOT));
        HttpExchange ex = exchange("GET",
                "/sections/mejores-promos", "date=live", null, "");

        endpoint.handle(ex);

        org.mockito.Mockito.verify(store).readLatest(SectionName.MEJORES_PROMOS);
    }

    @Test
    void date_iso_reads_specific_day() throws IOException {
        when(store.read(SectionName.MEJORES_PROMOS, DAY)).thenReturn(Optional.of(SNAPSHOT));
        HttpExchange ex = exchange("GET",
                "/sections/mejores-promos", "date=2026-07-06", null, "");

        endpoint.handle(ex);

        org.mockito.Mockito.verify(store).read(SectionName.MEJORES_PROMOS, DAY);
    }

    @Test
    void returns_400_for_malformed_date() throws IOException {
        HttpExchange ex = exchange("GET",
                "/sections/mejores-promos", "date=not-a-date", null, "");
        endpoint.handle(ex);
        assertThat(statusOf(ex)).isEqualTo(GlobalExceptionHandler.SC_BAD_REQUEST);
        assertThat(bodyOf(ex)).contains("invalid date");
    }

    @Test
    void returns_400_for_unknown_slug() throws IOException {
        HttpExchange ex = exchange("GET", "/sections/nope", null, null, "");
        endpoint.handle(ex);
        assertThat(statusOf(ex)).isEqualTo(GlobalExceptionHandler.SC_BAD_REQUEST);
        assertThat(bodyOf(ex)).contains("unknown section slug");
    }

    @Test
    void returns_404_when_latest_snapshot_missing() throws IOException {
        when(store.readLatest(SectionName.MEJORES_PROMOS)).thenReturn(Optional.empty());
        HttpExchange ex = exchange("GET",
                "/sections/mejores-promos", null, null, "");
        endpoint.handle(ex);
        assertThat(statusOf(ex)).isEqualTo(404);
        assertThat(bodyOf(ex)).contains("\"code\":\"not_found\"");
    }

    @Test
    void returns_404_when_specific_day_snapshot_missing() throws IOException {
        when(store.read(SectionName.MEJORES_PROMOS, DAY)).thenReturn(Optional.empty());
        HttpExchange ex = exchange("GET",
                "/sections/mejores-promos", "date=2026-07-06", null, "");
        endpoint.handle(ex);
        assertThat(statusOf(ex)).isEqualTo(404);
        assertThat(bodyOf(ex)).contains("2026-07-06");
    }

    @Test
    void returns_400_on_post() throws IOException {
        HttpExchange ex = exchange("POST",
                "/sections/mejores-promos", null, null, "");
        endpoint.handle(ex);
        assertThat(statusOf(ex)).isEqualTo(GlobalExceptionHandler.SC_BAD_REQUEST);
    }
}
