package com.agenticai.core.agents.importer;

import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Detects which platform a payload came from when the request says {@code source: "auto"}. Pure,
 * and deliberately conservative: it answers only on distinctive markers and otherwise returns empty
 * so the caller can ask the user to pick a source rather than mis-convert.
 */
public final class AgentImportDetector {

    private AgentImportDetector() {}

    public static Optional<AgentImportSource> detect(JsonNode root) {
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }
        // Flowise: {nodes: [...], edges: [...]} or a stored chatflow record carrying flowData.
        if (root.has("nodes") && root.get("nodes").isArray()) {
            return Optional.of(AgentImportSource.FLOWISE);
        }
        if (AgentImportJson.text(root, "flowData") != null) {
            return Optional.of(AgentImportSource.FLOWISE);
        }
        // OpenAI Assistants API object: object == "assistant" or an asst_ id.
        String object = AgentImportJson.text(root, "object");
        String id = AgentImportJson.text(root, "id");
        if ("assistant".equals(object) || (id != null && id.startsWith("asst_"))) {
            return Optional.of(AgentImportSource.OPENAI_ASSISTANT);
        }
        // Agents SDK style: Agent(...) keyword arguments.
        if (root.has("instructions")
                || root.has("handoffs")
                || root.has("model_settings")
                || root.has("modelSettings")
                || root.has("input_guardrails")
                || root.has("inputGuardrails")
                || root.has("output_guardrails")
                || root.has("outputGuardrails")
                || root.has("handoff_description")
                || root.has("handoffDescription")) {
            return Optional.of(AgentImportSource.OPENAI_AGENTS);
        }
        return Optional.empty();
    }
}
