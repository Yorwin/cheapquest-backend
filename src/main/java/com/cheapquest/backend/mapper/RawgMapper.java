package com.cheapquest.backend.mapper;

import com.cheapquest.backend.domain.rawg.DeveloperSummary;
import com.cheapquest.backend.domain.rawg.PublisherSummary;
import com.cheapquest.backend.domain.rawg.RawgCreator;
import com.cheapquest.backend.domain.rawg.RawgDetails;
import com.cheapquest.backend.domain.rawg.RawgDlc;
import com.cheapquest.backend.domain.rawg.RawgGenre;
import com.cheapquest.backend.domain.rawg.RawgPlatform;
import com.cheapquest.backend.domain.rawg.RawgTag;
import com.cheapquest.backend.dto.rawg.RawgCreatorDto;
import com.cheapquest.backend.dto.rawg.RawgDeveloperDto;
import com.cheapquest.backend.dto.rawg.RawgGameDto;
import com.cheapquest.backend.dto.rawg.RawgGenreDto;
import com.cheapquest.backend.dto.rawg.RawgMovieDto;
import com.cheapquest.backend.dto.rawg.RawgPlatformEntryDto;
import com.cheapquest.backend.dto.rawg.RawgPublisherDto;
import com.cheapquest.backend.dto.rawg.RawgScreenshotDto;
import com.cheapquest.backend.dto.rawg.RawgTagDto;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RawgMapper {

    private static final Logger log = LoggerFactory.getLogger(RawgMapper.class);
    private static final String NON_ALNUM_REGEX = "[^a-z0-9]";
    private static final Pattern NON_ALNUM = Pattern.compile(NON_ALNUM_REGEX);
    private static final int LEVENSHTEIN_MAX_DISTANCE = 5;

    public Optional<RawgGameDto> pickExactMatch(List<RawgGameDto> matches, String targetName) {
        if (matches == null || targetName == null) {
            return Optional.empty();
        }
        String normalized = normalize(targetName);
        return matches.stream()
                .filter(m -> m.name() != null && normalize(m.name()).equals(normalized))
                .findFirst();
    }

    public Optional<RawgGameDto> pickClosestByLevenshtein(List<RawgGameDto> matches, String targetName) {
        if (matches == null || matches.isEmpty() || targetName == null) {
            return Optional.empty();
        }
        String normalized = normalize(targetName);
        RawgGameDto best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (RawgGameDto m : matches) {
            if (m.name() == null) {
                continue;
            }
            int distance = levenshtein(normalized, normalize(m.name()));
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
            if (video != null && !video.isBlank()) {
                return video;
            }
            String clip = detail.clip().clip();
            if (clip != null && !clip.isBlank()) {
                return clip;
            }
        }
        if (movies != null && !movies.isEmpty()) {
            RawgMovieDto first = movies.get(0);
            if (first.data() != null) {
                String max = first.data().max();
                if (max != null && !max.isBlank()) {
                    return max;
                }
                String full = first.data().full();
                if (full != null && !full.isBlank()) {
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
                fetchedAt);
    }

    static String normalize(String s) {
        return NON_ALNUM.matcher(s.trim().toLowerCase(Locale.ROOT)).replaceAll("");
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
}
