package com.cheapquest.backend.fixtures;

import com.cheapquest.backend.dto.firebase.CheapsharkBlock;
import com.cheapquest.backend.dto.firebase.GameDocumentDto;
import com.cheapquest.backend.dto.firebase.LocaleBlock;
import com.cheapquest.backend.dto.firebase.OfferDto;
import com.cheapquest.backend.dto.firebase.RawgBlock;
import com.cheapquest.backend.dto.firebase.RawgDocumentDto;
import com.cheapquest.backend.dto.firebase.ValidationReportDto;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public final class GameDocumentDtoFixtures {

    private static final String ADDED_AT = "2026-06-30T10:00:00Z";
    private static final String FETCHED_AT = "2026-06-30T10:05:00Z";
    private static final String COMPLETE_TIMESTAMP = "2026-06-30T10:05:00Z";

    private GameDocumentDtoFixtures() {
    }

    public static GameDocumentDto emptyDoc(String slug, String title) {
        return new GameDocumentDto(
                title, slug, "en", true, ADDED_AT,
                CheapsharkBlock.empty(),
                RawgBlock.empty(),
                Map.of(
                        "es", LocaleBlock.unsynced(),
                        "en", LocaleBlock.unsynced(),
                        "fr", LocaleBlock.unsynced()),
                null);
    }

    public static GameDocumentDto syncedDoc(String slug, String title) {
        return new GameDocumentDto(
                title, slug, "en", true, ADDED_AT,
                new CheapsharkBlock(
                        true,
                        "82",
                        new BigDecimal("0.99"),
                        new OfferDto("1", "Steam", "https://steam.png",
                                new BigDecimal("1.99"), new BigDecimal("9.99"),
                                new BigDecimal("80.080"), "https://deal/1", null),
                        2,
                        List.of(new OfferDto("7", "GOG", null,
                                new BigDecimal("2.50"), new BigDecimal("9.99"),
                                new BigDecimal("74.977"), "https://deal/2", null)),
                        FETCHED_AT),
                new RawgBlock(true, FETCHED_AT,
                        new RawgDocumentDto(slug, title, title, "2021-10-06",
                                null, null, null, null, null, null, null, null,
                                0, 0, 0, 0,
                                List.of(), List.of(), List.of(), List.of(),
                                List.of(), List.of(), List.of(), List.of(),
                                List.of(),
                                false, null, null, List.of(), 0, 0, 0, null, 0, 0, 0, 0, 0,
                                null, List.of(), null, List.of(), List.of(),
                                Map.of(), Map.of(), 0,
                                FETCHED_AT)),
                Map.of(
                        "es", LocaleBlock.unsynced(),
                        "en", new LocaleBlock(true, FETCHED_AT, FETCHED_AT),
                        "fr", LocaleBlock.unsynced()),
                new ValidationReportDto("COMPLETE", List.of(),
                        COMPLETE_TIMESTAMP, null));
    }
}
