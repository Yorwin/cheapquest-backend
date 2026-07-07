package com.cheapquest.backend.domain.sections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class GameViewTest {

    @Test
    void rejects_null_slug() {
        assertThatNullPointerException()
                .isThrownBy(() -> new GameView(null, "Title", null, null, null))
                .withMessageContaining("slug");
    }

    @Test
    void rejects_null_title() {
        assertThatNullPointerException()
                .isThrownBy(() -> new GameView("slug", null, null, null, null))
                .withMessageContaining("title");
    }

    @Test
    void accepts_null_cheapshark_and_rawg() {
        GameView g = new GameView("slug", "Title", null, null, null);
        assertThat(g.cheapshark()).isNull();
        assertThat(g.rawg()).isNull();
        assertThat(g.rawgDetails()).isNull();
    }

    @Test
    void carries_cheapshark_and_rawg_views() {
        CheapsharkView cs = new CheapsharkView(true, null, null, 0, java.util.List.of());
        RawgView rawg = new RawgView("2014-09-16", 96, 4.5, null, null, null, null, null);
        com.cheapquest.backend.domain.rawg.RawgDetails details =
                com.cheapquest.backend.fixtures.RawgDetailsFixtures.minimalDetails("portal-2", "Portal 2");
        GameView g = new GameView("portal-2", "Portal 2", cs, rawg, details);
        assertThat(g.cheapshark()).isSameAs(cs);
        assertThat(g.rawg()).isSameAs(rawg);
        assertThat(g.rawgDetails()).isSameAs(details);
    }
}
