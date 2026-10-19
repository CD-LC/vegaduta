package com.agenticai.core.quality.dataset;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What an eval run actually replayed: the agent's sampling configuration and
 * a fingerprint of its prompt and tool allow-list, captured at trigger time
 * (V375). Two runs with equal {@link #hash()} used the same model, sampling
 * parameters, system prompt and tool allow-list - the precondition for
 * comparing their scores at all.
 *
 * <p>The system prompt is recorded only as a SHA-256 (it can hold tenant
 * secrets or PII and the bundle is meant to be shared). {@code tools} is the
 * agent's explicit allow-list, sorted; an EMPTY list means "no restriction"
 * (the agent's own convention), not "no tools".
 */
public record AgentConfigSnapshot(
    String model,
    Double temperature,
    Double topP,
    Integer maxTokens,
    Integer seed,
    String systemPromptSha256,
    List<String> tools
) {

    /** Sorted-key canonical JSON of the snapshot - the stored document and the hash input. */
    public String canonicalJson() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("model", model);
        doc.put("temperature", temperature);
        doc.put("topP", topP);
        doc.put("maxTokens", maxTokens);
        doc.put("seed", seed);
        doc.put("systemPromptSha256", systemPromptSha256);
        doc.put("tools", tools == null ? List.of() : tools);
        return CanonicalJson.write(doc);
    }

    public String hash() {
        return CanonicalJson.sha256Hex(canonicalJson());
    }
}
