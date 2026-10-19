package com.agenticai.core.agents.tools.web;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Per-turn evidence store shared by {@code deep_research} and {@code verify_citations}. One
 * instance per tool resolution (i.e. per chat turn): it dedupes sources across repeated calls,
 * enforces the turn-wide fetch ceiling, and keeps the fetched text so citations can be verified
 * against the exact words later. Thread-safe.
 */
public final class DeepResearchSession {

    /** One fetched page. {@code text} is the span-offset space for citations. */
    public record Source(String id, String url, String title, String text) {}

    private final int maxFetches;
    private final int maxSearches;
    private final Map<String, Source> byKey = new LinkedHashMap<>();
    private final Map<String, Source> byId = new LinkedHashMap<>();

    /** Fetch ATTEMPTS reserved so far (successful or not). */
    private int fetches;

    private int searches;

    public DeepResearchSession(int maxFetches) {
        this(maxFetches, 12);
    }

    public DeepResearchSession(int maxFetches, int maxSearches) {
        this.maxFetches = Math.max(1, maxFetches);
        this.maxSearches = Math.max(1, maxSearches);
    }

    public synchronized int searchesRemaining() {
        return Math.max(0, maxSearches - searches);
    }

    /** Search queries reserved so far this turn (across every deep_research call). */
    public synchronized int searchesUsed() {
        return searches;
    }

    /** Page-fetch attempts reserved so far this turn (successful or not, across every call). */
    public synchronized int fetchesUsed() {
        return fetches;
    }

    /** This turn's ceiling on search queries. */
    public int searchLimit() {
        return maxSearches;
    }

    /** This turn's ceiling on page-fetch attempts. */
    public int fetchLimit() {
        return maxFetches;
    }

    /** Deterministic one-line usage summary, e.g. {@code "Used 3/12 searches, 5/12 page reads"}. */
    public synchronized String usageLine() {
        return "Used "
                + searches
                + "/"
                + maxSearches
                + " searches, "
                + fetches
                + "/"
                + maxFetches
                + " page reads";
    }

    /**
     * Reserves up to {@code wanted} platform-paid search queries for this turn; returns how many
     * were granted.
     */
    public synchronized int reserveSearches(int wanted) {
        int granted = Math.max(0, Math.min(wanted, maxSearches - searches));
        searches += granted;
        return granted;
    }

    /**
     * Reserves up to {@code wanted} page-fetch attempts for this turn; returns how many were
     * granted. Attempts count whether or not the fetch later succeeds, so failing URLs cannot be
     * retried forever.
     */
    public synchronized int reserveFetches(int wanted) {
        int granted = Math.max(0, Math.min(wanted, maxFetches - fetches));
        fetches += granted;
        return granted;
    }

    public synchronized Source findByUrl(String url) {
        return byKey.get(normalizeUrl(url));
    }

    public synchronized Source get(String id) {
        return id == null ? null : byId.get(id.strip().toUpperCase(Locale.ROOT));
    }

    public synchronized int fetchesRemaining() {
        return Math.max(0, maxFetches - fetches);
    }

    /**
     * The URLs of every page this session actually fetched (the {@code byId} sources), in id order.
     */
    public synchronized java.util.Set<String> fetchedUrls() {
        java.util.Set<String> urls = new java.util.LinkedHashSet<>();
        for (Source s : byId.values()) {
            urls.add(s.url());
        }
        return urls;
    }

    public synchronized int sourceCount() {
        return byId.size();
    }

    /**
     * Registers a fetched page (its attempt was already counted by {@link #reserveFetches}) and
     * returns it with its stable id.
     */
    public synchronized Source add(String url, String title, String text) {
        String key = normalizeUrl(url);
        Source existing = byKey.get(key);
        if (existing != null) {
            return existing;
        }
        Source s =
                new Source(
                        "S" + (byId.size() + 1),
                        url,
                        title == null ? "" : title,
                        text == null ? "" : text);
        byKey.put(key, s);
        byId.put(s.id(), s);
        return s;
    }

    /**
     * Canonical key for dedupe: scheme and fragment dropped, host lower-cased without {@code www.},
     * tracking parameters removed, trailing slash trimmed.
     */
    public static String normalizeUrl(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.strip();
        try {
            URI u = new URI(trimmed);
            String host = u.getHost();
            if (host == null) {
                return trimmed.toLowerCase(Locale.ROOT);
            }
            host = host.toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }
            String path = u.getRawPath() == null ? "" : u.getRawPath();
            while (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            if (path.equals("/")) {
                path = "";
            }
            StringBuilder query = new StringBuilder();
            if (u.getRawQuery() != null) {
                for (String pair : u.getRawQuery().split("&")) {
                    String name = pair.contains("=") ? pair.substring(0, pair.indexOf('=')) : pair;
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (pair.isEmpty()
                            || lower.startsWith("utm_")
                            || lower.equals("fbclid")
                            || lower.equals("gclid")) {
                        continue;
                    }
                    query.append(query.length() == 0 ? "?" : "&").append(pair);
                }
            }
            int port = u.getPort();
            return host + (port > 0 && port != 80 && port != 443 ? ":" + port : "") + path + query;
        } catch (Exception e) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
    }
}
