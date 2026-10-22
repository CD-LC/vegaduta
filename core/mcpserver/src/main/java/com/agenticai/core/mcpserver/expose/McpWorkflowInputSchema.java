package com.agenticai.core.mcpserver.expose;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure helpers for the {@code workflow_<slug>} tools' arguments.
 *
 * <p><b>Honest scope:</b> a workflow's trigger takes ONE plain-string input today ({@code
 * WorkflowService#run(id, input, requestedBy)}) - the engine has no typed input contract. So the
 * JSON Schema advertised to the MCP client is derived from the Trigger node's config only when its
 * author declared one ({@code inputSchema} holding a JSON-Schema-like object, or a {@code fields}
 * list of {@code {key|name, type, required, label}}); otherwise it is an open object. Either way
 * the arguments are handed to the workflow as text: a lone {@code input} string is passed through
 * verbatim, anything else is passed as its JSON text.
 */
public final class McpWorkflowInputSchema {

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private McpWorkflowInputSchema() {
        // utility class
    }

    /** The open-object schema used when the trigger declares nothing. */
    public static String openSchemaJson() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "object");
        root.put(
                "description",
                "Input for the workflow. Pass {\"input\": \"some text\"} to give the trigger a plain string; "
                        + "any other JSON object is handed to the trigger as its JSON text.");
        ObjectNode properties = root.putObject("properties");
        properties
                .putObject("input")
                .put("description", "Plain-text input for the workflow's trigger (optional).");
        root.put("additionalProperties", true);
        return root.toString();
    }

    /**
     * Schema for a workflow whose Trigger node has the given config (may be null/empty). Never
     * throws: anything unrecognised falls back to {@link #openSchemaJson()}.
     */
    @SuppressWarnings("unchecked")
    public static String fromTriggerConfig(Map<String, Object> triggerConfig) {
        if (triggerConfig == null || triggerConfig.isEmpty()) {
            return openSchemaJson();
        }
        try {
            Object declared = triggerConfig.get("inputSchema");
            if (declared instanceof Map<?, ?> declaredMap
                    && declaredMap.get("properties") instanceof Map<?, ?>) {
                ObjectNode root = MAPPER.createObjectNode();
                root.put("type", "object");
                root.set("properties", MAPPER.valueToTree(declaredMap.get("properties")));
                Object required = declaredMap.get("required");
                if (required instanceof List<?> requiredList && !requiredList.isEmpty()) {
                    ArrayNode requiredNode = root.putArray("required");
                    for (Object name : requiredList) {
                        if (name instanceof String text && !text.isBlank()) {
                            requiredNode.add(text);
                        }
                    }
                }
                return root.toString();
            }
            Object fields = triggerConfig.get("fields");
            if (fields instanceof List<?> fieldList && !fieldList.isEmpty()) {
                ObjectNode root = MAPPER.createObjectNode();
                root.put("type", "object");
                ObjectNode properties = root.putObject("properties");
                ArrayNode required = MAPPER.createArrayNode();
                for (Object field : fieldList) {
                    if (!(field instanceof Map<?, ?> fieldMap)) {
                        continue;
                    }
                    Object key =
                            fieldMap.get("key") != null
                                    ? fieldMap.get("key")
                                    : fieldMap.get("name");
                    if (!(key instanceof String keyText) || keyText.isBlank()) {
                        continue;
                    }
                    ObjectNode property = properties.putObject(keyText);
                    property.put("type", jsonType(fieldMap.get("type")));
                    Object label =
                            fieldMap.get("label") != null
                                    ? fieldMap.get("label")
                                    : fieldMap.get("description");
                    if (label instanceof String labelText && !labelText.isBlank()) {
                        property.put("description", labelText);
                    }
                    if (Boolean.TRUE.equals(fieldMap.get("required"))) {
                        required.add(keyText);
                    }
                }
                if (properties.isEmpty()) {
                    return openSchemaJson();
                }
                if (!required.isEmpty()) {
                    root.set("required", required);
                }
                return root.toString();
            }
        } catch (RuntimeException e) {
            // A malformed author-supplied schema must never break tools/list - open object instead.
            return openSchemaJson();
        }
        return openSchemaJson();
    }

    /** Maps the trigger author's type word to a JSON Schema type; anything unknown is a string. */
    static String jsonType(Object declared) {
        if (!(declared instanceof String text)) {
            return "string";
        }
        return switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "number", "integer", "int", "float" -> "number";
            case "boolean", "bool" -> "boolean";
            case "object", "json" -> "object";
            case "array", "list" -> "array";
            default -> "string";
        };
    }

    /**
     * The text handed to {@code WorkflowService} as the run's input: empty for no arguments, the
     * raw string for a lone {@code {"input": "text"}}, otherwise the arguments' JSON text.
     */
    public static String toRunInput(JsonNode arguments) {
        if (arguments == null || arguments.isNull() || arguments.isMissingNode()) {
            return "";
        }
        if (arguments.isObject()
                && arguments.size() == 1
                && arguments.has("input")
                && arguments.get("input").isTextual()) {
            return arguments.get("input").asText();
        }
        if (arguments.isObject() && arguments.isEmpty()) {
            return "";
        }
        return arguments.toString();
    }
}
