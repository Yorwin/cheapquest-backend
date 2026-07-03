package com.cheapquest.backend.client;

import com.cheapquest.backend.exception.TranslationFailedException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Wraps a {@link DeepLTranslator} with the operational concerns
 * the raw SDK does not handle:
 * <ul>
 *   <li><b>Batching</b>: DeepL allows at most 50 strings per
 *       request. Inputs larger than the batch size are split
 *       transparently; the returned list is in the same order as
 *       the input.</li>
 *   <li><b>HTML preservation</b>: the game {@code description}
 *       carries HTML tags that must survive the translation. The
 *       {@code tag_handling=html} preset is set on every call to
 *       the underlying translator so the markup is preserved.</li>
 *   <li><b>Cache</b>: many games share the same tag
 *       ("Action", "Adventure", "FPS", ...). An in-memory LRU
 *       keyed by {@code (targetLang, sourceText)} avoids burning
 *       the 500k chars/month DeepL free quota on duplicates.
 *       Cache entries expire after a configurable TTL.</li>
 *   <li><b>Error mapping</b>: any exception from the underlying
 *       translator is wrapped in {@link TranslationFailedException}
 *       so the {@code GlobalExceptionHandler} can map it to
 *       HTTP 502.</li>
 * </ul>
 *
 * <p>This class is thread-safe: the LRU cache is synchronised and
 * DeepL's {@code Translator} is documented as thread-safe.
 */
public final class DeepLClient {

    /** DeepL hard limit on the number of {@code text} fields per request. */
    public static final int DEEPL_MAX_BATCH_SIZE = 50;

    private final DeepLTranslator translator;
    private final int maxBatchSize;
    private final long ttlMillis;
    private final int maxCacheSize;
    private final Clock clock;
    private final LinkedHashMap<String, CacheEntry> cache;

    public DeepLClient(String authKey, String baseUrl, int maxBatchSize,
            long cacheTtlMillis, int cacheMaxSize, Clock clock) {
        this(new DeepLTranslator.SdkBacked(authKey, baseUrl),
                maxBatchSize, cacheTtlMillis, cacheMaxSize, clock);
    }

    public DeepLClient(DeepLTranslator translator, int maxBatchSize,
            long cacheTtlMillis, int cacheMaxSize, Clock clock) {
        this.translator = Objects.requireNonNull(translator, "translator");
        if (maxBatchSize < 1 || maxBatchSize > DEEPL_MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "maxBatchSize must be in [1, " + DEEPL_MAX_BATCH_SIZE + "], got " + maxBatchSize);
        }
        if (cacheTtlMillis < 0) {
            throw new IllegalArgumentException("cacheTtlMillis must be >= 0, got " + cacheTtlMillis);
        }
        if (cacheMaxSize < 0) {
            throw new IllegalArgumentException("cacheMaxSize must be >= 0, got " + cacheMaxSize);
        }
        this.maxBatchSize = maxBatchSize;
        this.ttlMillis = cacheTtlMillis;
        this.maxCacheSize = cacheMaxSize;
        this.clock = Objects.requireNonNull(clock, "clock");
        // accessOrder=true makes LinkedHashMap evict least-recently-used
        // entries; removeEldestEntry enforces the max size.
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                return size() > maxCacheSize;
            }
        };
    }

    /**
     * Translate each input string into {@code targetLang}, returning
     * a list of the same size and order. An input of {@code null}
     * or an empty string is returned as-is (no DeepL call).
     *
     * <p>On any translator-side failure the whole call is aborted
     * and a {@link TranslationFailedException} is raised; the LRU
     * is left unchanged so a transient failure does not poison
     * future lookups.
     */
    public synchronized List<String> translate(List<String> texts, String targetLang) {
        Objects.requireNonNull(texts, "texts");
        Objects.requireNonNull(targetLang, "targetLang");
        if (texts.isEmpty()) {
            return List.of();
        }

        // 1. Look up each input in the cache. Misses are collected
        //    in their original order so the assembled result keeps
        //    the caller's order. Skips (null / empty) and cache
        //    hits produce their result directly.
        List<Integer> missIndices = new ArrayList<>();
        List<String> misses = new ArrayList<>();
        String[] result = new String[texts.size()];
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            if (text == null || text.isEmpty()) {
                result[i] = text;
                continue;
            }
            CacheEntry cached = cache.get(cacheKey(targetLang, text));
            if (cached != null && !isExpired(cached)) {
                result[i] = cached.translation;
                continue;
            }
            missIndices.add(i);
            misses.add(text);
        }
        if (misses.isEmpty()) {
            return Collections.unmodifiableList(Arrays.asList(result));
        }

        // 2. Batch the misses into chunks of maxBatchSize and call
        //    the translator once per chunk. The order within each
        //    batch is preserved, and the assembled result is in the
        //    same order as {@code misses}.
        int fromMiss = 0;
        while (fromMiss < misses.size()) {
            int toMiss = Math.min(fromMiss + maxBatchSize, misses.size());
            List<String> batch = misses.subList(fromMiss, toMiss);
            List<String> translated;
            try {
                translated = translator.translate(batch, targetLang);
            } catch (Exception e) {
                throw new TranslationFailedException(
                        "DeepL translate failed for " + batch.size()
                                + " string(s) into " + targetLang + ": " + e.getMessage(), e);
            }
            if (translated.size() != batch.size()) {
                throw new TranslationFailedException(
                        "DeepL returned " + translated.size() + " translations for a batch of "
                                + batch.size() + " (target=" + targetLang + ")");
            }
            Instant now = Instant.now(clock);
            for (int j = 0; j < batch.size(); j++) {
                String source = batch.get(j);
                String translatedText = translated.get(j);
                result[missIndices.get(fromMiss + j)] = translatedText;
                putCache(cacheKey(targetLang, source),
                        new CacheEntry(translatedText, now));
            }
            fromMiss = toMiss;
        }
        return Collections.unmodifiableList(Arrays.asList(result));
    }

    /** Test hook: number of entries currently in the LRU cache. */
    synchronized int cacheSize() {
        return cache.size();
    }

    private void putCache(String key, CacheEntry entry) {
        if (maxCacheSize == 0) {
            return;
        }
        cache.put(key, entry);
    }

    private boolean isExpired(CacheEntry entry) {
        if (ttlMillis == 0) {
            return false;
        }
        return clock.millis() - entry.storedAtMillis() >= ttlMillis;
    }

    private static String cacheKey(String targetLang, String sourceText) {
        return targetLang + ":" + sourceText;
    }

    private record CacheEntry(String translation, Instant storedAt) {
        long storedAtMillis() {
            return storedAt.toEpochMilli();
        }
    }
}
