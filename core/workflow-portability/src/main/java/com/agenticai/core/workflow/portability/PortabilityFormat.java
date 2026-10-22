package com.agenticai.core.workflow.portability;

import com.agenticai.core.agents.importer.AgentImportJson;
import java.util.Locale;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * The workflow file formats this feature reads. {@link #wire()} is the value the API and the web
 * client use.
 */
public enum PortabilityFormat {
    VEGADUTA("vegaduta"),
    N8N("n8n"),
    DIFY("dify");

    public static final String AUTO = "auto";

    private final String wire;

    PortabilityFormat(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static boolean isAuto(String requested) {
        return requested == null || requested.isBlank() || AUTO.equalsIgnoreCase(requested.trim());
    }

    public static Optional<PortabilityFormat> parse(String requested) {
        if (requested == null) {
            return Optional.empty();
        }
        String s = requested.trim().toLowerCase(Locale.ROOT);
        for (PortabilityFormat f : values()) {
            if (f.wire.equals(s)) {
                return Optional.of(f);
            }
        }
        return Optional.empty();
    }

    /**
     * Which format a parsed document is, from distinctive markers only; empty when it is none of
     * the three so the caller can ask the person to choose rather than mis-convert.
     */
    public static Optional<PortabilityFormat> detect(JsonNode root) {
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }
        if (WorkflowDslV1.FORMAT.equals(AgentImportJson.text(root, "format"))) {
            return Optional.of(VEGADUTA);
        }
        // Dify DSL: app + workflow.graph (kind: app). A chat/agent app has `model_config` instead
        // of a graph.
        JsonNode workflow = AgentImportJson.obj(root, "workflow");
        if (workflow != null && AgentImportJson.obj(workflow, "graph") != null) {
            return Optional.of(DIFY);
        }
        if ("app".equals(AgentImportJson.text(root, "kind"))
                && AgentImportJson.obj(root, "app") != null) {
            return Optional.of(DIFY);
        }
        // n8n: nodes[] keyed to a connections{} object by node NAME.
        if (AgentImportJson.arr(root, "nodes") != null
                && AgentImportJson.obj(root, "connections") != null) {
            return Optional.of(N8N);
        }
        if (root.has("schemaVersion") && AgentImportJson.arr(root, "nodes") != null) {
            return Optional.of(VEGADUTA);
        }
        return Optional.empty();
    }
}
