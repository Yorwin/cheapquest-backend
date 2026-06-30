package com.cheapquest.backend.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.cheapquest.backend.domain.GameDeals;
import com.cheapquest.backend.domain.Offer;
import com.cheapquest.backend.dto.cheapshark.CheapSharkCheapestPriceDto;
import com.cheapquest.backend.dto.cheapshark.CheapSharkDealDto;
import com.cheapquest.backend.dto.cheapshark.CheapSharkGameDetailDto;
import com.cheapquest.backend.dto.cheapshark.CheapSharkGameInfoDto;
import com.cheapquest.backend.dto.cheapshark.CheapSharkGameSummaryDto;
import com.cheapquest.backend.dto.cheapshark.CheapSharkStoreDto;
import com.cheapquest.backend.dto.cheapshark.CheapSharkStoreImagesDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CheapSharkMapperTest {

    private static final Instant T = Instant.parse("2026-06-30T10:00:00Z");

    private final CheapSharkMapper mapper = new CheapSharkMapper();

    @Test
    void toStoreIdToInfo_buildsMapWithNameAndIcon() {
        var s1 = new CheapSharkStoreDto(
                "Steam", 1, new CheapSharkStoreImagesDto("/b", "/l", "/img/stores/icons/0.png"), "1");
        var s2 = new CheapSharkStoreDto(
                "GOG", 1, new CheapSharkStoreImagesDto("/b2", "/l2", "/img/stores/icons/6.png"), "7");

        Map<String, StoreInfo> map = mapper.toStoreIdToInfo(List.of(s1, s2));

        assertThat(map).containsKey("1").containsKey("7");
        assertThat(map.get("1").name()).isEqualTo("Steam");
        assertThat(map.get("1").iconUrl())
                .isEqualTo("https://www.cheapshark.com/img/stores/icons/0.png");
        assertThat(map.get("7").name()).isEqualTo("GOG");
    }

    @Test
    void toStoreIdToInfo_handlesNullImages() {
        var s1 = new CheapSharkStoreDto("Steam", 1, null, "1");
        Map<String, StoreInfo> map = mapper.toStoreIdToInfo(List.of(s1));
        assertThat(map.get("1").iconUrl()).isNull();
    }

    @Test
    void toDomainOffers_convertsStringsToBigDecimal() {
        Map<String, StoreInfo> info = Map.of("1", new StoreInfo("Steam", "https://x/steam.png"));
        var deal = new CheapSharkDealDto("1", "d1", "4.99", "19.99", "75.037519");

        List<Offer> offers = mapper.toDomainOffers(List.of(deal), info);

        assertThat(offers).hasSize(1);
        Offer o = offers.get(0);
        assertThat(o.storeId()).isEqualTo("1");
        assertThat(o.storeName()).isEqualTo("Steam");
        assertThat(o.storeIconUrl()).isEqualTo("https://x/steam.png");
        assertThat(o.price()).isEqualByComparingTo("4.99");
        assertThat(o.retailPrice()).isEqualByComparingTo("19.99");
        assertThat(o.savings()).isEqualByComparingTo("75.037519");
        assertThat(o.dealUrl()).contains("cheapshark.com/redirect").contains("d1");
    }

    @Test
    void toDomainOffer_fallsBackWhenNameMissing() {
        var deal = new CheapSharkDealDto("99", "d99", "5.00", "10.00", "50.000000");
        Offer o = mapper.toDomainOffer(deal, Map.of());
        assertThat(o.storeName()).isEqualTo("store-99");
        assertThat(o.storeIconUrl()).isNull();
    }

    @Test
    void pickBestOffer_returnsHighestSavings() {
        Offer a = new Offer("1", "S1", null, bd("5"), bd("10"), bd("20.0"), null);
        Offer b = new Offer("2", "S2", null, bd("3"), bd("10"), bd("70.0"), null);
        Offer c = new Offer("3", "S3", null, bd("1"), bd("10"), bd("90.0"), null);

        Optional<Offer> best = mapper.pickBestOffer(List.of(a, b, c));

        assertThat(best).isPresent();
        assertThat(best.get().storeId()).isEqualTo("3");
    }

    @Test
    void pickBestOffer_emptyOnEmpty() {
        assertThat(mapper.pickBestOffer(List.of())).isEmpty();
        assertThat(mapper.pickBestOffer(null)).isEmpty();
    }

    @Test
    void buildDealUrl_encodesDealId() {
        String url = mapper.buildDealUrl("abc/def+ghi=");
        assertThat(url).startsWith("https://www.cheapshark.com/redirect?dealID=");
        assertThat(url).doesNotContain("/ghi");
    }

    @Test
    void buildDealUrl_handlesNullAndBlank() {
        assertThat(mapper.buildDealUrl(null)).isNull();
        assertThat(mapper.buildDealUrl("")).isNull();
        assertThat(mapper.buildDealUrl("   ")).isNull();
    }

    @Test
    void buildDealUrl_decodesThenEncodesUrlEncodedDealId() {
        String url = mapper.buildDealUrl("rPSCN3%2FJpoZ0SjQ%2FgDXvmCifMHiePHA9JjbUPcGPS3w%3D");
        assertThat(url).isEqualTo(
                "https://www.cheapshark.com/redirect?dealID=rPSCN3%2FJpoZ0SjQ%2FgDXvmCifMHiePHA9JjbUPcGPS3w%3D");
    }

    @Test
    void toAbsoluteIconUrl_passesAbsoluteUrlsThrough() {
        var s = new CheapSharkStoreDto("X", 1,
                new CheapSharkStoreImagesDto("/b", "/l", "https://cdn.example/icon.png"), "1");
        assertThat(mapper.toStoreIdToInfo(List.of(s)).get("1").iconUrl())
                .isEqualTo("https://cdn.example/icon.png");
    }

    @Test
    void toGameDeals_buildsAggregateWithAllFields() {
        Map<String, StoreInfo> info = Map.of(
                "1", new StoreInfo("Steam", "https://x/steam.png"),
                "7", new StoreInfo("GOG",   "https://x/gog.png"));
        var summary = new CheapSharkGameSummaryDto(
                "82", "400", "1.99", "dealSteam",
                "Portal", "PORTAL", "http://thumb/summary.jpg");
        var detail = new CheapSharkGameDetailDto(
                new CheapSharkGameInfoDto("Portal", "400", "http://thumb/info.jpg"),
                new CheapSharkCheapestPriceDto("0.99", 1498153168L),
                List.of(
                        new CheapSharkDealDto("1", "dealSteam", "1.99", "9.99", "80.080080"),
                        new CheapSharkDealDto("7", "dealGog",   "2.50", "9.99", "74.977498")));

        GameDeals gd = mapper.toGameDeals(summary, detail, info, "Portal", T);

        assertThat(gd.gameId()).isEqualTo("82");
        assertThat(gd.searchTitle()).isEqualTo("Portal");
        assertThat(gd.name()).isEqualTo("Portal");
        assertThat(gd.internalName()).isEqualTo("PORTAL");
        assertThat(gd.thumb()).isEqualTo("http://thumb/info.jpg");
        assertThat(gd.cheapestEver()).isEqualByComparingTo("0.99");
        assertThat(gd.offerCount()).isEqualTo(2);
        assertThat(gd.bestDeal()).isNotNull();
        assertThat(gd.bestDeal().storeId()).isEqualTo("1");
        assertThat(gd.bestDeal().savings()).isEqualByComparingTo("80.080080");
        assertThat(gd.offers()).hasSize(1);
        assertThat(gd.offers().get(0).storeId()).isEqualTo("7");
        assertThat(gd.offers()).doesNotContain(gd.bestDeal());
        assertThat(gd.fetchedAt()).isEqualTo(T);
    }

    @Test
    void toGameDeals_preservesSearchTitleEvenIfDifferentFromCanonical() {
        Map<String, StoreInfo> info = Map.of();
        var summary = new CheapSharkGameSummaryDto(
                "82", "400", "1.99", "x", "Portal", "PORTAL", "t");
        var detail = new CheapSharkGameDetailDto(
                new CheapSharkGameInfoDto("Portal", "400", "t"),
                null,
                List.of());

        GameDeals gd = mapper.toGameDeals(summary, detail, info, " portal ", T);

        assertThat(gd.searchTitle()).isEqualTo(" portal ");
        assertThat(gd.name()).isEqualTo("Portal");
    }

    @Test
    void toGameDeals_excludesBestFromOffers() {
        Map<String, StoreInfo> info = Map.of("1", new StoreInfo("Steam", null));
        var summary = new CheapSharkGameSummaryDto(
                "82", "400", "1.99", "x", "Portal", "PORTAL", "t");
        var detail = new CheapSharkGameDetailDto(
                new CheapSharkGameInfoDto("Portal", "400", "t"),
                new CheapSharkCheapestPriceDto("0.99", 0L),
                List.of(new CheapSharkDealDto("1", "sameDeal", "1.99", "9.99", "80.000000")));

        GameDeals gd = mapper.toGameDeals(summary, detail, info, "Portal", T);

        assertThat(gd.offerCount()).isEqualTo(1);
        assertThat(gd.bestDeal()).isNotNull();
        assertThat(gd.offers()).isEmpty();
    }

    @Test
    void toGameDeals_handlesNoDeals() {
        Map<String, StoreInfo> info = Map.of();
        var summary = new CheapSharkGameSummaryDto(
                "82", "400", "1.99", "x", "Portal", "PORTAL", "t");
        var detail = new CheapSharkGameDetailDto(
                new CheapSharkGameInfoDto("Portal", "400", "t"),
                null,
                List.of());

        GameDeals gd = mapper.toGameDeals(summary, detail, info, "Portal", T);

        assertThat(gd.offerCount()).isZero();
        assertThat(gd.bestDeal()).isNull();
        assertThat(gd.offers()).isEmpty();
        assertThat(gd.cheapestEver()).isNull();
    }

    @Test
    void pickExactMatch_caseInsensitive() {
        var m1 = new CheapSharkGameSummaryDto("1", null, null, null, "Portal", "PORTAL", null);
        var m2 = new CheapSharkGameSummaryDto("2", null, null, null, "PORTAL 2", "PORTAL2", null);

        Optional<CheapSharkGameSummaryDto> found = mapper.pickExactMatch(List.of(m1, m2), "portal");

        assertThat(found).isPresent();
        assertThat(found.get().gameId()).isEqualTo("1");
    }

    @Test
    void pickExactMatch_ignoresPunctuation() {
        var m1 = new CheapSharkGameSummaryDto("1", null, null, null, "Half-Life 2", "HALFLIFE2", null);
        var m2 = new CheapSharkGameSummaryDto("2", null, null, null, "Half Life 2: Episode One", "HALFLIFE2EPISODEONE", null);

        Optional<CheapSharkGameSummaryDto> found = mapper.pickExactMatch(
                List.of(m1, m2), "  half-life 2  ");

        assertThat(found).isPresent();
        assertThat(found.get().gameId()).isEqualTo("1");
    }

    @Test
    void pickExactMatch_emptyOnNoMatch() {
        var m1 = new CheapSharkGameSummaryDto("1", null, null, null, "Portal 2", "PORTAL2", null);

        Optional<CheapSharkGameSummaryDto> found = mapper.pickExactMatch(List.of(m1), "Portal");

        assertThat(found).isEmpty();
    }

    @Test
    void pickExactMatch_emptyOnNullInputs() {
        var m1 = new CheapSharkGameSummaryDto("1", null, null, null, "Portal", "PORTAL", null);
        assertThat(mapper.pickExactMatch(null, "Portal")).isEmpty();
        assertThat(mapper.pickExactMatch(List.of(m1), null)).isEmpty();
        assertThat(mapper.pickExactMatch(null, null)).isEmpty();
    }

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }
}
