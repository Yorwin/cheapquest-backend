package com.cheapquest.backend.mapper;

import com.cheapquest.backend.domain.GameDeals;
import com.cheapquest.backend.domain.Offer;
import com.cheapquest.backend.dto.cheapshark.CheapSharkDealDto;
import com.cheapquest.backend.dto.cheapshark.CheapSharkGameDetailDto;
import com.cheapquest.backend.dto.cheapshark.CheapSharkGameInfoDto;
import com.cheapquest.backend.dto.cheapshark.CheapSharkGameSummaryDto;
import com.cheapquest.backend.dto.cheapshark.CheapSharkStoreDto;
import com.cheapquest.backend.util.StringNormalize;
import com.cheapquest.backend.util.StringUtils;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class CheapSharkMapper {

    private static final String DEAL_URL_BASE = "https://www.cheapshark.com/redirect?dealID=";
    private static final String UNKNOWN_STORE_PREFIX = "store-";
    private static final String STORE_ASSETS_BASE = "https://www.cheapshark.com";

    public Map<String, StoreInfo> toStoreIdToInfo(List<CheapSharkStoreDto> stores) {
        Map<String, StoreInfo> map = new HashMap<>();
        for (CheapSharkStoreDto s : stores) {
            String icon = s.images() != null ? toAbsoluteIconUrl(s.images().icon()) : null;
            map.put(s.storeId(), new StoreInfo(s.storeName(), icon));
        }
        return Map.copyOf(map);
    }

    public List<Offer> toDomainOffers(List<CheapSharkDealDto> deals, Map<String, StoreInfo> storeIdToInfo) {
        return deals.stream()
                .map(d -> toDomainOffer(d, storeIdToInfo))
                .toList();
    }

    public Offer toDomainOffer(CheapSharkDealDto deal, Map<String, StoreInfo> storeIdToInfo) {
        StoreInfo info = storeIdToInfo.get(deal.storeId());
        String storeName = info != null ? info.name() : UNKNOWN_STORE_PREFIX + deal.storeId();
        String storeIcon = info != null ? info.iconUrl() : null;
        return new Offer(
                deal.storeId(),
                storeName,
                storeIcon,
                toBigDecimal(deal.price()),
                toBigDecimal(deal.retailPrice()),
                toBigDecimal(deal.savings()),
                buildDealUrl(deal.dealId()),
                null);
    }

    public Optional<Offer> pickBestOffer(List<Offer> offers) {
        if (offers == null || offers.isEmpty()) {
            return Optional.empty();
        }
        return offers.stream()
                .max((a, b) -> a.savings().compareTo(b.savings()));
    }

    public String buildDealUrl(String dealId) {
        if (StringUtils.isBlank(dealId)) {
            return null;
        }
        try {
            String normalized = URLDecoder.decode(dealId, StandardCharsets.UTF_8);
            return DEAL_URL_BASE + URLEncoder.encode(normalized, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return DEAL_URL_BASE + dealId;
        }
    }

    public Optional<CheapSharkGameSummaryDto> pickExactMatch(
            List<CheapSharkGameSummaryDto> matches, String targetName) {
        if (matches == null || targetName == null) {
            return Optional.empty();
        }
        String normalized = StringNormalize.matchKey(targetName);
        return matches.stream()
                .filter(m -> m.external() != null && StringNormalize.matchKey(m.external()).equals(normalized))
                .findFirst();
    }

    public GameDeals toGameDeals(
            CheapSharkGameSummaryDto summary,
            CheapSharkGameDetailDto detail,
            Map<String, StoreInfo> storeIdToInfo,
            String searchTitle,
            Instant fetchedAt) {

        CheapSharkGameInfoDto info = detail.info();
        String title = info != null ? info.title() : summary.external();
        String thumb = info != null ? info.thumb() : summary.thumb();
        BigDecimal cheapestEver = detail.cheapestPriceEver() != null
                ? toBigDecimal(detail.cheapestPriceEver().price()) : null;

        List<Offer> all = (detail.deals() == null) ? List.of()
                : toDomainOffers(detail.deals(), storeIdToInfo);
        Offer best = pickBestOffer(all).orElse(null);
        List<Offer> remaining = (best == null) ? List.of()
                : all.stream().filter(o -> !o.equals(best)).toList();

        return new GameDeals(
                summary.gameId(),
                searchTitle,
                title,
                summary.internalName(),
                thumb,
                cheapestEver,
                all.size(),
                best,
                remaining,
                fetchedAt);
    }

    private static String toAbsoluteIconUrl(String relative) {
        if (StringUtils.isBlank(relative)) {
            return null;
        }
        if (relative.startsWith("http://") || relative.startsWith("https://")) {
            return relative;
        }
        return STORE_ASSETS_BASE + (relative.startsWith("/") ? relative : "/" + relative);
    }

    private static BigDecimal toBigDecimal(String s) {
        if (StringUtils.isBlank(s)) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
