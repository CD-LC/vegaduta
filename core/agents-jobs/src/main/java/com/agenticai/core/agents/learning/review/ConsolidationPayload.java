package com.agenticai.core.agents.learning.review;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What an approved CONSOLIDATION proposal does, stored as the proposal's payload: {@code action} "merge" (the
 * {@code keepId} fact is rewritten with the approved text and every {@code dropIds} fact is forgotten) or "drop"
 * (only {@code dropIds}). {@code scope} is "agent" (then {@code agentId} names the tier) or "shared".
 *
 * <p>Ids are written with {@code x} in place of the hyphens. The proposal store scrubs card-like digit runs from
 * every stored string, and an id that happens to be mostly digits would otherwise be damaged; no run in the
 * {@code x} form is longer than twelve digits.
 */
public record ConsolidationPayload(
        String action, String scope, UUID agentId, UUID keepId, List<UUID> dropIds, Map<UUID, String> hashes) {

    /** A payload with no content fingerprints: the applier then checks only that each fact still exists. */
    public ConsolidationPayload(String action, String scope, UUID agentId, UUID keepId, List<UUID> dropIds) {
        this(action, scope, agentId, keepId, dropIds, Map.of());
    }

    public static final String MERGE = "merge";
    public static final String DROP = "drop";
    static final int MAX_DROPS = 10;

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", action);
        m.put("scope", scope);
        if (agentId != null) {
            m.put("agentId", encode(agentId));
        }
        if (keepId != null) {
            m.put("keepId", encode(keepId));
        }
        m.put("dropIds", dropIds.stream().map(ConsolidationPayload::encode).toList());
        if (!hashes.isEmpty()) {
            Map<String, String> h = new LinkedHashMap<>();
            hashes.forEach((id, fp) -> h.put(encode(id), fp));
            m.put("hashes", h);
        }
        return m;
    }

    /** Parses and validates a stored payload; throws {@link IllegalArgumentException} with a person-readable reason. */
    public static ConsolidationPayload parse(String json) {
        try {
            JsonNode n = JsonMappers.plain().readTree(json == null ? "{}" : json);
            String action = n.path("action").asText("");
            String scope = n.path("scope").asText("");
            if (!MERGE.equals(action) && !DROP.equals(action)) {
                throw new IllegalArgumentException("This tidy-up has no recognised action.");
            }
            if (!MemoryFactRef.AGENT.equals(scope) && !MemoryFactRef.SHARED.equals(scope)) {
                throw new IllegalArgumentException("This tidy-up does not say which memories it is about.");
            }
            UUID agentId = n.hasNonNull("agentId") ? decode(n.path("agentId").asText()) : null;
            if (MemoryFactRef.AGENT.equals(scope) && agentId == null) {
                throw new IllegalArgumentException("This tidy-up does not say which assistant's memories it is about.");
            }
            UUID keepId = n.hasNonNull("keepId") ? decode(n.path("keepId").asText()) : null;
            List<UUID> drops = new ArrayList<>();
            n.path("dropIds").forEach(d -> drops.add(decode(d.asText())));
            if (drops.isEmpty() || drops.size() > MAX_DROPS || drops.contains(keepId)) {
                throw new IllegalArgumentException("This tidy-up lists no valid memories to remove.");
            }
            if (MERGE.equals(action) != (keepId != null)) {
                throw new IllegalArgumentException("This tidy-up is not well formed.");
            }
            Map<UUID, String> hashes = new LinkedHashMap<>();
            n.path("hashes").fields().forEachRemaining(e -> hashes.put(decode(e.getKey()), e.getValue().asText()));
            return new ConsolidationPayload(action, scope, agentId, keepId, List.copyOf(drops), Map.copyOf(hashes));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("This tidy-up could not be read.");
        }
    }

    /**
     * A short fingerprint of a fact's wording, taken when the proposal is filed so the applier can tell the person
     * approved the fact they were shown. Groups of six hex characters joined by {@code x}: no run of digits long
     * enough for the card-number scrubber to touch.
     */
    public static String fingerprint(String content) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((content == null ? "" : content).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 9; i++) {
                if (i > 0 && i % 3 == 0) {
                    sb.append('x');
                }
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String encode(UUID id) {
        return id.toString().replace('-', 'x');
    }

    static UUID decode(String s) {
        try {
            return UUID.fromString(s.replace('x', '-'));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("This tidy-up could not be read.");
        }
    }
}
