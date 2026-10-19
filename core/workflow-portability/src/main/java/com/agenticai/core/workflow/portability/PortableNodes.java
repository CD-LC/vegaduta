package com.agenticai.core.workflow.portability;

import com.agenticai.core.agents.importer.AgentImportText;
import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small constructors the mappers share, so every imported node has the same shape: React Flow's
 * {@code {id, type, position, data: {label, config}}} plus two optional, display-only data keys the
 * canvas ignores - {@code needsReview} and {@code importNotes} - that keep the "check this one"
 * signal on the node itself after the import report is gone.
 */
final class PortableNodes {

    /**
     * The Transform operation with no template: emits its input unchanged, so it never alters or
     * runs anything.
     */
    static final String PASSTHROUGH_OPERATION = "template";

    private PortableNodes() {}

    static Map<String, Object> position(double x, double y) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("x", Math.round(x));
        p.put("y", Math.round(y));
        return p;
    }

    static WorkflowNodeDto node(
            String id,
            String type,
            String label,
            Map<String, Object> position,
            Map<String, Object> config,
            List<String> notes,
            boolean needsReview) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("label", label);
        data.put("config", config == null ? new LinkedHashMap<String, Object>() : config);
        if (needsReview) {
            data.put("needsReview", Boolean.TRUE);
        }
        if (notes != null && !notes.isEmpty()) {
            data.put("importNotes", new ArrayList<>(notes));
        }
        return new WorkflowNodeDto(id, type, position, data);
    }

    static WorkflowEdgeDto edge(String id, String source, String sourceHandle, String target) {
        return new WorkflowEdgeDto(id, source, target, sourceHandle, null);
    }

    /** A Transform node that only passes its input through. */
    static Map<String, Object> passthroughConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("operation", PASSTHROUGH_OPERATION);
        return config;
    }

    /**
     * The record of where an inert stand-in came from. Kept in the node's config under a key the
     * executor never reads, so the stand-in is harmless to run and a person can still see (and
     * copy) what the original was. {@code parameters} must already be credential-redacted by the
     * caller.
     */
    static Map<String, Object> importedFrom(
            String platform, String type, String name, Object parameters) {
        Map<String, Object> from = new LinkedHashMap<>();
        from.put("platform", platform);
        from.put("type", type == null ? "" : type);
        from.put("name", name == null ? "" : name);
        if (parameters != null) {
            from.put("parameters", parameters);
        }
        return from;
    }

    /**
     * Plain Java (maps, lists, strings, numbers) for a JSON tree, so a config map never holds
     * Jackson nodes.
     */
    static Object plain(ObjectMapper mapper, JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        return mapper.convertValue(node, Object.class);
    }

    /**
     * {@code plain} unchanged when its JSON is at most {@code capChars}, else a {@code {truncated,
     * preview}} stub.
     */
    static Object capped(ObjectMapper mapper, Object plain, int capChars) {
        try {
            String json = mapper.writeValueAsString(plain);
            if (json.length() <= capChars) {
                return plain;
            }
            Map<String, Object> stub = new LinkedHashMap<>();
            stub.put("truncated", Boolean.TRUE);
            stub.put("preview", AgentImportText.truncate(json, 4000));
            return stub;
        } catch (Exception e) {
            return null;
        }
    }

    static String slug(String text, String fallback) {
        if (text == null) {
            return fallback;
        }
        String s =
                text.toLowerCase(java.util.Locale.ROOT)
                        .replaceAll("[^a-z0-9]+", "-")
                        .replaceAll("^-+|-+$", "");
        if (s.length() > 40) {
            s = s.substring(0, 40).replaceAll("-+$", "");
        }
        return s.isEmpty() ? fallback : s;
    }
}
