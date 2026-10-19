package com.agenticai.core.agents.tools.web;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Log-safe rendering of request URLs (SEC-049). */
final class HttpLoggableUrl {

    private HttpLoggableUrl() {}

    /**
     * The URL as it may appear in a log line (SEC-049): scheme, host, port and path only - no
     * userinfo, query string or fragment - with any path segment that contains a placeholder or a
     * resolved secret masked.
     */
    static String of(URI uri, Set<String> secretValues) {
        if (uri == null) {
            return "-";
        }
        StringBuilder out = new StringBuilder();
        out.append(uri.getScheme() == null ? "?" : uri.getScheme())
                .append("://")
                .append(uri.getHost() == null ? "?" : uri.getHost());
        if (uri.getPort() >= 0) {
            out.append(':').append(uri.getPort());
        }
        String path = uri.getRawPath();
        if (path != null && !path.isEmpty()) {
            String[] segments = path.split("/", -1);
            for (int i = 0; i < segments.length; i++) {
                if (i > 0) {
                    out.append('/');
                }
                String segment = segments[i];
                String decoded = URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8);
                boolean sensitive =
                        segment.contains("{{")
                                || decoded.contains("{{")
                                || HttpRedirectStage.containsAny(segment, secretValues)
                                || HttpRedirectStage.containsAny(decoded, secretValues);
                out.append(sensitive ? "***" : segment);
            }
        }
        if (uri.getRawQuery() != null) {
            out.append("?...");
        }
        return out.toString();
    }
}
