package com.cheapquest.backend.endpoint.sections;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SectionsPathUtilsTest {

    @Test
    void lastSegment_returns_segment_after_prefix() {
        assertThat(SectionsPathUtils.lastSegment(
                "/admin/sections/mejores-promos", "/admin/sections/"))
                .isEqualTo("mejores-promos");
    }

    @Test
    void lastSegment_returns_null_when_path_equals_prefix() {
        assertThat(SectionsPathUtils.lastSegment(
                "/admin/sections/", "/admin/sections/"))
                .isNull();
    }

    @Test
    void lastSegment_returns_null_when_path_is_shorter_than_prefix() {
        assertThat(SectionsPathUtils.lastSegment(
                "/admin/x", "/admin/sections/"))
                .isNull();
    }

    @Test
    void lastSegment_ignores_subsequent_slashes() {
        assertThat(SectionsPathUtils.lastSegment(
                "/sections/vintage/extra", "/sections/"))
                .isEqualTo("vintage");
    }

    @Test
    void lastSegment_returns_null_for_null_path() {
        assertThat(SectionsPathUtils.lastSegment(null, "/sections/")).isNull();
    }
}
