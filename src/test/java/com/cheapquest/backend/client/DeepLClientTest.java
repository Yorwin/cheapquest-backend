package com.cheapquest.backend.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cheapquest.backend.exception.TranslationFailedException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DeepLClientTest {

    private static final String TARGET_ES = "es";
    private static final String TARGET_FR = "fr";

    private DeepLTranslator translator;
    private Clock clock;
    private DeepLClient client;

    @BeforeEach
    void setUp() {
        translator = mock(DeepLTranslator.class);
        clock = Clock.fixed(Instant.parse("2026-06-30T10:00:00Z"), ZoneOffset.UTC);
        client = new DeepLClient(translator, 50, 3_600_000L, 100, clock);
    }

    @Test
    void translate_returnsTranslationsInInputOrder() throws Exception {
        when(translator.translate(anyList(), anyString()))
                .thenReturn(List.of("Hola", "Mundo", "Adios"));

        List<String> result = client.translate(
                List.of("Hello", "World", "Goodbye"), TARGET_ES);

        assertThat(result).containsExactly("Hola", "Mundo", "Adios");
    }

    @Test
    void translate_passesTargetLangToTranslator() throws Exception {
        when(translator.translate(anyList(), anyString()))
                .thenReturn(List.of("Bonjour"));

        client.translate(List.of("Hello"), TARGET_FR);

        verify(translator).translate(anyList(), org.mockito.ArgumentMatchers.eq(TARGET_FR));
    }

    @Test
    void translate_batchesLargerInputs() throws Exception {
        // 110 strings at batch size 50 should produce 3 calls
        // (50 + 50 + 10).
        List<String> input = new ArrayList<>();
        for (int i = 0; i < 110; i++) {
            input.add("text-" + i);
        }
        when(translator.translate(anyList(), anyString()))
                .thenAnswer(inv -> {
                    List<String> batch = inv.getArgument(0);
                    return batch.stream().map(s -> "t-" + s).toList();
                });

        List<String> result = client.translate(input, TARGET_ES);

        assertThat(result).hasSize(110);
        assertThat(result.get(0)).isEqualTo("t-text-0");
        assertThat(result.get(49)).isEqualTo("t-text-49");
        assertThat(result.get(50)).isEqualTo("t-text-50");
        assertThat(result.get(109)).isEqualTo("t-text-109");
        verify(translator, times(3)).translate(anyList(), anyString());
    }

    @Test
    void translate_batchesRespectBatchSize() throws Exception {
        // Use a client with batch size 3 so 7 inputs split into
        // 3 + 3 + 1.
        DeepLClient smallBatchClient = new DeepLClient(translator, 3, 3_600_000L, 100, clock);
        when(translator.translate(anyList(), anyString()))
                .thenAnswer(inv -> {
                    List<String> batch = inv.getArgument(0);
                    return batch.stream().map(s -> "t-" + s).toList();
                });

        smallBatchClient.translate(
                Arrays.asList("a", "b", "c", "d", "e", "f", "g"), TARGET_ES);

        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(translator, times(3)).translate(captor.capture(), anyString());
        assertThat(captor.getAllValues().get(0)).containsExactly("a", "b", "c");
        assertThat(captor.getAllValues().get(1)).containsExactly("d", "e", "f");
        assertThat(captor.getAllValues().get(2)).containsExactly("g");
    }

    @Test
    void translate_reusesCacheAcrossCalls() throws Exception {
        when(translator.translate(anyList(), anyString()))
                .thenReturn(List.of("Hola"));

        client.translate(List.of("Hello"), TARGET_ES);
        client.translate(List.of("Hello"), TARGET_ES);

        verify(translator, times(1)).translate(anyList(), anyString());
        assertThat(client.cacheSize()).isEqualTo(1);
    }

    @Test
    void translate_separatesCacheByTargetLang() throws Exception {
        when(translator.translate(anyList(), org.mockito.ArgumentMatchers.eq(TARGET_ES)))
                .thenReturn(List.of("Hola"));
        when(translator.translate(anyList(), org.mockito.ArgumentMatchers.eq(TARGET_FR)))
                .thenReturn(List.of("Bonjour"));

        client.translate(List.of("Hello"), TARGET_ES);
        client.translate(List.of("Hello"), TARGET_FR);

        assertThat(client.cacheSize()).isEqualTo(2);
    }

    @Test
    void translate_expiresCacheAfterTtl() throws Exception {
        // Two clients, same translator mock, different clocks past
        // a 1ms TTL. The second call must not be a cache hit.
        when(translator.translate(anyList(), anyString()))
                .thenReturn(List.of("v1", "v2"));

        DeepLClient ttlClient = new DeepLClient(translator, 50, 1L, 100, clock);
        ttlClient.translate(List.of("a", "b"), TARGET_ES);
        Clock later = Clock.fixed(Instant.parse("2026-06-30T10:00:10Z"), ZoneOffset.UTC);
        DeepLClient laterClient = new DeepLClient(translator, 50, 1L, 100, later);
        laterClient.translate(List.of("a", "b"), TARGET_ES);

        verify(translator, times(2)).translate(anyList(), anyString());
    }

    @Test
    void translate_ttlZeroMeansNeverExpire() throws Exception {
        // The cache is per-instance and the clock is injected; with
        // TTL=0 the cache is immortal, so a second call on the same
        // client always hits. (The "expire after a real TTL" path
        // is exercised by translate_expiresCacheAfterTtl above.)
        when(translator.translate(anyList(), anyString()))
                .thenReturn(List.of("v1"));

        DeepLClient noTtl = new DeepLClient(translator, 50, 0L, 100, clock);
        noTtl.translate(List.of("a"), TARGET_ES);
        noTtl.translate(List.of("a"), TARGET_ES);

        verify(translator, times(1)).translate(anyList(), anyString());
    }

    @Test
    void translate_passesThroughNullAndEmptyEntries() throws Exception {
        // null and "" are not sent to DeepL. They are returned as-is
        // in their original position.
        when(translator.translate(anyList(), anyString()))
                .thenReturn(List.of("Mundo"));

        List<String> result = client.translate(
                Arrays.asList(null, "", "World"), TARGET_ES);

        assertThat(result).containsExactly(null, "", "Mundo");
        verify(translator, times(1)).translate(anyList(), anyString());
    }

    @Test
    void translate_keepsCacheIntactOnTranslatorFailure() throws Exception {
        // First call seeds the cache; second call (different input)
        // fails and must not poison the cache. A third call with the
        // original input should still be a cache hit.
        when(translator.translate(anyList(), anyString()))
                .thenReturn(List.of("Hola"))   // seeds cache for "Hello"
                .thenThrow(new RuntimeException("network blip"))   // second call fails
                .thenReturn(List.of("ignored"));    // not reached if cache hit

        client.translate(List.of("Hello"), TARGET_ES);
        assertThatThrownBy(() -> client.translate(List.of("World"), TARGET_ES))
                .isInstanceOf(TranslationFailedException.class);
        // "Hello" is still in the cache, so a third call must not
        // hit the translator.
        client.translate(List.of("Hello"), TARGET_ES);
        verify(translator, times(2)).translate(anyList(), anyString());
    }

    @Test
    void translate_wrapsTranslatorExceptionInTranslationFailed() throws Exception {
        when(translator.translate(anyList(), anyString()))
                .thenThrow(new RuntimeException("quota exhausted"));

        assertThatThrownBy(() -> client.translate(List.of("Hello"), TARGET_ES))
                .isInstanceOf(TranslationFailedException.class)
                .hasMessageContaining("quota exhausted");
    }

    @Test
    void translate_rejectsSizeMismatch() throws Exception {
        // Translator returns 1 result for a 2-string batch.
        when(translator.translate(anyList(), anyString()))
                .thenReturn(List.of("only-one"));

        assertThatThrownBy(() -> client.translate(List.of("a", "b"), TARGET_ES))
                .isInstanceOf(TranslationFailedException.class)
                .hasMessageContaining("returned 1");
    }

    @Test
    void translate_returnsEmptyForEmptyInput() throws Exception {
        assertThat(client.translate(List.of(), TARGET_ES)).isEmpty();
        verify(translator, never()).translate(anyList(), anyString());
    }

    @Test
    void constructor_rejectsInvalidConfig() {
        assertThatThrownBy(() -> new DeepLClient(translator, 0, 0, 0, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxBatchSize");
        assertThatThrownBy(() -> new DeepLClient(translator, 51, 0, 0, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxBatchSize");
        assertThatThrownBy(() -> new DeepLClient(translator, 50, -1, 0, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cacheTtlMillis");
        assertThatThrownBy(() -> new DeepLClient(translator, 50, 0, -1, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cacheMaxSize");
    }
}
