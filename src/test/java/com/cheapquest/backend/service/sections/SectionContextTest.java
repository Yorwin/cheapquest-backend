package com.cheapquest.backend.service.sections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.cheapquest.backend.domain.sections.GameView;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SectionContextTest {

    private static final GameView GAME = new GameView("slug", "Title", null, null);

    @Test
    void accepts_null_catalog_and_returns_emptyList() {
        SectionContext ctx = new SectionContext(null);
        assertThat(ctx.catalog()).isEmpty();
    }

    @Test
    void catalog_is_defensive_copy() {
        List<GameView> mutable = new ArrayList<>();
        mutable.add(GAME);
        SectionContext ctx = new SectionContext(mutable);
        mutable.clear();
        assertThat(ctx.catalog()).hasSize(1);
    }

    @Test
    void catalog_is_unmodifiable() {
        SectionContext ctx = new SectionContext(new ArrayList<>(List.of(GAME)));
        assertThat(ctx.catalog()).isUnmodifiable();
    }

    @Test
    void empty_factory_returns_empty_catalog() {
        assertThat(SectionContext.empty().catalog()).isEmpty();
    }
}
