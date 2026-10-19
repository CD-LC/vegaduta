package com.agenticai.core.workflow.dto;

import java.util.Map;

/**
 * Mirrors a React Flow node object exactly ({@code id}, {@code type},
 * {@code position}, {@code data}) so the graph JSON round-trips between the
 * canvas and this API with zero translation. {@code data.config} (a nested
 * map inside {@code data}) is where a node's actual behavior lives - e.g.
 * an Agent node's {@code data.config.agentId}, a Knowledge node's
 * {@code data.config.collectionId}/{@code topK}, a Condition node's
 * {@code data.config.contains}. Left as {@code Map<String, Object>} rather
 * than a typed hierarchy per node type: the node taxonomy will keep growing
 * (PRD Section 4 already lists nine types, three not yet executable), and a
 * typed sealed hierarchy would force a coordinated Java + JSON schema change
 * every time a new node type's config shape is added.
 */
public record WorkflowNodeDto(
    String id,
    String type,
    Map<String, Object> position,
    Map<String, Object> data
) {

    @SuppressWarnings("unchecked")
    public Map<String, Object> config() {
        if (data == null) {
            return Map.of();
        }
        Object config = data.get("config");
        return config instanceof Map ? (Map<String, Object>) config : Map.of();
    }

    public String label() {
        if (data == null) {
            return id;
        }
        Object label = data.get("label");
        return label != null ? label.toString() : id;
    }
}
