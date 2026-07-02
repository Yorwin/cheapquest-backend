package com.cheapquest.backend.util;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class Urls {

    private Urls() {
    }

    public static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    public static String buildKeyParam(String apiKey) {
        if (StringUtils.isBlank(apiKey)) {
            return "";
        }
        return "key=" + apiKey;
    }
}
