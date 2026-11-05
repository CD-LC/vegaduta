package com.agenticai.core.agents.memory;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/**
 * Trust and taint sources recorded with one memory row (columns {@code trust}, {@code
 * source_taint}).
 */
public record MemoryProvenance(MemoryTrust trust, List<String> sourceTaint) {

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    /** A person-authored or clean-turn write. */
    public static final MemoryProvenance TRUSTED =
            new MemoryProvenance(MemoryTrust.TRUSTED, List.of());

    public MemoryProvenance {
        trust = trust == null ? MemoryTrust.QUARANTINED : trust;
        sourceTaint = sourceTaint == null ? List.of() : List.copyOf(sourceTaint);
    }

    public boolean quarantined() {
        return trust == MemoryTrust.QUARANTINED;
    }

    /** JSON array for the {@code source_taint} column, or null when there are no sources. */
    public String sourcesJson() {
        if (sourceTaint.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(sourceTaint);
        } catch (Exception e) {
            return null;
        }
    }

    public static List<String> parseSources(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
