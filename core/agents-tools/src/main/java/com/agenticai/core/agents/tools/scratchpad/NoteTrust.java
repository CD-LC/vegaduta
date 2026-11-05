package com.agenticai.core.agents.tools.scratchpad;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;
import java.util.function.Supplier;

/**
 * Whether a scratchpad note may be read back (column {@code trust}, migration V678) and what
 * tainted the turn that wrote it ({@code source_taint}). Deliberately free of the memory package's
 * types: this package must not depend on {@code agents.memory} (package-cycle baseline).
 */
public record NoteTrust(boolean quarantined, List<String> sourceTaint) {

    public static final NoteTrust TRUSTED = new NoteTrust(false, List.of());

    static final String UNAVAILABLE = "taint-state-unavailable";

    public NoteTrust {
        sourceTaint = sourceTaint == null ? List.of() : List.copyOf(sourceTaint);
    }

    /**
     * The trust a write gets from the turn's taint: {@code taintSources} returns what tainted the
     * turn, or null when it is clean. No supplier, or one that throws, means the taint state cannot
     * be read, and the write is held (fail closed).
     */
    static NoteTrust of(Supplier<List<String>> taintSources) {
        try {
            List<String> sources = taintSources == null ? List.of(UNAVAILABLE) : taintSources.get();
            if (sources == null) {
                return TRUSTED;
            }
            return new NoteTrust(true, sources.isEmpty() ? List.of("unknown") : sources);
        } catch (RuntimeException e) {
            return new NoteTrust(true, List.of(UNAVAILABLE));
        }
    }

    String sourcesJson() {
        if (sourceTaint.isEmpty()) {
            return null;
        }
        try {
            return JsonMappers.plain().writeValueAsString(sourceTaint);
        } catch (Exception e) {
            return null;
        }
    }

    static List<String> parseSources(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JsonMappers.plain().readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
