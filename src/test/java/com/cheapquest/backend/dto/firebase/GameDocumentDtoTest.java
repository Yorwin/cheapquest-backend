package com.cheapquest.backend.dto.firebase;

import static org.assertj.core.api.Assertions.assertThat;

import com.cheapquest.backend.fixtures.GameDocumentDtoFixtures;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;

class GameDocumentDtoTest {

    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    @Test
    void round_trips_through_gson() {
        GameDocumentDto original = GameDocumentDtoFixtures.syncedDoc("far-cry-6", "Far Cry 6");

        String json = gson.toJson(original);
        GameDocumentDto back = gson.fromJson(json, GameDocumentDto.class);

        assertThat(back).isEqualTo(original);
    }

    @Test
    void minimal_bootstrap_doc_round_trips() {
        GameDocumentDto original = GameDocumentDtoFixtures.emptyDoc("portal", "Portal");

        String json = gson.toJson(original);
        GameDocumentDto back = gson.fromJson(json, GameDocumentDto.class);

        assertThat(back).isEqualTo(original);
    }
}
