package com.cheapquest.backend.endpoint.sections;

/**
 * Internal helper for the two URL-prefix endpoints the
 * sections pipeline exposes ({@code /admin/sections/{name}}
 * and {@code /sections/{name}}). The {@link
 * com.sun.net.httpserver.HttpServer} dispatches by prefix,
 * so each handler receives the full request path
 * ({@code /admin/sections/mejores-promos}) and has to
 * extract the trailing slug itself. This is the single
 * place that knows the rule.
 */
final class SectionsPathUtils {

    private SectionsPathUtils() {
    }

    /**
     * Returns the segment after the given prefix, or
     * {@code null} if there is no such segment. A request
     * for {@code /admin/sections/} with prefix
     * {@code /admin/sections/} has no segment; a request
     * for {@code /admin/sections/mejores-promos} returns
     * {@code "mejores-promos"}.
     */
    static String lastSegment(String path, String prefix) {
        if (path == null || !path.startsWith(prefix)) {
            return null;
        }
        String rest = path.substring(prefix.length());
        if (rest.startsWith("/")) {
            rest = rest.substring(1);
        }
        if (rest.isEmpty()) {
            return null;
        }
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }
}
