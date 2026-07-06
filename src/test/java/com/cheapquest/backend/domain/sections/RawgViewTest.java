package com.cheapquest.backend.domain.sections;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RawgViewTest {

    @Test
    void all_fields_nullable() {
        RawgView v = new RawgView(null, null, null, null, null, null, null, null);
        assertThat(v.released()).isNull();
        assertThat(v.metacritic()).isNull();
        assertThat(v.rating()).isNull();
        assertThat(v.ratingsCount()).isNull();
        assertThat(v.additionsCount()).isNull();
        assertThat(v.addedByStatus()).isEmpty();
        assertThat(v.reactions()).isEmpty();
        assertThat(v.suggestionsCount()).isNull();
    }

    @Test
    void carries_released_metacritic_rating() {
        RawgView v = new RawgView("2014-09-16", 96, 4.5, 120, 3, Map.of(), Map.of(), 8);
        assertThat(v.released()).isEqualTo("2014-09-16");
        assertThat(v.metacritic()).isEqualTo(96);
        assertThat(v.rating()).isEqualTo(4.5);
        assertThat(v.ratingsCount()).isEqualTo(120);
        assertThat(v.additionsCount()).isEqualTo(3);
        assertThat(v.suggestionsCount()).isEqualTo(8);
    }

    @Test
    void addedByStatus_is_defensive_copy() {
        java.util.HashMap<String, Integer> mutable = new java.util.HashMap<>();
        mutable.put("owned", 10);
        RawgView v = new RawgView(null, null, null, null, null, mutable, null, null);
        mutable.clear();
        assertThat(v.addedByStatus()).containsEntry("owned", 10);
    }
}
