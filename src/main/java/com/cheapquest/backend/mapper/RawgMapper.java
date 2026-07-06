package com.cheapquest.backend.mapper;

import com.cheapquest.backend.domain.rawg.DeveloperSummary;
import com.cheapquest.backend.domain.rawg.PublisherSummary;
import com.cheapquest.backend.domain.rawg.RawgClip;
import com.cheapquest.backend.domain.rawg.RawgCreator;
import com.cheapquest.backend.domain.rawg.RawgDetails;
import com.cheapquest.backend.domain.rawg.RawgDlc;
import com.cheapquest.backend.domain.rawg.RawgEsrbRating;
import com.cheapquest.backend.domain.rawg.RawgGenre;
import com.cheapquest.backend.domain.rawg.RawgPlatform;
import com.cheapquest.backend.domain.rawg.RawgRating;
import com.cheapquest.backend.domain.rawg.RawgScreenshot;
import com.cheapquest.backend.domain.rawg.RawgStoreEntry;
import com.cheapquest.backend.domain.rawg.RawgStoreRef;
import com.cheapquest.backend.domain.rawg.RawgTag;
import com.cheapquest.backend.dto.rawg.RawgClipDto;
import com.cheapquest.backend.dto.rawg.RawgCreatorDto;
import com.cheapquest.backend.dto.rawg.RawgDeveloperDto;
import com.cheapquest.backend.dto.rawg.RawgEsrbRatingDto;
import com.cheapquest.backend.dto.rawg.RawgGameDto;
import com.cheapquest.backend.dto.rawg.RawgGenreDto;
import com.cheapquest.backend.dto.rawg.RawgMovieDto;
import com.cheapquest.backend.dto.rawg.RawgPlatformEntryDto;
import com.cheapquest.backend.dto.rawg.RawgPublisherDto;
import com.cheapquest.backend.dto.rawg.RawgRatingDto;
import com.cheapquest.backend.dto.rawg.RawgScreenshotDto;
import com.cheapquest.backend.dto.rawg.RawgStoreEntryDto;
import com.cheapquest.backend.dto.rawg.RawgStoreRefDto;
import com.cheapquest.backend.dto.rawg.RawgTagDto;
import com.cheapquest.backend.util.StringNormalize;
import com.cheapquest.backend.util.StringUtils;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RawgMapper {

    private static final Logger log = LoggerFactory.getLogger(RawgMapper.class);
    private static final int LEVENSHTEIN_MAX_DISTANCE = 5;

    public Optional<RawgGameDto> pickExactMatch(List<RawgGameDto> matches, String targetName) {
        if (matches == null || targetName == null) {
            return Optional.empty();
        }
        String normalized = StringNormalize.matchKey(targetName);
        return matches.stream()
                .filter(m -> m.name() != null && StringNormalize.matchKey(m.name()).equals(normalized))
                .findFirst();
    }

    public Optional<RawgGameDto> pickClosestByLevenshtein(List<RawgGameDto> matches, String targetName) {
        if (matches == null || matches.isEmpty() || targetName == null) {
            return Optional.empty();
        }
        String normalized = StringNormalize.matchKey(targetName);
        RawgGameDto best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (RawgGameDto m : matches) {
            if (m.name() == null) {
                continue;
            }
            int distance = levenshtein(normalized, StringNormalize.matchKey(m.name()));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = m;
            }
        }
        if (best == null || bestDistance > LEVENSHTEIN_MAX_DISTANCE) {
            return Optional.empty();
        }
        if (bestDistance > 0) {
            log.warn("rawg_levenshtein_fallback target=\"{}\" picked=\"{}\" distance={}",
                    targetName, best.name(), bestDistance);
        }
        return Optional.of(best);
    }

    public String pickTrailerUrl(RawgGameDto detail, List<RawgMovieDto> movies) {
        if (detail != null && detail.clip() != null) {
            String video = detail.clip().video();
            if (!StringUtils.isBlank(video)) {
                return video;
            }
            String clip = detail.clip().clip();
            if (!StringUtils.isBlank(clip)) {
                return clip;
            }
        }
        if (movies != null && !movies.isEmpty()) {
            RawgMovieDto first = movies.get(0);
            if (first.data() != null) {
                String max = first.data().max();
                if (!StringUtils.isBlank(max)) {
                    return max;
                }
                String full = first.data().full();
                if (!StringUtils.isBlank(full)) {
                    return full;
                }
            }
        }
        return null;
    }

    public List<RawgDlc> toDlcSummaries(List<RawgGameDto> additions) {
        if (additions == null) {
            return List.of();
        }
        return additions.stream()
                .map(a -> new RawgDlc(a.slug(), a.name(), a.released(), a.backgroundImage()))
                .toList();
    }

    public List<RawgCreator> toCreators(List<RawgCreatorDto> creators) {
        if (creators == null) {
            return List.of();
        }
        return creators.stream()
                .map(c -> new RawgCreator(c.name(), c.slug(), c.position()))
                .toList();
    }

    public List<String> toScreenshotUrls(List<RawgScreenshotDto> screenshots) {
        if (screenshots == null) {
            return List.of();
        }
        return screenshots.stream()
                .map(RawgScreenshotDto::image)
                .filter(Objects::nonNull)
                .toList();
    }

    public List<RawgGenre> toGenres(List<RawgGenreDto> genres) {
        if (genres == null) {
            return List.of();
        }
        return genres.stream()
                .map(g -> new RawgGenre(g.id(), g.name(), g.slug()))
                .toList();
    }

    public List<RawgTag> toTags(List<RawgTagDto> tags) {
        if (tags == null) {
            return List.of();
        }
        return tags.stream()
                .map(t -> new RawgTag(t.id(), t.name(), t.slug(), t.language()))
                .toList();
    }

    public List<RawgPlatform> toPlatforms(List<RawgPlatformEntryDto> entries) {
        if (entries == null) {
            return List.of();
        }
        return entries.stream()
                .map(RawgPlatformEntryDto::platform)
                .filter(Objects::nonNull)
                .map(p -> new RawgPlatform(p.id(), p.name(), p.slug()))
                .toList();
    }

    public List<DeveloperSummary> toDeveloperSummaries(List<RawgDeveloperDto> developers) {
        if (developers == null) {
            return List.of();
        }
        return developers.stream()
                .map(d -> new DeveloperSummary(d.name(), d.slug()))
                .toList();
    }

    public List<PublisherSummary> toPublisherSummaries(List<RawgPublisherDto> publishers) {
        if (publishers == null) {
            return List.of();
        }
        return publishers.stream()
                .map(p -> new PublisherSummary(p.name(), p.slug()))
                .toList();
    }

    public List<RawgRating> toRatings(List<RawgRatingDto> ratings) {
        if (ratings == null) {
            return List.of();
        }
        return ratings.stream()
                .map(r -> new RawgRating(r.id(), r.title(), r.count(), r.percent()))
                .toList();
    }

    public RawgClip toClip(RawgClipDto clip) {
        if (clip == null) {
            return null;
        }
        return new RawgClip(clip.clip(), clip.video(), clip.videoId(), clip.embedId());
    }

    public RawgEsrbRating toEsrbRating(RawgEsrbRatingDto esrb) {
        if (esrb == null) {
            return null;
        }
        return new RawgEsrbRating(esrb.id(), esrb.name(), esrb.slug(), esrb.nameEn());
    }

    public RawgStoreRef toStoreRef(RawgStoreRefDto ref) {
        if (ref == null) {
            return null;
        }
        return new RawgStoreRef(ref.id(), ref.name(), ref.slug(), ref.domain(),
                ref.gamesCount(), ref.imageBackground());
    }

    public List<RawgStoreEntry> toStoreEntries(List<RawgStoreEntryDto> stores) {
        if (stores == null) {
            return List.of();
        }
        return stores.stream()
                .map(s -> new RawgStoreEntry(s.id(), s.url(), toStoreRef(s.store())))
                .toList();
    }

    public List<RawgScreenshot> toScreenshots(List<RawgScreenshotDto> screenshots) {
        if (screenshots == null) {
            return List.of();
        }
        return screenshots.stream()
                .map(s -> new RawgScreenshot(s.id(), s.image(), s.width(), s.height(), s.isDeleted()))
                .toList();
    }

    public RawgDetails toDetails(
            RawgGameDto detail,
            List<RawgMovieDto> movies,
            List<RawgScreenshotDto> screenshots,
            List<RawgGameDto> additions,
            List<RawgCreatorDto> creators,
            java.time.Instant fetchedAt) {

        Objects.requireNonNull(detail, "detail");
        return new RawgDetails(
                detail.slug(),
                detail.name(),
                detail.nameOriginal(),
                detail.released(),
                detail.description(),
                detail.descriptionRaw(),
                detail.backgroundImage(),
                pickTrailerUrl(detail, movies),
                detail.website(),
                detail.rating(),
                detail.ratingTop(),
                detail.metacritic(),
                detail.additionsCount(),
                detail.creatorsCount(),
                detail.moviesCount(),
                detail.screenshotsCount(),
                toDeveloperSummaries(detail.developers()),
                toPublisherSummaries(detail.publishers()),
                toGenres(detail.genres()),
                toTags(detail.tags()),
                toPlatforms(detail.platforms()),
                toPlatforms(detail.parentPlatforms()),
                toDlcSummaries(additions),
                toCreators(creators),
                toScreenshotUrls(screenshots),
                detail.tba(),
                detail.updated(),
                detail.backgroundImageAdditional(),
                toRatings(detail.ratings()),
                detail.ratingsCount(),
                detail.reviewsCount(),
                detail.reviewsTextCount(),
                detail.metacriticUrl(),
                detail.playtime(),
                detail.parentsCount(),
                detail.gameSeriesCount(),
                detail.achievementsCount(),
                detail.parentAchievementsCount(),
                toClip(detail.clip()),
                detail.alternativeNames() == null ? List.of() : List.copyOf(detail.alternativeNames()),
                toEsrbRating(detail.esrbRating()),
                toStoreEntries(detail.stores()),
                toScreenshots(detail.shortScreenshots()),
                detail.addedByStatus() == null ? Map.of() : Map.copyOf(detail.addedByStatus()),
                detail.reactions() == null ? Map.of() : Map.copyOf(detail.reactions()),
                detail.suggestionsCount(),
                fetchedAt);
    }

    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length()];
    }

    /**
     * Three-way union of the RAWG responses the hydration pipeline collects
     * for a single game: the search-summary DTO (picked from
     * {@code /games?search=NAME}) and the two full-payload DTOs returned by
     * {@code /games/{slug}} (mandatory) and {@code /games/{id}} (best-effort).
     *
     * <p>Precedence for scalar fields is {@code detailsById > detailsBySlug >
     * search}: the id-based call is the canonical source, the slug-based call
     * is its near-equivalent (kept for the early-phase hydration flow), and
     * the search result is a sparse fallback. A scalar that the preferred
     * source left blank falls through to the next source; a scalar that two
     * non-blank sources disagree on resolves to the more authoritative one
     * and emits {@code WARN rawg_field_mismatch} with the winning source.
     *
     * <p>Lists with an {@code id} field are deduplicated by that key, in the
     * same precedence order. Lists of plain strings are deduplicated by
     * {@link String#equals}. Counts are {@code max} across the three sources
     * without a WARN — counters are "noise-permissive" because RAWG can lag
     * by a few minutes between sub-resource calls and the parent
     * {@code /games/{id}} response.
     *
     * <p>{@code detailsById} may be {@code null} when the best-effort
     * id-based call failed (5xx, 404, rate-limit). The merge then degenerates
     * to a 2-way merge of {@code (search, detailsBySlug)}; the call sites in
     * {@code RawgAggregationService} also short-circuit to the slug-based
     * DTO in that case, so a 2-way merge is mostly useful for unit tests.
     */
    public RawgGameDto mergeSearchAndDetails(
            RawgGameDto search,
            RawgGameDto detailsBySlug,
            RawgGameDto detailsById) {
        Objects.requireNonNull(search, "search");
        Objects.requireNonNull(detailsBySlug, "detailsBySlug");

        if (detailsById != null
                && detailsById.id() != 0
                && detailsBySlug.id() != 0
                && detailsById.id() != detailsBySlug.id()) {
            log.warn("rawg_id_mismatch idById={} idBySlug={} - keeping id-based",
                    detailsById.id(), detailsBySlug.id());
        }
        if (detailsById != null
                && detailsById.id() != 0
                && search.id() != 0
                && detailsById.id() != search.id()) {
            log.warn("rawg_id_mismatch idById={} idBySearch={} - keeping id-based",
                    detailsById.id(), search.id());
        }

        int id = pickInt(search.id(), detailsBySlug.id(), safeInt(detailsById, RawgGameDto::id), "id");
        String slug = pickString(search.slug(), detailsBySlug.slug(),
                safe(detailsById, RawgGameDto::slug), "slug");
        String name = pickString(search.name(), detailsBySlug.name(),
                safe(detailsById, RawgGameDto::name), "name");
        String nameOriginal = pickString(search.nameOriginal(), detailsBySlug.nameOriginal(),
                safe(detailsById, RawgGameDto::nameOriginal), "nameOriginal");
        String description = pickString(search.description(), detailsBySlug.description(),
                safe(detailsById, RawgGameDto::description), "description");
        String descriptionRaw = pickString(search.descriptionRaw(), detailsBySlug.descriptionRaw(),
                safe(detailsById, RawgGameDto::descriptionRaw), "descriptionRaw");
        String released = pickString(search.released(), detailsBySlug.released(),
                safe(detailsById, RawgGameDto::released), "released");
        boolean tba = pickBoolean(search.tba(), detailsBySlug.tba(),
                detailsById != null && detailsById.tba());
        String updated = pickString(search.updated(), detailsBySlug.updated(),
                safe(detailsById, RawgGameDto::updated), "updated");
        String backgroundImage = pickString(search.backgroundImage(), detailsBySlug.backgroundImage(),
                safe(detailsById, RawgGameDto::backgroundImage), "backgroundImage");
        String backgroundImageAdditional = pickString(search.backgroundImageAdditional(),
                detailsBySlug.backgroundImageAdditional(),
                safe(detailsById, RawgGameDto::backgroundImageAdditional),
                "backgroundImageAdditional");
        String website = pickString(search.website(), detailsBySlug.website(),
                safe(detailsById, RawgGameDto::website), "website");
        double rating = pickDouble(search.rating(), detailsBySlug.rating(),
                safeDouble(detailsById, RawgGameDto::rating), "rating");
        int ratingTop = pickInt(search.ratingTop(), detailsBySlug.ratingTop(),
                safeInt(detailsById, RawgGameDto::ratingTop), "ratingTop");
        List<RawgRatingDto> ratings = unionById(
                search.ratings(), detailsBySlug.ratings(),
                safe(detailsById, RawgGameDto::ratings), RawgRatingDto::id);
        int ratingsCount = pickIntCount(search.ratingsCount(), detailsBySlug.ratingsCount(),
                safeInt(detailsById, RawgGameDto::ratingsCount));
        int reviewsCount = pickIntCount(search.reviewsCount(), detailsBySlug.reviewsCount(),
                safeInt(detailsById, RawgGameDto::reviewsCount));
        int reviewsTextCount = pickIntCount(search.reviewsTextCount(), detailsBySlug.reviewsTextCount(),
                safeInt(detailsById, RawgGameDto::reviewsTextCount));
        Integer metacritic = pickIntObject(search.metacritic(), detailsBySlug.metacritic(),
                safe(detailsById, RawgGameDto::metacritic), "metacritic");
        String metacriticUrl = pickString(search.metacriticUrl(), detailsBySlug.metacriticUrl(),
                safe(detailsById, RawgGameDto::metacriticUrl), "metacriticUrl");
        int playtime = pickInt(search.playtime(), detailsBySlug.playtime(),
                safeInt(detailsById, RawgGameDto::playtime), "playtime");
        int parentsCount = pickIntCount(search.parentsCount(), detailsBySlug.parentsCount(),
                safeInt(detailsById, RawgGameDto::parentsCount));
        int additionsCount = pickIntCount(search.additionsCount(), detailsBySlug.additionsCount(),
                safeInt(detailsById, RawgGameDto::additionsCount));
        int gameSeriesCount = pickIntCount(search.gameSeriesCount(), detailsBySlug.gameSeriesCount(),
                safeInt(detailsById, RawgGameDto::gameSeriesCount));
        int screenshotsCount = pickIntCount(search.screenshotsCount(), detailsBySlug.screenshotsCount(),
                safeInt(detailsById, RawgGameDto::screenshotsCount));
        int moviesCount = pickIntCount(search.moviesCount(), detailsBySlug.moviesCount(),
                safeInt(detailsById, RawgGameDto::moviesCount));
        int creatorsCount = pickIntCount(search.creatorsCount(), detailsBySlug.creatorsCount(),
                safeInt(detailsById, RawgGameDto::creatorsCount));
        int achievementsCount = pickIntCount(search.achievementsCount(), detailsBySlug.achievementsCount(),
                safeInt(detailsById, RawgGameDto::achievementsCount));
        int parentAchievementsCount = pickIntCount(search.parentAchievementsCount(), detailsBySlug.parentAchievementsCount(),
                safeInt(detailsById, RawgGameDto::parentAchievementsCount));
        RawgClipDto clip = pickNested(search.clip(), detailsBySlug.clip(),
                detailsById != null ? detailsById.clip() : null, "clip");
        List<String> alternativeNames = unionStrings(search.alternativeNames(),
                detailsBySlug.alternativeNames(),
                safe(detailsById, RawgGameDto::alternativeNames));
        List<RawgDeveloperDto> developers = unionById(
                search.developers(), detailsBySlug.developers(),
                safe(detailsById, RawgGameDto::developers), RawgDeveloperDto::id);
        List<RawgPublisherDto> publishers = unionById(
                search.publishers(), detailsBySlug.publishers(),
                safe(detailsById, RawgGameDto::publishers), RawgPublisherDto::id);
        List<RawgGenreDto> genres = unionById(
                search.genres(), detailsBySlug.genres(),
                safe(detailsById, RawgGameDto::genres), RawgGenreDto::id);
        List<RawgTagDto> tags = unionById(
                search.tags(), detailsBySlug.tags(),
                safe(detailsById, RawgGameDto::tags), RawgTagDto::id);
        List<RawgPlatformEntryDto> platforms = unionPlatforms(
                search.platforms(), detailsBySlug.platforms(),
                safe(detailsById, RawgGameDto::platforms));
        List<RawgPlatformEntryDto> parentPlatforms = unionPlatforms(
                search.parentPlatforms(), detailsBySlug.parentPlatforms(),
                safe(detailsById, RawgGameDto::parentPlatforms));
        RawgEsrbRatingDto esrbRating = pickNested(search.esrbRating(), detailsBySlug.esrbRating(),
                detailsById != null ? detailsById.esrbRating() : null, "esrbRating");
        List<RawgStoreEntryDto> stores = unionByStoreId(
                search.stores(), detailsBySlug.stores(),
                safe(detailsById, RawgGameDto::stores));
        List<RawgScreenshotDto> shortScreenshots = unionShortScreenshots(
                search.shortScreenshots(), detailsBySlug.shortScreenshots(),
                safe(detailsById, RawgGameDto::shortScreenshots));
        Map<String, Integer> addedByStatus = mergeIntMap(
                search.addedByStatus(), detailsBySlug.addedByStatus(),
                safe(detailsById, RawgGameDto::addedByStatus));
        Map<String, Integer> reactions = mergeIntMap(
                search.reactions(), detailsBySlug.reactions(),
                safe(detailsById, RawgGameDto::reactions));
        int suggestionsCount = pickIntCount(search.suggestionsCount(),
                detailsBySlug.suggestionsCount(),
                safeInt(detailsById, RawgGameDto::suggestionsCount));

        return new RawgGameDto(
                id, slug, name, nameOriginal, description, descriptionRaw,
                released, tba, updated, backgroundImage, backgroundImageAdditional, website,
                rating, ratingTop, ratings, ratingsCount, reviewsCount, reviewsTextCount,
                metacritic, metacriticUrl, playtime, parentsCount, additionsCount, gameSeriesCount,
                screenshotsCount, moviesCount, creatorsCount, achievementsCount, parentAchievementsCount,
                clip, alternativeNames, developers, publishers, genres, tags,
                platforms, parentPlatforms, esrbRating, stores, shortScreenshots,
                addedByStatus, reactions, suggestionsCount);
    }

    private static <T> T safe(RawgGameDto dto, Function<RawgGameDto, T> getter) {
        return dto == null ? null : getter.apply(dto);
    }

    private static int safeInt(RawgGameDto dto, ToIntFunction<RawgGameDto> getter) {
        return dto == null ? 0 : getter.applyAsInt(dto);
    }

    private static double safeDouble(RawgGameDto dto, ToDoubleFunction<RawgGameDto> getter) {
        return dto == null ? 0.0 : getter.applyAsDouble(dto);
    }

    private static String pickString(String searchVal, String slugVal, String idVal, String fieldName) {
        String result = firstNonBlank(idVal, slugVal, searchVal);
        if (isNonBlank(idVal) && isNonBlank(slugVal) && !idVal.equals(slugVal)) {
            String winningSource = result == idVal ? "id" : "slug";
            log.warn("rawg_field_mismatch field={} winningSource={} idValue=\"{}\" slugValue=\"{}\"",
                    fieldName, winningSource, idVal, slugVal);
        }
        return result;
    }

    private static int pickInt(int searchVal, int slugVal, int idVal, String fieldName) {
        int result = firstNonZero(idVal, slugVal, searchVal);
        if (idVal != 0 && slugVal != 0 && idVal != slugVal) {
            String winningSource = result == idVal ? "id" : "slug";
            log.warn("rawg_field_mismatch field={} winningSource={} idValue={} slugValue={}",
                    fieldName, winningSource, idVal, slugVal);
        }
        return result;
    }

    private static Integer pickIntObject(Integer searchVal, Integer slugVal, Integer idVal, String fieldName) {
        Integer result = firstNonNullInt(idVal, slugVal, searchVal);
        if (idVal != null && slugVal != null && !idVal.equals(slugVal)) {
            String winningSource = result == idVal ? "id" : "slug";
            log.warn("rawg_field_mismatch field={} winningSource={} idValue={} slugValue={}",
                    fieldName, winningSource, idVal, slugVal);
        }
        return result;
    }

    private static int pickIntCount(int searchVal, int slugVal, int idVal) {
        return Math.max(idVal, Math.max(slugVal, searchVal));
    }

    private static double pickDouble(double searchVal, double slugVal, double idVal, String fieldName) {
        double result = firstNonZeroDouble(idVal, slugVal, searchVal);
        if (idVal != 0.0 && slugVal != 0.0 && idVal != slugVal) {
            String winningSource = result == idVal ? "id" : "slug";
            log.warn("rawg_field_mismatch field={} winningSource={} idValue={} slugValue={}",
                    fieldName, winningSource, idVal, slugVal);
        }
        return result;
    }

    private static boolean pickBoolean(boolean searchVal, boolean slugVal, boolean idVal) {
        return idVal || slugVal || searchVal;
    }

    private static <T> T pickNested(T searchVal, T slugVal, T idVal, String fieldName) {
        T result = idVal != null ? idVal : (slugVal != null ? slugVal : searchVal);
        if (idVal != null && slugVal != null && !idVal.equals(slugVal)) {
            String winningSource = result == idVal ? "id" : "slug";
            log.warn("rawg_field_mismatch field={} winningSource={} idValue={} slugValue={}",
                    fieldName, winningSource, idVal, slugVal);
        }
        return result;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (isNonBlank(v)) {
                return v;
            }
        }
        return null;
    }

    private static boolean isNonBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static int firstNonZero(int... values) {
        for (int v : values) {
            if (v != 0) {
                return v;
            }
        }
        return 0;
    }

    private static Integer firstNonNullInt(Integer... values) {
        for (Integer v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static double firstNonZeroDouble(double... values) {
        for (double v : values) {
            if (v != 0.0) {
                return v;
            }
        }
        return 0.0;
    }

    private static <T> List<T> unionById(
            List<T> search,
            List<T> slug,
            List<T> id,
            Function<T, Integer> keyFn) {
        List<T> s = search == null ? List.of() : search;
        List<T> u = slug == null ? List.of() : slug;
        List<T> i = id == null ? List.of() : id;
        Map<Integer, T> byKey = new LinkedHashMap<>();
        for (T item : i) {
            Integer key = keyFn.apply(item);
            if (key != null && key != 0) {
                byKey.putIfAbsent(key, item);
            }
        }
        for (T item : u) {
            Integer key = keyFn.apply(item);
            if (key != null && key != 0) {
                byKey.putIfAbsent(key, item);
            }
        }
        for (T item : s) {
            Integer key = keyFn.apply(item);
            if (key != null && key != 0) {
                byKey.putIfAbsent(key, item);
            }
        }
        return List.copyOf(byKey.values());
    }

    private static List<RawgScreenshotDto> unionShortScreenshots(
            List<RawgScreenshotDto> search,
            List<RawgScreenshotDto> slug,
            List<RawgScreenshotDto> id) {
        List<RawgScreenshotDto> s = search == null ? List.of() : search;
        List<RawgScreenshotDto> u = slug == null ? List.of() : slug;
        List<RawgScreenshotDto> i = id == null ? List.of() : id;
        Map<Long, RawgScreenshotDto> byId = new LinkedHashMap<>();
        for (RawgScreenshotDto item : i) {
            if (item != null && item.id() != 0) {
                byId.putIfAbsent(item.id(), item);
            }
        }
        for (RawgScreenshotDto item : u) {
            if (item != null && item.id() != 0) {
                byId.putIfAbsent(item.id(), item);
            }
        }
        for (RawgScreenshotDto item : s) {
            if (item != null && item.id() != 0) {
                byId.putIfAbsent(item.id(), item);
            }
        }
        return List.copyOf(byId.values());
    }

    private static List<RawgPlatformEntryDto> unionPlatforms(
            List<RawgPlatformEntryDto> search,
            List<RawgPlatformEntryDto> slug,
            List<RawgPlatformEntryDto> id) {
        List<RawgPlatformEntryDto> s = search == null ? List.of() : search;
        List<RawgPlatformEntryDto> u = slug == null ? List.of() : slug;
        List<RawgPlatformEntryDto> i = id == null ? List.of() : id;
        Map<Integer, RawgPlatformEntryDto> byId = new LinkedHashMap<>();
        for (RawgPlatformEntryDto item : i) {
            Integer key = platformKey(item);
            if (key != null) {
                byId.putIfAbsent(key, item);
            }
        }
        for (RawgPlatformEntryDto item : u) {
            Integer key = platformKey(item);
            if (key != null) {
                byId.putIfAbsent(key, item);
            }
        }
        for (RawgPlatformEntryDto item : s) {
            Integer key = platformKey(item);
            if (key != null) {
                byId.putIfAbsent(key, item);
            }
        }
        return List.copyOf(byId.values());
    }

    private static Integer platformKey(RawgPlatformEntryDto entry) {
        if (entry == null || entry.platform() == null) {
            return null;
        }
        int id = entry.platform().id();
        return id == 0 ? null : id;
    }

    private static List<RawgStoreEntryDto> unionByStoreId(
            List<RawgStoreEntryDto> search,
            List<RawgStoreEntryDto> slug,
            List<RawgStoreEntryDto> id) {
        List<RawgStoreEntryDto> s = search == null ? List.of() : search;
        List<RawgStoreEntryDto> u = slug == null ? List.of() : slug;
        List<RawgStoreEntryDto> i = id == null ? List.of() : id;
        Map<Long, RawgStoreEntryDto> byId = new LinkedHashMap<>();
        for (RawgStoreEntryDto item : i) {
            if (item != null && item.id() != 0L) {
                byId.putIfAbsent(item.id(), item);
            }
        }
        for (RawgStoreEntryDto item : u) {
            if (item != null && item.id() != 0L) {
                byId.putIfAbsent(item.id(), item);
            }
        }
        for (RawgStoreEntryDto item : s) {
            if (item != null && item.id() != 0L) {
                byId.putIfAbsent(item.id(), item);
            }
        }
        return List.copyOf(byId.values());
    }

    private static List<String> unionStrings(List<String> search, List<String> slug, List<String> id) {
        List<String> s = search == null ? List.of() : search;
        List<String> u = slug == null ? List.of() : slug;
        List<String> i = id == null ? List.of() : id;
        Set<String> seen = new LinkedHashSet<>();
        seen.addAll(i);
        seen.addAll(u);
        seen.addAll(s);
        return List.copyOf(seen);
    }

    private static Map<String, Integer> mergeIntMap(
            Map<String, Integer> search,
            Map<String, Integer> slug,
            Map<String, Integer> id) {
        Map<String, Integer> s = search == null ? Map.of() : search;
        Map<String, Integer> u = slug == null ? Map.of() : slug;
        Map<String, Integer> i = id == null ? Map.of() : id;
        Set<String> allKeys = new LinkedHashSet<>();
        allKeys.addAll(s.keySet());
        allKeys.addAll(u.keySet());
        allKeys.addAll(i.keySet());
        Map<String, Integer> result = new HashMap<>();
        for (String key : allKeys) {
            int best = 0;
            Integer a = s.get(key);
            Integer b = u.get(key);
            Integer c = i.get(key);
            if (a != null) {
                best = Math.max(best, a);
            }
            if (b != null) {
                best = Math.max(best, b);
            }
            if (c != null) {
                best = Math.max(best, c);
            }
            result.put(key, best);
        }
        return Map.copyOf(result);
    }
}
