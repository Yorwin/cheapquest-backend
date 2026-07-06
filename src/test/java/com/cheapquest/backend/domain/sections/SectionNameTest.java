package com.cheapquest.backend.domain.sections;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SectionNameTest {

    @Test
    void slug_uses_dash_separator() {
        assertThat(SectionName.POPULARES.slug()).isEqualTo("populares");
        assertThat(SectionName.MEJORES_PROMOS.slug()).isEqualTo("mejores-promos");
        assertThat(SectionName.NUEVAS_OFERTAS.slug()).isEqualTo("nuevas-ofertas");
        assertThat(SectionName.BAJOS_HISTORICOS.slug()).isEqualTo("bajos-historicos");
        assertThat(SectionName.VINTAGE.slug()).isEqualTo("vintage");
    }

    @Test
    void slug_is_lowercase() {
        for (SectionName n : SectionName.values()) {
            assertThat(n.slug())
                    .as("slug of %s must be lowercase", n)
                    .isEqualTo(n.slug().toLowerCase());
        }
    }

    @Test
    void valueOf_round_trip() {
        for (SectionName n : SectionName.values()) {
            assertThat(SectionName.valueOf(n.name())).isEqualTo(n);
        }
    }

    @Test
    void all_sections_have_unique_slugs() {
        long distinct = Arrays.stream(SectionName.values())
                .map(SectionName::slug)
                .distinct()
                .count();
        assertThat(distinct).isEqualTo(SectionName.values().length);
    }

    @Test
    void has_five_sections() {
        assertThat(SectionName.values()).hasSize(5);
    }

    @Test
    void fromSlug_returns_section_for_known_slug() {
        assertThat(SectionName.fromSlug("populares"))
                .contains(SectionName.POPULARES);
        assertThat(SectionName.fromSlug("mejores-promos"))
                .contains(SectionName.MEJORES_PROMOS);
        assertThat(SectionName.fromSlug("nuevas-ofertas"))
                .contains(SectionName.NUEVAS_OFERTAS);
        assertThat(SectionName.fromSlug("vintage"))
                .contains(SectionName.VINTAGE);
        assertThat(SectionName.fromSlug("bajos-historicos"))
                .contains(SectionName.BAJOS_HISTORICOS);
    }

    @Test
    void fromSlug_returns_empty_for_null_blank_or_unknown() {
        assertThat(SectionName.fromSlug(null)).isEmpty();
        assertThat(SectionName.fromSlug("")).isEmpty();
        assertThat(SectionName.fromSlug("   ")).isEmpty();
        assertThat(SectionName.fromSlug("Populares")).isEmpty();
        assertThat(SectionName.fromSlug("mejores_promos")).isEmpty();
        assertThat(SectionName.fromSlug("nope")).isEmpty();
    }
}
