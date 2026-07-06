package com.cheapquest.backend.mapper;

import com.cheapquest.backend.domain.Offer;
import com.cheapquest.backend.domain.sections.CheapsharkView;
import com.cheapquest.backend.domain.sections.GameView;
import com.cheapquest.backend.domain.sections.RawgView;
import com.cheapquest.backend.dto.firebase.CheapsharkBlock;
import com.cheapquest.backend.dto.firebase.GameDocumentDto;
import com.cheapquest.backend.dto.firebase.OfferDto;
import com.cheapquest.backend.dto.firebase.RawgBlock;
import com.cheapquest.backend.dto.firebase.RawgDocumentDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Projects hydrated game documents into the
 * {@link GameView} shape that the section builders consume.
 * One mapper instance can be reused across the whole catalog
 * walk; the methods are stateless and thread-safe.
 *
 * <p>The mapping is intentionally lossy: a
 * {@code GameDocumentDto} carries a lot of nested state
 * (locales, validation report, screenshot URLs, ...) that no
 * section needs, so the view narrows to
 * {@code slug, title, cheapshark, rawg}. New fields land on
 * the view (and the mapper) only when a builder asks for
 * them.
 */
public final class GameViewMapper {

    public List<GameView> toGameViews(Iterable<GameDocumentDto> docs) {
        Objects.requireNonNull(docs, "docs");
        List<GameView> out = new ArrayList<>();
        for (GameDocumentDto d : docs) {
            out.add(toGameView(d));
        }
        return List.copyOf(out);
    }

    public GameView toGameView(GameDocumentDto doc) {
        Objects.requireNonNull(doc, "doc");
        return new GameView(
                doc.slug(),
                doc.title(),
                toCheapsharkView(doc.cheapshark()),
                toRawgView(doc.rawg()));
    }

    private static CheapsharkView toCheapsharkView(CheapsharkBlock block) {
        if (block == null) {
            return null;
        }
        boolean synced = Boolean.TRUE.equals(block.synced());
        Offer best = block.bestDeal() == null ? null : OfferConverter.toDomain(block.bestDeal());
        Integer offerCount = block.offerCount();
        List<Offer> offers = new ArrayList<>();
        if (block.deals() != null) {
            for (OfferDto d : block.deals()) {
                offers.add(OfferConverter.toDomain(d));
            }
        }
        return new CheapsharkView(synced, best, block.cheapestEver(), offerCount, offers);
    }

    private static RawgView toRawgView(RawgBlock block) {
        if (block == null) {
            return null;
        }
        RawgDocumentDto data = block.data();
        if (data == null) {
            return null;
        }
        return new RawgView(
                data.released(),
                data.metacritic(),
                data.rating(),
                data.ratingsCount(),
                data.additionsCount(),
                data.addedByStatus(),
                data.reactions(),
                data.suggestionsCount());
    }
}
