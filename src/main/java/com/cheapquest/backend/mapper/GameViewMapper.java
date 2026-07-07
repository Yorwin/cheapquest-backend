package com.cheapquest.backend.mapper;

import com.cheapquest.backend.domain.Offer;
import com.cheapquest.backend.domain.rawg.RawgDetails;
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
 * <p>The mapping narrows the {@code GameDocumentDto} to what
 * the section pipeline needs: {@code slug, title, cheapshark,
 * rawg, rawgDetails}. {@code rawg} is the thin projection
 * (popularity counters + release date) the score formulas
 * read; {@code rawgDetails} is the full {@link RawgDetails}
 * propagated to the {@code SectionItem} so the public API can
 * surface the description, genres, tags, platforms and the
 * rest of the RAWG payload without a re-fetch.
 */
public final class GameViewMapper {

    private final RawgDetailsMapper rawgDetailsMapper;

    public GameViewMapper() {
        this(new RawgDetailsMapper());
    }

    public GameViewMapper(RawgDetailsMapper rawgDetailsMapper) {
        this.rawgDetailsMapper = Objects.requireNonNull(rawgDetailsMapper, "rawgDetailsMapper");
    }

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
                toRawgView(doc.rawg()),
                toRawgDetails(doc.rawg()));
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

    private RawgDetails toRawgDetails(RawgBlock block) {
        if (block == null) {
            return null;
        }
        RawgDocumentDto data = block.data();
        if (data == null) {
            return null;
        }
        return rawgDetailsMapper.toDomain(data);
    }
}
