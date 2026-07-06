package com.cheapquest.backend.domain.sections;

import java.util.Map;

/**
 * Domain projection of the RAWG block, narrowed to the fields
 * the section builders and the page projection need.
 *
 * <p>v1 (release-date / metacritic / rating) is the input for
 * the vintage and nuevos builders. v2 adds the popularity
 * signals the "populares" builder sorts on:
 * {@code ratingsCount} (raw star count, 0-5 totals),
 * {@code additionsCount} (number of DLCs / sibling games),
 * {@code addedByStatus} (e.g. {@code {yet=124, owned=320,
 * beaten=58}} — RAWG's "how many users marked it X" map),
 * {@code reactions} (e.g. {@code {1=23, 2=5, 3=2, 4=1, 5=0}}
 * — likes / hearts split) and {@code suggestionsCount}
 * (number of "you might also like" entries RAWG produced).
 * All are nullable so a game that has not been ranked by RAWG
 * still loads; builders filter on the fields they need.
 */
public record RawgView(
        String released,
        Integer metacritic,
        Double rating,
        Integer ratingsCount,
        Integer additionsCount,
        Map<String, Integer> addedByStatus,
        Map<String, Integer> reactions,
        Integer suggestionsCount) {

    public RawgView {
        addedByStatus = addedByStatus == null ? Map.of() : Map.copyOf(addedByStatus);
        reactions = reactions == null ? Map.of() : Map.copyOf(reactions);
    }
}
