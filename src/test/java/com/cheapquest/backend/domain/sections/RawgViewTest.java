package com.cheapquest.backend.domain.sections;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RawgViewTest {

    @Test
    void all_fields_nullable() {
        RawgView v = new RawgView(null, null, null);
        assertThat(v.released()).isNull();
        assertThat(v.metacritic()).isNull();
        assertThat(v.rating()).isNull();
    }

    @Test
    void carries_released_metacritic_rating() {
        RawgView v = new RawgView("2014-09-16", 96, 4.5);
        assertThat(v.released()).isEqualTo("2014-09-16");
        assertThat(v.metacritic()).isEqualTo(96);
        assertThat(v.rating()).isEqualTo(4.5);
    }
}
