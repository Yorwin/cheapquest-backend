package com.cheapquest.backend.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GameFixturesTest {

    @Test
    void all_has_three_games() {
        assertThat(GameFixtures.all())
                .hasSize(3)
                .extracting(HardcodedGame::name)
                .containsExactly("Portal", "Half-Life 2", "Stardew Valley");
    }

    @Test
    void names_are_non_blank() {
        for (HardcodedGame g : GameFixtures.all()) {
            assertThat(g.name()).isNotBlank();
        }
    }
}
