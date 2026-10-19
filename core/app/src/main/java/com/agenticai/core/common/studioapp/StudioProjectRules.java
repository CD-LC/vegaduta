package com.agenticai.core.common.studioapp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The pure rules behind studio project sync - id shape, size limits,
 * version de-duplication and the history cap - kept free of persistence so
 * they are unit-testable without a database.
 */
final class StudioProjectRules {

    static final int MAX_VERSIONS = 50;
    static final int MAX_HTML_CHARS = 1_000_000;
    /** Per request, across all versions - bounds memory for one sync. */
    static final long MAX_TOTAL_CHARS = 8_000_000L;
    static final int MAX_SETTINGS_CHARS = 64 * 1024;
    static final int MAX_NAME_CHARS = 120;
    static final int MAX_NOTE_CHARS = 400;

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    /** Mirrors the web store's VersionSource; anything else is stored as "manual". */
    private static final Set<String> SOURCES = Set.of(
        "template", "device", "cloud", "design", "manual", "restore", "connect", "publish");

    /** One incoming version as the studio sends it. */
    record VersionIn(long at, String note, String source, String html) {
    }

    private StudioProjectRules() {
    }

    static boolean validId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max);
    }

    /**
     * Validates and normalises an incoming version list: drops blank/null
     * entries, de-duplicates by timestamp (last write wins), orders oldest
     * first and keeps only the newest {@link #MAX_VERSIONS}.
     *
     * @throws IllegalArgumentException {@code version_too_large} when one HTML
     *         document exceeds {@link #MAX_HTML_CHARS}
     */
    static List<VersionIn> sanitize(List<VersionIn> incoming) {
        TreeMap<Long, VersionIn> byAt = new TreeMap<>();
        if (incoming != null) {
            for (VersionIn v : incoming) {
                if (v == null || v.html() == null || v.html().isBlank() || v.at() <= 0) {
                    continue;
                }
                if (v.html().length() > MAX_HTML_CHARS) {
                    throw new IllegalArgumentException("version_too_large");
                }
                String source = v.source() != null && SOURCES.contains(v.source()) ? v.source() : "manual";
                byAt.put(v.at(), new VersionIn(v.at(), clip(v.note(), MAX_NOTE_CHARS), source, v.html()));
            }
        }
        List<VersionIn> ordered = new ArrayList<>(byAt.values());
        ordered.sort(Comparator.comparingLong(VersionIn::at));
        int from = Math.max(0, ordered.size() - MAX_VERSIONS);
        List<VersionIn> kept = List.copyOf(ordered.subList(from, ordered.size()));
        long total = 0;
        for (VersionIn v : kept) {
            total += v.html().length();
        }
        if (total > MAX_TOTAL_CHARS) {
            throw new IllegalArgumentException("payload_too_large");
        }
        return kept;
    }

    /** Timestamps to delete so only the newest {@link #MAX_VERSIONS} remain. */
    static List<Long> surplus(List<Long> atsOldestFirst) {
        List<Long> sorted = new ArrayList<>(atsOldestFirst);
        sorted.sort(Comparator.naturalOrder());
        int extra = sorted.size() - MAX_VERSIONS;
        return extra <= 0 ? List.of() : List.copyOf(sorted.subList(0, extra));
    }
}
