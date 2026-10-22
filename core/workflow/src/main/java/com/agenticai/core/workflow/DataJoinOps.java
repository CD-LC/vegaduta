package com.agenticai.core.workflow;

import static com.agenticai.core.workflow.DataPathOps.get;
import static com.agenticai.core.workflow.DataValues.isAbsent;
import static com.agenticai.core.workflow.DataValues.isEmpty;
import static com.agenticai.core.workflow.DataValues.outputText;
import static com.agenticai.core.workflow.DataValues.text;
import static com.agenticai.core.workflow.NumberCanon.canonicalNumber;
import static com.agenticai.core.workflow.NumberCanon.numberText;
import static com.agenticai.core.workflow.WorkflowDataOps.JOIN_TYPES;
import static com.agenticai.core.workflow.WorkflowDataOps.MAX_ITEMS;
import static com.agenticai.core.workflow.WorkflowDataOps.MERGE_MODES;
import static com.agenticai.core.workflow.WorkflowDataOps.intOrNull;
import static com.agenticai.core.workflow.WorkflowDataOps.str;

import com.agenticai.core.workflow.WorkflowDataOps.Outcome;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * The {@code merge} data operation (append / join / first_non_empty) and its keyed join ({@code
 * left | inner | outer}). Extracted from {@link WorkflowDataOps}; see {@link #merge} for the
 * contract.
 */
final class DataJoinOps {

    private DataJoinOps() {}

    /**
     * {@code mode: append|join|first_non_empty}, {@code key} + {@code joinType: left|inner|outer}
     * (join), {@code sources: [text]} (each already expression-resolved, typically {@code {{
     * $node["A"].output }}}), {@code minInputs}.
     *
     * <p>The inputs being combined are, in order of preference, the {@code sources} (a blank source
     * is an absent input, e.g. the other side of a branch that did not run) or - when none are
     * configured - the elements of the node's own input array. That second form is the join after a
     * Parallel / Fan-out / Loop node, whose output is exactly such an array (one entry per branch
     * or item; entries that are JSON text are parsed). The engine walks ONE path per run and a node
     * executes once, so this node cannot suspend until N independent branches have all arrived; it
     * combines whatever is present when it runs, and {@code minInputs} turns "fewer than N inputs
     * are present" into a visible SKIPPED node (input passed through) instead of a silently thin
     * result.
     */
    static Outcome merge(ObjectMapper mapper, Map<String, Object> config, String input) {
        String mode =
                str(config.get("mode")) == null || str(config.get("mode")).isBlank()
                        ? "append"
                        : str(config.get("mode")).strip().toLowerCase(Locale.ROOT);
        if (!MERGE_MODES.contains(mode)) {
            throw new IllegalArgumentException(
                    "Unknown merge mode '"
                            + mode
                            + "' (expected append, join or first_non_empty).");
        }
        List<String> log = new ArrayList<>();
        List<JsonNode> present = new ArrayList<>();
        int declared;
        Object rawSources = config.get("sources");
        List<String> sources = rawSources == null ? List.of() : sourceTexts(mapper, rawSources);
        if (!sources.isEmpty()) {
            declared = sources.size();
            for (String s : sources) {
                JsonNode node = parseLenient(mapper, s);
                if (!isAbsentInput(node)) {
                    present.add(node);
                }
            }
        } else {
            JsonNode root = DataValues.parse(mapper, input);
            if (root != null && root.isArray()) {
                declared = root.size();
                for (JsonNode element : root) {
                    JsonNode node =
                            element.isString() ? parseLenient(mapper, element.asString()) : element;
                    if (!isAbsentInput(node)) {
                        present.add(node);
                    }
                }
            } else {
                declared = 1;
                JsonNode node = root != null ? root : parseLenient(mapper, input);
                if (!isAbsentInput(node)) {
                    present.add(node);
                }
            }
        }
        log.add(present.size() + " of " + declared + " input(s) present.");

        Integer minInputs = intOrNull(config.get("minInputs"));
        if (minInputs != null && minInputs > 0 && present.size() < minInputs) {
            return Outcome.skippedWith(
                    input,
                    "Merge needs "
                            + minInputs
                            + " input(s) but only "
                            + present.size()
                            + " arrived - input passed through unchanged.",
                    log);
        }

        switch (mode) {
            case "first_non_empty":
                {
                    for (JsonNode node : present) {
                        if (!isEmpty(node)) {
                            return Outcome.ok(
                                    outputText(node),
                                    "Merge picked the first non-empty input.",
                                    log);
                        }
                    }
                    log.add("Every input was empty.");
                    return Outcome.ok("", "Merge found no non-empty input.", log);
                }
            case "join":
                {
                    String key = str(config.get("key"));
                    if (key == null || key.isBlank()) {
                        throw new IllegalArgumentException("Join needs a key field to match on.");
                    }
                    String joinType =
                            str(config.get("joinType")) == null
                                            || str(config.get("joinType")).isBlank()
                                    ? "left"
                                    : str(config.get("joinType")).strip().toLowerCase(Locale.ROOT);
                    if (!JOIN_TYPES.contains(joinType)) {
                        throw new IllegalArgumentException(
                                "Unknown join type '"
                                        + joinType
                                        + "' (expected left, inner or outer).");
                    }
                    ArrayNode joined = join(mapper, present, key.strip(), joinType);
                    return Outcome.ok(
                            joined.toString(),
                            "Joined "
                                    + present.size()
                                    + " input(s) on '"
                                    + key.strip()
                                    + "' into "
                                    + joined.size()
                                    + " row(s).",
                            log);
                }
            default:
                {
                    ArrayNode out = mapper.createArrayNode();
                    for (JsonNode node : present) {
                        if (node.isArray()) {
                            for (JsonNode element : node) {
                                out.add(element);
                            }
                        } else {
                            out.add(node);
                        }
                        if (out.size() > MAX_ITEMS) {
                            throw new IllegalArgumentException(
                                    "Merged result is larger than " + MAX_ITEMS + " items.");
                        }
                    }
                    return Outcome.ok(
                            out.toString(),
                            "Merged "
                                    + present.size()
                                    + " input(s) into "
                                    + out.size()
                                    + " item(s).",
                            log);
                }
        }
    }

    private static ArrayNode join(
            ObjectMapper mapper, List<JsonNode> inputs, String key, String joinType) {
        List<List<JsonNode>> rowSets = new ArrayList<>();
        for (JsonNode input : inputs) {
            List<JsonNode> rows = new ArrayList<>();
            if (input.isArray()) {
                input.forEach(rows::add);
            } else {
                rows.add(input);
            }
            for (JsonNode row : rows) {
                if (!row.isObject()) {
                    throw new IllegalArgumentException(
                            "Join needs JSON objects (or arrays of objects) to match on '"
                                    + key
                                    + "'.");
                }
            }
            rowSets.add(rows);
        }
        if (rowSets.isEmpty()) {
            return mapper.createArrayNode();
        }
        List<JsonNode> result = new ArrayList<>(rowSets.get(0));
        for (int s = 1; s < rowSets.size(); s++) {
            List<JsonNode> right = rowSets.get(s);
            Map<String, List<JsonNode>> index = new LinkedHashMap<>();
            for (JsonNode r : right) {
                String k = joinKey(get(r, key));
                if (k != null) {
                    index.computeIfAbsent(k, x -> new ArrayList<>()).add(r);
                }
            }
            Set<String> matchedKeys = new HashSet<>();
            List<JsonNode> next = new ArrayList<>();
            for (JsonNode l : result) {
                String k = joinKey(get(l, key));
                List<JsonNode> matches = k == null ? null : index.get(k);
                if (matches != null && !matches.isEmpty()) {
                    matchedKeys.add(k);
                    for (JsonNode m : matches) {
                        ObjectNode combined = ((ObjectNode) l).deepCopy();
                        combined.setAll(((ObjectNode) m).deepCopy());
                        next.add(combined);
                    }
                } else if (!joinType.equals("inner")) {
                    next.add(l);
                }
                if (next.size() > MAX_ITEMS) {
                    throw new IllegalArgumentException(
                            "Joined result is larger than " + MAX_ITEMS + " rows.");
                }
            }
            if (joinType.equals("outer")) {
                for (JsonNode r : right) {
                    String k = joinKey(get(r, key));
                    if (k == null || !matchedKeys.contains(k)) {
                        next.add(r);
                    }
                }
            }
            result = next;
        }
        ArrayNode out = mapper.createArrayNode();
        result.forEach(out::add);
        return out;
    }

    static String joinKey(JsonNode v) {
        if (isAbsent(v)) {
            return null;
        }
        if (v.isString() && v.asString().isBlank()) {
            return null;
        }
        BigDecimal n = canonicalNumber(v);
        return n != null ? "n:" + numberText(n) : "s:" + text(v);
    }

    private static List<String> sourceTexts(ObjectMapper mapper, Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && m.containsKey("value")) {
                    out.add(m.get("value") == null ? "" : m.get("value").toString());
                } else {
                    out.add(o == null ? "" : o.toString());
                }
            }
        } else if (raw instanceof String s && !s.isBlank()) {
            JsonNode parsed = DataValues.parse(mapper, s);
            if (parsed != null && parsed.isArray()) {
                for (JsonNode e : parsed) {
                    out.add(e.isString() ? e.asString() : e.toString());
                }
            }
        }
        return out;
    }

    private static JsonNode parseLenient(ObjectMapper mapper, String text) {
        if (text == null || text.isBlank()) {
            return NullNode.getInstance();
        }
        String t = text.strip();
        if (t.charAt(0) == '{' || t.charAt(0) == '[') {
            JsonNode parsed = DataValues.parse(mapper, t);
            if (parsed != null && parsed.isContainer()) {
                return parsed;
            }
        }
        return StringNode.valueOf(text);
    }

    private static boolean isAbsentInput(JsonNode n) {
        return n == null
                || n.isMissingNode()
                || n.isNull()
                || (n.isString() && n.asString().isBlank());
    }
}
