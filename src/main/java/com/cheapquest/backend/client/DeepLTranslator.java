package com.cheapquest.backend.client;

import com.deepl.api.TextResult;
import com.deepl.api.TextTranslationOptions;
import com.deepl.api.Translator;
import com.deepl.api.TranslatorOptions;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Thin abstraction over the {@code deepl-java} SDK so the rest
 * of the backend (and its tests) can depend on a small
 * application-owned interface instead of the SDK's
 * {@link Translator}, which is {@code @Deprecated} (and final
 * for testing purposes). The default production implementation
 * wraps the SDK with the {@code tag_handling=html} preset.
 */
public interface DeepLTranslator {

    /**
     * Translate the given texts into {@code targetLang}, returning
     * a list of the same size and order. The implementation is
     * expected to be safe to call from multiple threads.
     */
    List<String> translate(List<String> texts, String targetLang) throws Exception;

    /**
     * Default SDK-backed implementation. Uses
     * {@code tag_handling=html} so HTML markup in the source
     * (e.g. the game description) is preserved in the translation.
     */
    final class SdkBacked implements DeepLTranslator {

        private final Translator translator;

        public SdkBacked(String authKey, String baseUrl) {
            this.translator = buildTranslator(authKey, baseUrl);
        }

    @SuppressWarnings("deprecation")
    private static Translator buildTranslator(String authKey, String baseUrl) {
        // The deepl-java team marks Translator @Deprecated in
        // favour of DeepLClient, but DeepLClient only exposes the
        // new "Write" API (rephraseText). The translation API is
        // still on Translator, so we use it directly and silence
        // the warning until the SDK exposes a non-deprecated
        // translateText.
        //
        // If a base URL is supplied (e.g. for tests) we set it
        // explicitly; otherwise we let the SDK auto-detect from
        // the API key suffix (":fx" -> free endpoint, no suffix
        // -> pro endpoint) so the user's existing plan keeps
        // working without configuration.
        TranslatorOptions options = new TranslatorOptions();
        if (baseUrl != null && !baseUrl.isBlank()) {
            options = options.setServerUrl(baseUrl);
        }
        return new Translator(authKey, options);
    }

        @Override
        public List<String> translate(List<String> texts, String targetLang) throws Exception {
            TextTranslationOptions options = new TextTranslationOptions()
                    .setTagHandling("html");
            List<TextResult> results = translator.translateText(
                    texts, null, targetLang, options);
            return results.stream().map(TextResult::getText).collect(Collectors.toList());
        }
    }
}
