package com.cheapquest.backend.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class StringNormalizeTest {

    @Test
    void matchKey_lowercasesInput() {
        assertThat(StringNormalize.matchKey("PORTAL")).isEqualTo("portal");
    }

    @Test
    void matchKey_trimsLeadingAndTrailingWhitespace() {
        assertThat(StringNormalize.matchKey("  Portal  ")).isEqualTo("portal");
    }

    @Test
    void matchKey_stripsSpaces() {
        assertThat(StringNormalize.matchKey("Half Life 2")).isEqualTo("halflife2");
    }

    @Test
    void matchKey_stripsHyphens() {
        assertThat(StringNormalize.matchKey("Half-Life 2")).isEqualTo("halflife2");
    }

    @Test
    void matchKey_stripsUnderscores() {
        assertThat(StringNormalize.matchKey("hello_world")).isEqualTo("helloworld");
    }

    @Test
    void matchKey_stripsPunctuation() {
        assertThat(StringNormalize.matchKey("FAR_CRY-3!")).isEqualTo("farcry3");
    }

    @Test
    void matchKey_stripsApostrophesAndBangs() {
        assertThat(StringNormalize.matchKey("Don't Starve!")).isEqualTo("dontstarve");
    }

    @Test
    void matchKey_stripsUnicodeSymbols() {
        assertThat(StringNormalize.matchKey("Pokémon Edición")).isEqualTo("pokmonedicin");
    }

    @Test
    void matchKey_collapsesRunsOfSeparators() {
        assertThat(StringNormalize.matchKey("  Far  -- Cry__  ")).isEqualTo("farcry");
    }

    @Test
    void matchKey_returnsEmptyStringForAllPunctuation() {
        assertThat(StringNormalize.matchKey("!!!")).isEqualTo("");
    }

    @Test
    void matchKey_throwsNullPointerOnNull() {
        assertThatThrownBy(() -> StringNormalize.matchKey(null))
                .isInstanceOf(NullPointerException.class);
    }
}
