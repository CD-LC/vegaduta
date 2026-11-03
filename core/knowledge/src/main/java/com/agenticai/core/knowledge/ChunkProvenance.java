package com.agenticai.core.knowledge;

import com.agenticai.core.json.JsonMappers;
import java.util.List;

/**
 * Trust recorded with the chunks one ingestion writes ({@code knowledge_chunks.trust} and {@code
 * source_taint}, migration V677). QUARANTINED chunks are stored but never retrieved until a person
 * approves them: text an agent saved on a turn that read attacker-authored content is still
 * attacker-authored when it is retrieved next month (OWASP ASI06 / LLM04).
 */
public record ChunkProvenance(boolean quarantined, List<String> sourceTaint) {

    /** A person-authored upload, or an agent write on a clean turn. */
    public static final ChunkProvenance TRUSTED = new ChunkProvenance(false, List.of());

    public ChunkProvenance {
        sourceTaint = sourceTaint == null ? List.of() : List.copyOf(sourceTaint);
    }

    public static ChunkProvenance quarantined(List<String> sources) {
        return new ChunkProvenance(true, sources);
    }

    public String trustName() {
        return quarantined ? "QUARANTINED" : "TRUSTED";
    }

    /** JSON array for the {@code source_taint} column, or null when there are no sources. */
    public String sourcesJson() {
        if (sourceTaint.isEmpty()) {
            return null;
        }
        try {
            return JsonMappers.plain().writeValueAsString(sourceTaint);
        } catch (Exception e) {
            return null;
        }
    }
}
