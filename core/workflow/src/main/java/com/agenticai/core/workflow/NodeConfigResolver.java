package com.agenticai.core.workflow;

import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Tolerant readers for node config values whose stored shape varies (a parsed object, a JSON string
 * from a textarea, comma separated text). Shared by the Agent and Supervisor handlers.
 */
@Component
class NodeConfigResolver {

    private static final Logger log = LoggerFactory.getLogger(NodeConfigResolver.class);

    private final ObjectMapper objectMapper;

    NodeConfigResolver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Tolerant of {@code context_data} arriving as an already-parsed {@code Map} or a raw JSON
     * string (textarea input).
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> resolveContextData(Object raw) {
        if (raw instanceof Map<?, ?> map && !map.isEmpty()) {
            return (Map<String, Object>) map;
        }
        if (raw instanceof String s && !s.isBlank()) {
            try {
                JsonNode node = objectMapper.readTree(s);
                if (node.isObject()) {
                    return objectMapper.convertValue(node, Map.class);
                }
            } catch (Exception e) {
                log.warn(
                        "Malformed context_data JSON on Agent node - ignoring: {}", e.getMessage());
            }
        }
        return Map.of();
    }

    /**
     * Tolerant of {@code knowledge_collection_ids} arriving as a {@code List}, a JSON array string,
     * or comma-separated text.
     */
    List<UUID> resolveKnowledgeCollectionIds(Object raw) {
        List<String> rawIds;
        if (raw instanceof java.util.Collection<?> collection) {
            rawIds = collection.stream().map(String::valueOf).toList();
        } else if (raw instanceof String s && !s.isBlank()) {
            try {
                JsonNode arrayNode = objectMapper.readTree(s);
                rawIds =
                        arrayNode.isArray()
                                ? java.util.stream.StreamSupport.stream(
                                                arrayNode.spliterator(), false)
                                        .map(JsonNode::asText)
                                        .toList()
                                : List.of(s.split(","));
            } catch (Exception e) {
                rawIds = List.of(s.split(","));
            }
        } else {
            return List.of();
        }
        List<UUID> ids = new ArrayList<>();
        for (String rawId : rawIds) {
            try {
                ids.add(UUID.fromString(rawId.trim()));
            } catch (IllegalArgumentException e) {
                log.warn(
                        "Malformed knowledge_collection_ids entry '{}' on Agent node - skipping.",
                        rawId);
            }
        }
        return ids;
    }

    /**
     * Config-declared routes, tolerant of {@code routes} arriving either as an already-parsed
     * {@code List} (the normal case - {@link WorkflowNodeDto#config()} is deserialized straight off
     * the saved graph's JSON) or as a raw JSON string (defensive - matches this class's own "never
     * trust the shape of a config value" posture elsewhere, e.g. {@link #stringConfig}).
     */
    List<Map<String, String>> resolveRoutes(WorkflowNodeDto node) {
        Object raw = node.config().get("routes");
        if (raw == null) {
            return List.of();
        }
        try {
            JsonNode arrayNode =
                    raw instanceof String s
                            ? objectMapper.readTree(s.isBlank() ? "[]" : s)
                            : objectMapper.valueToTree(raw);
            if (!arrayNode.isArray()) {
                return List.of();
            }
            List<Map<String, String>> routes = new ArrayList<>();
            for (JsonNode routeNode : arrayNode) {
                String handle =
                        routeNode.path("handle").isMissingNode()
                                ? null
                                : routeNode.path("handle").asText(null);
                if (handle == null || handle.isBlank()) {
                    continue;
                }
                String label =
                        routeNode.path("label").isMissingNode()
                                ? handle
                                : routeNode.path("label").asText(handle);
                routes.add(
                        Map.of(
                                "handle",
                                handle,
                                "label",
                                label == null || label.isBlank() ? handle : label));
            }
            return routes;
        } catch (Exception e) {
            // Falls open to "no routes" (executeSupervisorNode's caller
            // already handles that as a skipped node with an explanatory
            // message) rather than failing the whole run over a malformed
            // routes config - but logged, so a customer reporting "my
            // Supervisor node isn't routing" leaves an operator a trail
            // instead of this looking like it silently did nothing.
            log.warn(
                    "Malformed 'routes' config on Supervisor node '{}' - treating as no routes.",
                    node.id(),
                    e);
            return List.of();
        }
    }
}
