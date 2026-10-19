package com.agenticai.core.workflow.portability;

import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns {@code @placeholder:<key>} tokens in node config into real values: the id the importing
 * user mapped the placeholder to, or - when nothing was mapped - nothing at all. The field is
 * removed rather than left holding a foreign id or a token, so a DRAFT never carries a reference
 * that points at another tenant (or at nothing) and the canvas shows the field as "not chosen yet".
 * Every node that lost a field also gets an {@code importNotes} line and {@code needsReview}, so
 * the reminder survives the import report.
 *
 * <p>Pure: returns new nodes and never mutates its input.
 */
final class PlaceholderResolver {

    private static final int MAX_DEPTH = 40;

    /**
     * @param nodes the nodes with every token resolved or removed
     * @param unresolved placeholder keys that were removed because nothing was mapped to them
     * @param missing node id to the config fields that lost their value there
     */
    record Result(
            List<WorkflowNodeDto> nodes,
            Set<String> unresolved,
            Map<String, List<String>> missing) {}

    private PlaceholderResolver() {}

    static Result apply(
            List<WorkflowNodeDto> nodes,
            Map<String, String> mappings,
            PlaceholderRegistry registry) {
        List<WorkflowNodeDto> out = new ArrayList<>();
        Set<String> unresolved = new LinkedHashSet<>();
        Map<String, List<String>> missing = new LinkedHashMap<>();
        for (WorkflowNodeDto node : nodes) {
            Map<String, Object> data =
                    node.data() == null ? new LinkedHashMap<>() : copyMap(node.data(), 0);
            Object config = data.get("config");
            List<String> removedKeys = new ArrayList<>();
            List<String> removedFields = new ArrayList<>();
            if (config instanceof Map<?, ?> configMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) configMap;
                data.put("config", resolveMap(typed, mappings, removedKeys, removedFields, "", 0));
            }
            if (!removedKeys.isEmpty()) {
                List<String> notes = new ArrayList<>();
                Object existing = data.get("importNotes");
                if (existing instanceof List<?> list) {
                    for (Object item : list) {
                        notes.add(String.valueOf(item));
                    }
                }
                for (String key : removedKeys) {
                    PlaceholderRegistry.Entry entry = registry.get(key);
                    notes.add(
                            "Choose a value for "
                                    + describe(entry, key)
                                    + " (placeholder "
                                    + key
                                    + ").");
                    unresolved.add(key);
                }
                data.put("importNotes", notes);
                data.put("needsReview", Boolean.TRUE);
                missing.put(node.id(), removedFields);
            }
            out.add(new WorkflowNodeDto(node.id(), node.type(), node.position(), data));
        }
        return new Result(out, unresolved, missing);
    }

    private static String describe(PlaceholderRegistry.Entry entry, String key) {
        if (entry == null) {
            return "an unknown reference";
        }
        String label = entry.label().isBlank() ? "" : " '" + entry.label() + "'";
        return switch (entry.kind()) {
            case PlaceholderRegistry.AGENT -> "the agent" + label;
            case PlaceholderRegistry.KNOWLEDGE_BASE -> "the knowledge base" + label;
            case PlaceholderRegistry.WORKFLOW -> "the workflow" + label;
            case PlaceholderRegistry.CONTACT_GROUP -> "the contact group" + label;
            case PlaceholderRegistry.DEVICE -> "the device" + label;
            case PlaceholderRegistry.DESTINATION -> "the destination" + label;
            default -> "a value" + label;
        };
    }

    private static Map<String, Object> resolveMap(
            Map<String, Object> map,
            Map<String, String> mappings,
            List<String> removedKeys,
            List<String> removedFields,
            String path,
            int depth) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (depth > MAX_DEPTH) {
            return out;
        }
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object value = entry.getValue();
            String field = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
            if (value instanceof String text) {
                String key = PlaceholderRegistry.keyOfToken(text);
                if (key == null) {
                    out.put(entry.getKey(), value);
                } else if (mappings.containsKey(key)) {
                    out.put(entry.getKey(), mappings.get(key));
                } else {
                    removedKeys.add(key);
                    removedFields.add(field);
                }
            } else if (value instanceof Map<?, ?> nested) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) nested;
                out.put(
                        entry.getKey(),
                        resolveMap(typed, mappings, removedKeys, removedFields, field, depth + 1));
            } else if (value instanceof List<?> list) {
                out.put(
                        entry.getKey(),
                        resolveList(list, mappings, removedKeys, removedFields, field, depth + 1));
            } else {
                out.put(entry.getKey(), value);
            }
        }
        return out;
    }

    private static List<Object> resolveList(
            List<?> list,
            Map<String, String> mappings,
            List<String> removedKeys,
            List<String> removedFields,
            String path,
            int depth) {
        List<Object> out = new ArrayList<>();
        if (depth > MAX_DEPTH) {
            return out;
        }
        for (Object item : list) {
            if (item instanceof String text) {
                String key = PlaceholderRegistry.keyOfToken(text);
                if (key == null) {
                    out.add(item);
                } else if (mappings.containsKey(key)) {
                    out.add(mappings.get(key));
                } else {
                    removedKeys.add(key);
                    removedFields.add(path);
                }
            } else if (item instanceof Map<?, ?> nested) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) nested;
                out.add(resolveMap(typed, mappings, removedKeys, removedFields, path, depth + 1));
            } else if (item instanceof List<?> inner) {
                out.add(resolveList(inner, mappings, removedKeys, removedFields, path, depth + 1));
            } else {
                out.add(item);
            }
        }
        return out;
    }

    private static Map<String, Object> copyMap(Map<String, Object> source, int depth) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (depth > MAX_DEPTH) {
            return out;
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            out.put(entry.getKey(), copy(entry.getValue(), depth + 1));
        }
        return out;
    }

    private static Object copy(Object value, int depth) {
        if (depth > MAX_DEPTH) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), copy(entry.getValue(), depth + 1));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) {
                out.add(copy(item, depth + 1));
            }
            return out;
        }
        return value;
    }
}
