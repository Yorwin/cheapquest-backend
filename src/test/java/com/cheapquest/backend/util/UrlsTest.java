package com.cheapquest.backend.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UrlsTest {

    @Test
    void encode_encodesSpecialChars() {
        assertThat(Urls.encode("Half-Life 2")).isEqualTo("Half-Life+2");
        assertThat(Urls.encode("Far Cry: Special Edition")).isEqualTo("Far+Cry%3A+Special+Edition");
        assertThat(Urls.encode("café")).isEqualTo("caf%C3%A9");
    }

    @Test
    void encode_keepsAlnumUnchanged() {
        assertThat(Urls.encode("Portal")).isEqualTo("Portal");
        assertThat(Urls.encode("game123")).isEqualTo("game123");
    }

    @Test
    void encode_emptyReturnsEmpty() {
        assertThat(Urls.encode("")).isEmpty();
    }

    @Test
    void buildKeyParam_returnsKeyEqualsValue() {
        assertThat(Urls.buildKeyParam("cc387cdcb05e4f4aa9f6592bea63c0ab"))
                .isEqualTo("key=cc387cdcb05e4f4aa9f6592bea63c0ab");
    }

    @Test
    void buildKeyParam_doesNotUrlEncodeTheKey() {
        String key = "abc-DEF_123";
        assertThat(Urls.buildKeyParam(key)).isEqualTo("key=abc-DEF_123");
    }

    @Test
    void buildKeyParam_emptyOnNullOrBlank() {
        assertThat(Urls.buildKeyParam(null)).isEmpty();
        assertThat(Urls.buildKeyParam("")).isEmpty();
        assertThat(Urls.buildKeyParam("   ")).isEmpty();
    }
}
