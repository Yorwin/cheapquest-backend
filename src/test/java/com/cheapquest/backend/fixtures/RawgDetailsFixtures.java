package com.cheapquest.backend.fixtures;

import com.cheapquest.backend.domain.rawg.DeveloperSummary;
import com.cheapquest.backend.domain.rawg.PublisherSummary;
import com.cheapquest.backend.domain.rawg.RawgDetails;
import com.cheapquest.backend.domain.rawg.RawgGenre;
import com.cheapquest.backend.domain.rawg.RawgTag;
import java.util.List;

/**
 * Shared builder for {@link RawgDetails} instances used by tests that
 * need to control which fields are populated. The default state is
 * "fully populated" so the most common usage is to start with
 * {@link #full(String, String)} and clear the field under test.
 */
public final class RawgDetailsFixtures {

    private RawgDetailsFixtures() {
    }

    public static DetailsBuilder full(String slug, String name) {
        return new DetailsBuilder(slug, name);
    }

    public static RawgDetails minimalDetails(String slug, String name) {
        return new DetailsBuilder(slug, name).build();
    }

    public static final class DetailsBuilder {
        private final String slug;
        private final String name;
        private String nameOriginal;
        private String released = "2007-10-10";
        private String description = "<p>An action game.</p>";
        private String descriptionRaw = "An action game.";
        private String headerImage = "https://example.com/header.jpg";
        private String trailerUrl = "https://example.com/trailer.mp4";
        private List<DeveloperSummary> developers = List.of(new DeveloperSummary("Valve", "valve"));
        private List<PublisherSummary> publishers = List.of(new PublisherSummary("Valve", "valve"));
        private List<RawgGenre> genres = List.of(new RawgGenre(1, "Action", "action"));
        private List<RawgTag> tags = List.of(new RawgTag(1, "FPS", "fps", "eng"));
        private List<String> screenshots = List.of("https://example.com/shot1.jpg");
        private java.time.Instant fetchedAt = java.time.Instant.parse("2026-06-30T00:00:00Z");

        DetailsBuilder(String slug, String name) {
            this.slug = slug;
            this.name = name;
            this.nameOriginal = name;
        }

        public DetailsBuilder released(String v) { this.released = v; return this; }
        public DetailsBuilder description(String v) { this.description = v; return this; }
        public DetailsBuilder descriptionRaw(String v) { this.descriptionRaw = v; return this; }
        public DetailsBuilder headerImage(String v) { this.headerImage = v; return this; }
        public DetailsBuilder trailerUrl(String v) { this.trailerUrl = v; return this; }
        public DetailsBuilder developers(List<DeveloperSummary> v) { this.developers = v; return this; }
        public DetailsBuilder publishers(List<PublisherSummary> v) { this.publishers = v; return this; }
        public DetailsBuilder genres(List<RawgGenre> v) { this.genres = v; return this; }
        public DetailsBuilder tags(List<RawgTag> v) { this.tags = v; return this; }
        public DetailsBuilder screenshots(List<String> v) { this.screenshots = v; return this; }
        public DetailsBuilder fetchedAt(java.time.Instant v) { this.fetchedAt = v; return this; }

        public RawgDetails build() {
            return new RawgDetails(
                    slug, name, nameOriginal, released, description, descriptionRaw,
                    headerImage, trailerUrl, null, null, null, null, 0, 0, 0, 0,
                    developers, publishers, genres, tags, null, null, null, null, screenshots,
                    fetchedAt);
        }
    }
}
