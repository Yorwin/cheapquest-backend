package com.cheapquest.backend.dto.firebase;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GameDocumentDtoTest {

    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    @Test
    void round_trips_through_gson() {
        GameDocumentDto original = new GameDocumentDto(
                "Far Cry 6",
                "far-cry-6",
                "en",
                true,
                "2026-06-30T10:00:00Z",
                new CheapsharkBlock(true, "5678", new BigDecimal("29.99"),
                        new OfferDto("1", "Steam", "https://steam.png",
                                new BigDecimal("29.99"), new BigDecimal("59.99"),
                                new BigDecimal("50.013"), "https://deal/1"),
                        3,
                        List.of(
                                new OfferDto("2", "GOG", null,
                                        new BigDecimal("32.99"), new BigDecimal("59.99"),
                                        new BigDecimal("45.00"), "https://deal/2"),
                                new OfferDto("3", "Epic", null,
                                        new BigDecimal("35.00"), new BigDecimal("59.99"),
                                        new BigDecimal("41.67"), "https://deal/3"))),
                new RawgBlock(true, 4321L, "2026-06-30T10:05:00Z",
                        Map.of("slug", "far-cry-6", "name", "Far Cry 6",
                                "released", "2021-10-06")),
                Map.of(
                        "es", new LocaleBlock(false, null),
                        "en", new LocaleBlock(true, "2026-06-30T10:00:00Z"),
                        "fr", new LocaleBlock(false, null)),
                new ValidationReportDto("PARTIAL", List.of("TRAILER"),
                        "2026-06-30T10:05:00Z", null));

        String json = gson.toJson(original);
        GameDocumentDto back = gson.fromJson(json, GameDocumentDto.class);

        assertThat(back).isEqualTo(original);
    }

    @Test
    void minimal_bootstrap_doc_round_trips() {
        GameDocumentDto original = new GameDocumentDto(
                "Portal", "portal", "en", true, "2026-06-30T10:00:00Z",
                new CheapsharkBlock(false, null, null, null, 0, List.of()),
                new RawgBlock(false, null, null, null),
                Map.of(
                        "es", new LocaleBlock(false, null),
                        "en", new LocaleBlock(false, null),
                        "fr", new LocaleBlock(false, null)),
                null);

        String json = gson.toJson(original);
        GameDocumentDto back = gson.fromJson(json, GameDocumentDto.class);

        assertThat(back).isEqualTo(original);
    }
}
