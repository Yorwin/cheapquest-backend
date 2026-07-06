package com.cheapquest.backend.endpoint.sections;

import static com.cheapquest.backend.endpoint.sections.SectionsEndpointTestSupport.GSON;
import static com.cheapquest.backend.endpoint.sections.SectionsEndpointTestSupport.bodyOf;
import static com.cheapquest.backend.endpoint.sections.SectionsEndpointTestSupport.exchange;
import static com.cheapquest.backend.endpoint.sections.SectionsEndpointTestSupport.statusOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cheapquest.backend.domain.sections.SectionName;
import com.cheapquest.backend.domain.sections.SectionSnapshot;
import com.cheapquest.backend.exception.GlobalExceptionHandler;
import com.cheapquest.backend.mapper.PublicSectionMapper;
import com.cheapquest.backend.service.sections.SectionStore;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PublicSectionsListEndpointTest {

    private static final Instant T = Instant.parse("2026-07-06T00:00:05Z");
    private static final LocalDate DAY = LocalDate.parse("2026-07-06");

    private final SectionStore store = mock(SectionStore.class);
    private final PublicSectionMapper mapper = new PublicSectionMapper();
    private final PublicSectionsListEndpoint endpoint =
            new PublicSectionsListEndpoint(store, mapper, GSON);

    @Test
    void returns_200_with_section_list() throws IOException {
        Map<SectionName, SectionSnapshot> latest = new EnumMap<>(SectionName.class);
        latest.put(SectionName.MEJORES_PROMOS, new SectionSnapshot(
                SectionName.MEJORES_PROMOS, DAY, T, 5, List.of()));
        latest.put(SectionName.VINTAGE, new SectionSnapshot(
                SectionName.VINTAGE, DAY, T, 8, List.of()));
        when(store.readAllLatest()).thenReturn(latest);

        HttpExchange ex = exchange("GET", "/sections", null, null, "");
        endpoint.handle(ex);

        assertThat(statusOf(ex)).isEqualTo(200);
        String body = bodyOf(ex);
        assertThat(body).contains("\"status\":\"ok\"");
        assertThat(body).contains("\"count\":2");
        assertThat(body).contains("mejores-promos");
        assertThat(body).contains("vintage");
    }

    @Test
    void returns_200_with_empty_sections_when_catalog_uncomputed() throws IOException {
        when(store.readAllLatest()).thenReturn(new EnumMap<>(SectionName.class));

        HttpExchange ex = exchange("GET", "/sections", null, null, "");
        endpoint.handle(ex);

        assertThat(statusOf(ex)).isEqualTo(200);
        assertThat(bodyOf(ex)).contains("\"count\":0");
    }

    @Test
    void returns_400_on_post() throws IOException {
        HttpExchange ex = exchange("POST", "/sections", null, null, "");
        endpoint.handle(ex);
        assertThat(statusOf(ex)).isEqualTo(GlobalExceptionHandler.SC_BAD_REQUEST);
    }
}
