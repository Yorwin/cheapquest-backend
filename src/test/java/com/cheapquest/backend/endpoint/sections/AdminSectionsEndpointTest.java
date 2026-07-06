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

class AdminSectionsEndpointTest {

    private final SectionsService service = mock(SectionsService.class);
    private final PublicSectionMapper mapper = new PublicSectionMapper();
    private final AdminSectionsEndpoint endpoint =
            new AdminSectionsEndpoint(TOKEN, service, mapper, GSON);

    @Test
    void returns_200_with_report_on_valid_post() throws IOException {
        when(service.recomputeAll()).thenReturn(List.of(
                SectionsService.Report.completed(SectionName.MEJORES_PROMOS, 5, 1, 100L)));
        HttpExchange ex = exchange("POST", "/admin/sections", null, authHeader(), "");

        endpoint.handle(ex);

        assertThat(statusOf(ex)).isEqualTo(200);
        String body = bodyOf(ex);
        assertThat(body).contains("\"status\":\"completed\"");
        assertThat(body).contains("\"processed\":1");
        assertThat(body).contains("\"failed\":0");
        assertThat(body).contains("mejores-promos");
    }

    @Test
    void returns_401_when_token_missing() throws IOException {
        HttpExchange ex = exchange("POST", "/admin/sections", null, null, "");
        endpoint.handle(ex);
        assertThat(statusOf(ex)).isEqualTo(GlobalExceptionHandler.SC_UNAUTHORIZED);
        assertThat(bodyOf(ex)).contains("\"code\":\"unauthorized\"");
    }

    @Test
    void returns_401_when_token_wrong() throws IOException {
        HttpExchange ex = exchange("POST", "/admin/sections", null, "Bearer wrong", "");
        endpoint.handle(ex);
        assertThat(statusOf(ex)).isEqualTo(GlobalExceptionHandler.SC_UNAUTHORIZED);
    }

    @Test
    void returns_409_when_lock_held() throws IOException {
        when(service.recomputeAll()).thenThrow(
                new com.cheapquest.backend.exception.ConflictException("busy"));
        HttpExchange ex = exchange("POST", "/admin/sections", null, authHeader(), "");
        endpoint.handle(ex);
        assertThat(statusOf(ex)).isEqualTo(GlobalExceptionHandler.SC_CONFLICT);
    }

    @Test
    void returns_400_on_get() throws IOException {
        HttpExchange ex = exchange("GET", "/admin/sections", null, authHeader(), "");
        endpoint.handle(ex);
        assertThat(statusOf(ex)).isEqualTo(GlobalExceptionHandler.SC_BAD_REQUEST);
    }
}
