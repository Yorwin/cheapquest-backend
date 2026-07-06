package com.cheapquest.backend.dto.public_;

import java.util.List;

/**
 * Response of {@code GET /sections}. Lists every
 * {@link SectionName} with the date and totalCandidates of
 * the latest snapshot, in canonical enum order. Sections
 * that have never been computed are absent from the list;
 * a "section unavailable" message is the caller's job.
 */
public record PublicSectionListDto(
        String status,
        int count,
        List<Entry> sections) {

    public record Entry(
            String name,
            String date,
            int totalCandidates) {
    }
}
