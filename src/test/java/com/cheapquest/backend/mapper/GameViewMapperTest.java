package com.cheapquest.backend.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cheapquest.backend.domain.sections.GameView;
import com.cheapquest.backend.domain.sections.RawgView;
import com.cheapquest.backend.dto.firebase.GameDocumentDto;
import com.cheapquest.backend.dto.firebase.RawgBlock;
import com.cheapquest.backend.dto.firebase.RawgDocumentDto;
import com.cheapquest.backend.fixtures.GameDocumentDtoFixtures;
import com.cheapquest.backend.fixtures.RawgDocumentDtoFixtures;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GameViewMapperTest {

    private final GameViewMapper mapper = new GameViewMapper();

    @Test
    void toGameView_maps_synced_doc_to_filled_view() {
        GameDocumentDto doc = GameDocumentDtoFixtures.syncedDoc("far-cry-6", "Far Cry 6");
        GameView v = mapper.toGameView(doc);

        assertThat(v.slug()).isEqualTo("far-cry-6");
        assertThat(v.title()).isEqualTo("Far Cry 6");
        assertThat(v.cheapshark()).isNotNull();
        assertThat(v.cheapshark().synced()).isTrue();
        assertThat(v.cheapshark().bestDeal()).isNotNull();
        assertThat(v.cheapshark().bestDeal().storeName()).isEqualTo("Steam");
        assertThat(v.cheapshark().offers()).hasSize(1);
        assertThat(v.cheapshark().cheapestEver()).isEqualByComparingTo("0.99");
        assertThat(v.rawg()).isNotNull();
        assertThat(v.rawg().released()).isEqualTo("2021-10-06");
    }

    @Test
    void toGameView_with_null_cheapshark_returns_null_view() {
        GameDocumentDto doc = new GameDocumentDto(
                "title", "slug", "en", true, "2026-01-01T00:00:00Z",
                null, null, java.util.Map.of(), null);
        GameView v = mapper.toGameView(doc);
        assertThat(v.cheapshark()).isNull();
        assertThat(v.rawg()).isNull();
    }

    @Test
    void toGameView_with_empty_block_treats_as_unsynced() {
        GameDocumentDto doc = GameDocumentDtoFixtures.emptyDoc("portal", "Portal");
        GameView v = mapper.toGameView(doc);
        assertThat(v.cheapshark()).isNotNull();
        assertThat(v.cheapshark().synced()).isFalse();
        assertThat(v.cheapshark().bestDeal()).isNull();
        assertThat(v.cheapshark().offers()).isEmpty();
    }

    @Test
    void toGameView_with_rawg_block_but_null_data_returns_null_rawg() {
        RawgBlock rawgBlock = new RawgBlock(true, "2026-01-01T00:00:00Z", null);
        GameDocumentDto doc = new GameDocumentDto(
                "title", "slug", "en", true, "2026-01-01T00:00:00Z",
                null, rawgBlock, java.util.Map.of(), null);
        GameView v = mapper.toGameView(doc);
        assertThat(v.rawg()).isNull();
    }

    @Test
    void toGameView_rejects_null_doc() {
        assertThatThrownBy(() -> mapper.toGameView(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("doc");
    }

    @Test
    void toGameViews_walks_iterable() {
        GameDocumentDto a = GameDocumentDtoFixtures.emptyDoc("a", "A");
        GameDocumentDto b = GameDocumentDtoFixtures.emptyDoc("b", "B");
        List<GameView> vs = mapper.toGameViews(List.of(a, b));
        assertThat(vs).extracting(GameView::slug).containsExactly("a", "b");
    }

    @Test
    void toGameViews_with_empty_iterable_returns_empty_list() {
        List<GameView> vs = mapper.toGameViews(List.of());
        assertThat(vs).isEmpty();
    }

    @Test
    void toGameView_propagates_popularity_signals_into_RawgView() {
        RawgDocumentDto rawgData = RawgDocumentDtoFixtures.full("portal", "Portal")
                .build();
        RawgBlock rawgBlock = new RawgBlock(true, "2026-06-30T10:05:00Z", rawgData);
        GameDocumentDto doc = new GameDocumentDto(
                "Portal", "portal", "en", true, "2026-06-30T10:00:00Z",
                null, rawgBlock, java.util.Map.of(), null);
        GameView v = mapper.toGameView(doc);
        assertThat(v.rawg()).isNotNull();
        assertThat(v.rawg().ratingsCount()).isZero();
        assertThat(v.rawg().additionsCount()).isZero();
        assertThat(v.rawg().addedByStatus()).isEmpty();
        assertThat(v.rawg().reactions()).isEmpty();
        assertThat(v.rawg().suggestionsCount()).isZero();
    }

    @Test
    void toGameView_defensive_copies_popularity_maps() {
        java.util.HashMap<String, Integer> mutable = new java.util.HashMap<>();
        mutable.put("owned", 10);
        RawgDocumentDto rawgData = new RawgDocumentDto(
                "portal", "Portal", "Portal", "2007-10-10",
                null, null, null, null, null, null, null, null,
                0, 0, 0, 0,
                List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(),
                List.of(),
                false, null, null, List.of(), 0, 0, 0, null, 0, 0, 0, 0, 0,
                null, List.of(), null, List.of(), List.of(),
                mutable, Map.of(), 0,
                "2026-06-30T10:05:00Z");
        RawgBlock rawgBlock = new RawgBlock(true, "2026-06-30T10:05:00Z", rawgData);
        GameDocumentDto doc = new GameDocumentDto(
                "Portal", "portal", "en", true, "2026-06-30T10:00:00Z",
                null, rawgBlock, java.util.Map.of(), null);
        GameView v = mapper.toGameView(doc);
        mutable.clear();
        assertThat(v.rawg().addedByStatus()).containsEntry("owned", 10);
    }

    @Test
    void toGameView_propagates_offerCount_to_CheapsharkView() {
        GameDocumentDto doc = GameDocumentDtoFixtures.syncedDoc("far-cry-6", "Far Cry 6");
        GameView v = mapper.toGameView(doc);
        assertThat(v.cheapshark().offerCount()).isEqualTo(2);
    }

    @Test
    void toGameView_with_rawg_data_propagates_full_rawgDetails() {
        RawgDocumentDto rawgData = RawgDocumentDtoFixtures
                .full("portal", "Portal")
                .released("2007-10-10")
                .description("A puzzle game.")
                .build();
        RawgBlock rawgBlock = new RawgBlock(true, "2026-06-30T10:05:00Z", rawgData);
        GameDocumentDto doc = new GameDocumentDto(
                "Portal", "portal", "en", true, "2026-06-30T10:00:00Z",
                null, rawgBlock, java.util.Map.of(), null);
        GameView v = mapper.toGameView(doc);
        assertThat(v.rawgDetails()).isNotNull();
        assertThat(v.rawgDetails().slug()).isEqualTo("portal");
        assertThat(v.rawgDetails().name()).isEqualTo("Portal");
        assertThat(v.rawgDetails().released()).isEqualTo("2007-10-10");
        assertThat(v.rawgDetails().description()).isEqualTo("A puzzle game.");
    }

    @Test
    void toGameView_with_null_rawg_block_returns_null_rawgDetails() {
        GameDocumentDto doc = new GameDocumentDto(
                "title", "slug", "en", true, "2026-01-01T00:00:00Z",
                null, null, java.util.Map.of(), null);
        GameView v = mapper.toGameView(doc);
        assertThat(v.rawgDetails()).isNull();
    }

    @Test
    void toGameView_with_rawg_block_but_null_data_returns_null_rawgDetails() {
        RawgBlock rawgBlock = new RawgBlock(true, "2026-01-01T00:00:00Z", null);
        GameDocumentDto doc = new GameDocumentDto(
                "title", "slug", "en", true, "2026-01-01T00:00:00Z",
                null, rawgBlock, java.util.Map.of(), null);
        GameView v = mapper.toGameView(doc);
        assertThat(v.rawgDetails()).isNull();
    }

    @Test
    void toGameView_carries_both_rawgView_and_rawgDetails() {
        RawgDocumentDto rawgData = RawgDocumentDtoFixtures
                .full("portal", "Portal")
                .released("2007-10-10")
                .build();
        RawgBlock rawgBlock = new RawgBlock(true, "2026-06-30T10:05:00Z", rawgData);
        GameDocumentDto doc = new GameDocumentDto(
                "Portal", "portal", "en", true, "2026-06-30T10:00:00Z",
                null, rawgBlock, java.util.Map.of(), null);
        GameView v = mapper.toGameView(doc);
        assertThat(v.rawg()).isNotNull();
        assertThat(v.rawg().released()).isEqualTo("2007-10-10");
        assertThat(v.rawgDetails()).isNotNull();
        assertThat(v.rawgDetails().released()).isEqualTo("2007-10-10");
        assertThat(v.rawgDetails()).isNotSameAs(v.rawg());
    }
}
