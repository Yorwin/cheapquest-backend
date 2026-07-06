package com.cheapquest.backend.endpoint.sections;

import static com.cheapquest.backend.endpoint.sections.SectionsEndpointTestSupport.GSON;
import static com.cheapquest.backend.endpoint.sections.SectionsEndpointTestSupport.TOKEN;
import static com.cheapquest.backend.endpoint.sections.SectionsEndpointTestSupport.authHeader;
import static com.cheapquest.backend.endpoint.sections.SectionsEndpointTestSupport.bodyOf;
import static com.cheapquest.backend.endpoint.sections.SectionsEndpointTestSupport.exchange;
import static com.cheapquest.backend.endpoint.sections.SectionsEndpointTestSupport.statusOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cheapquest.backend.domain.sections.SectionName;
import com.cheapquest.backend.exception.GlobalExceptionHandler;
import com.cheapquest.backend.mapper.PublicSectionMapper;
import com.cheapquest.backend.service.sections.SectionsService;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdminOneSectionEndpointTest {

    private final SectionsService service = mock(SectionsService.class);
    private final PublicSectionMapper mapper = new PublicSectionMapper();
    private final AdminOneSectionEndpoint endpoint =
            new AdminOneSectionEndpoint(TOKEN, service, mapper, GSON);

    @Test
    void returns_200_with_report_on_known_slug() throws IOException {
        when(service.recompute(SectionName.MEJORES_PROMOS)).thenReturn(
                SectionsService.Report.completed(SectionName.MEJORES_PROMOS, 5, 1, 100L));
        HttpExchange ex = exchange("POST",
                "/admin/sections/mejores-promos", null, authHeader(), "");

        endpoint.handle(ex);

        assertThat(statusOf(ex)).isEqualTo(200);
        assertThat(bodyOf(ex)).contains("mejores-promos");
    }

    @Test
    void returns_400_for_unknown_slug() throws IOException {
        HttpExchange ex = exchange("POST",
                "/admin/sections/nope", null, authHeader(), "");
        endpoint.handle(ex);
        assertThat(statusOf(ex)).isEqualTo(GlobalExceptionHandler.SC_BAD_REQUEST);
        assertThat(bodyOf(ex)).contains("unknown section slug");
    }

    @Test
    void returns_401_when_token_missing() throws IOException {
        HttpExchange ex = exchange("POST",
                "/admin/sections/mejores-promos", null, null, "");
        endpoint.handle(ex);
        assertThat(statusOf(ex)).isEqualTo(GlobalExceptionHandler.SC_UNAUTHORIZED);
    }

    @Test
    void returns_409_when_lock_held() throws IOException {
        when(service.recompute(SectionName.MEJORES_PROMOS)).thenThrow(
                new com.cheapquest.backend.exception.ConflictException("busy"));
        HttpExchange ex = exchange("POST",
                "/admin/sections/mejores-promos", null, authHeader(), "");
        endpoint.handle(ex);
        assertThat(statusOf(ex)).isEqualTo(GlobalExceptionHandler.SC_CONFLICT);
    }

    @Test
    void returns_400_on_get() throws IOException {
        HttpExchange ex = exchange("GET",
                "/admin/sections/mejores-promos", null, authHeader(), "");
        endpoint.handle(ex);
        assertThat(statusOf(ex)).isEqualTo(GlobalExceptionHandler.SC_BAD_REQUEST);
    }
}
