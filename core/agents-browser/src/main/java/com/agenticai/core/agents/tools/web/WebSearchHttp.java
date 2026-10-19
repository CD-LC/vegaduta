package com.agenticai.core.agents.tools.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Transport and small helpers shared by the non-Brave {@link WebSearchProvider} adapters. Brave
 * keeps its own (unchanged) client; the numbers here mirror it so every provider behaves the same
 * under load.
 */
final class WebSearchHttp {

    /** Matches BraveWebSearchClient / WebPageFetcher: a tool call must never hang a turn. */
    static final Duration TIMEOUT = Duration.ofSeconds(12);

    static final int DEFAULT_RESULTS = 5;
    static final int MAX_RESULTS = 10;

    /** Same trim Brave applies, so a pathological query cannot ride into a provider request. */
    static final int MAX_QUERY_CHARS = 400;

    /**
     * Raw response cap: a search API answers in tens of KB; anything larger is a misbehaving
     * upstream.
     */
    static final int MAX_BODY_BYTES = 1024 * 1024;

    /** Per-field caps on what a result may carry back into the prompt. */
    static final int MAX_TITLE_CHARS = 300;

    static final int MAX_SNIPPET_CHARS = 600;
    static final int MAX_URL_CHARS = 2048;

    private WebSearchHttp() {}

    /** A completed HTTP exchange reduced to what an adapter needs. */
    record Reply(int status, String body) {}

    /**
     * The network seam: production uses {@link #defaultTransport()}, tests hand in canned replies.
     */
    @FunctionalInterface
    interface Transport {
        Reply send(HttpRequest request) throws IOException, InterruptedException;
    }

    static Transport defaultTransport() {
        HttpClient client =
                HttpClient.newBuilder()
                        .connectTimeout(TIMEOUT)
                        // NEVER: a redirect chain is an SSRF primitive even against a fixed host,
                        // and a search API has
                        // no legitimate reason to redirect (same posture as BraveWebSearchClient).
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        return request -> {
            HttpResponse<InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                return new Reply(response.statusCode(), readCapped(in));
            }
        };
    }

    /**
     * Reads at most {@link #MAX_BODY_BYTES}; the rest is dropped (a truncated body fails to parse
     * -> no results).
     */
    static String readCapped(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            int room = MAX_BODY_BYTES - out.size();
            if (read >= room) {
                out.write(buffer, 0, room);
                break;
            }
            out.write(buffer, 0, read);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /**
     * Blank -> the exact refusal Brave gives; otherwise stripped and trimmed to {@link
     * #MAX_QUERY_CHARS}.
     */
    static String requireQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new WebSearchException("no search query was provided.");
        }
        String trimmed = query.strip();
        return trimmed.length() > MAX_QUERY_CHARS ? trimmed.substring(0, MAX_QUERY_CHARS) : trimmed;
    }

    static int clampCount(Integer count) {
        return count == null ? DEFAULT_RESULTS : Math.max(1, Math.min(MAX_RESULTS, count));
    }

    /** Null-safe clip to {@code max} chars (never null). */
    static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        String s = text.strip();
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * Only http(s) URLs may reach the model as a "result": drops javascript:, data:, file: and the
     * like.
     */
    static boolean isWebUrl(String url) {
        if (url == null || url.length() > MAX_URL_CHARS) {
            return false;
        }
        String lower = url.strip().toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /**
     * Null/blank -> null; otherwise clipped to a short date-ish string (providers send ISO instants
     * or free text).
     */
    static String shortDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.strip();
        // 2024-01-15T00:00:00Z -> 2024-01-15
        if (s.length() >= 10
                && Character.isDigit(s.charAt(0))
                && s.charAt(4) == '-'
                && s.charAt(7) == '-') {
            return s.substring(0, 10);
        }
        return clip(s, 40);
    }
}
