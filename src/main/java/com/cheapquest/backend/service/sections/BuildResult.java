package com.cheapquest.backend.service.sections;

import com.cheapquest.backend.domain.sections.SectionItem;
import java.util.List;
import java.util.Objects;

/**
 * Output of a {@link SectionBuilder#build(SectionContext)}
 * call. The builder reports two numbers plus the items:
 *
 * <ul>
 *   <li>{@code totalCandidates} is the size of the
 *       post-filter, pre-limit candidate set: how many games
 *       in the catalog passed the builder's eligibility
 *       check. This is what {@code SectionSnapshot.totalCandidates}
 *       records so a consumer can tell "the section is short
 *       because the catalog is small" apart from "the section
 *       is short because most games were filtered out".</li>
 *   <li>{@code items} is the final, sorted, limited list the
 *       service will wrap into a {@code SectionSnapshot}.</li>
 * </ul>
 *
 * <p>The relationship {@code items.size() <= totalCandidates}
 * always holds; it is not enforced by the record so the
 * builder can return a partial top-N if it wants to surface
 * a degraded state (e.g. an "almost full" section). The
 * service treats both as data, not as a contract.
 */
public record BuildResult(int totalCandidates, List<SectionItem> items) {

    public BuildResult {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static BuildResult empty(int totalCandidates) {
        return new BuildResult(totalCandidates, List.of());
    }
}
