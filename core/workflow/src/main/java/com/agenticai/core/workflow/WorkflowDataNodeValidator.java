package com.agenticai.core.workflow;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.workflow.WorkflowDataOps.Condition;
import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Save-time checks for the eight data nodes, called from {@link OrchestratorService#validate}. Everything a
 * node's runtime would reject with a {@link IllegalArgumentException} for a reason that is knowable from the
 * stored config alone (unknown operator, empty condition list, malformed literal, duplicate Switch case names) is
 * reported here, so it surfaces on Validate / Activate instead of as a SKIPPED node mid-run. Values that contain a
 * {@code {{ }}} expression are only known at run time and are not checked.
 */
final class WorkflowDataNodeValidator {

    static final Set<String> TYPES = Set.of("set", "filter", "sort", "aggregate", "limit", "dedupe", "switch", "merge");

    private static final WorkflowDataOps OPS = new WorkflowDataOps(JsonMappers.plain());
    private static final Set<String> RESERVED_HANDLES = Set.of(WorkflowDataOps.SWITCH_DEFAULT_HANDLE, "error");

    private WorkflowDataNodeValidator() {
    }

    static boolean handles(String type) {
        return type != null && TYPES.contains(type.toLowerCase(Locale.ROOT));
    }

    /** Per-node config checks. Messages follow the other node types' "X node 'label' ..." wording. */
    static void validate(WorkflowNodeDto node, List<String> errors) {
        String type = node.type() == null ? "" : node.type().toLowerCase(Locale.ROOT);
        Map<String, Object> config = node.config();
        String who = displayName(type) + " node '" + node.label() + "'";
        switch (type) {
            case "set" -> validateSet(who, config, errors);
            case "filter" -> {
                requireConditions(who, config.get("conditions"), "condition", errors);
                checkCombinator(who, config.get("combinator"), errors);
            }
            case "sort" -> validateSort(who, config, errors);
            case "aggregate" -> validateAggregate(who, config, errors);
            case "limit" -> validateLimit(who, config, errors);
            case "dedupe" -> checkChoice(who, "keep", config.get("keep"), Set.of("first", "last"), errors);
            case "switch" -> validateSwitch(who, config, errors);
            case "merge" -> validateMerge(who, config, errors);
            default -> {
                // not a data node
            }
        }
    }

    /**
     * Graph-level check: a Switch routes by matching its chosen case name against each outgoing edge's
     * {@code sourceHandle}, so an edge off a Switch must come from one of its case ports, the default port or the
     * shared error port - anything else is a connection the engine can never follow.
     */
    static void validateGraph(WorkflowGraphDto graph, List<String> errors) {
        for (WorkflowNodeDto node : graph.nodes()) {
            if (!"switch".equalsIgnoreCase(node.type()) || node.id() == null) {
                continue;
            }
            Set<String> ports = new HashSet<>(RESERVED_HANDLES);
            for (Map<String, Object> c : WorkflowDataOps.mapList(node.config().get("cases"))) {
                String name = WorkflowDataOps.str(c.get("name"));
                if (name != null && !name.isBlank()) {
                    ports.add(name.strip().toLowerCase(Locale.ROOT));
                }
            }
            for (WorkflowEdgeDto edge : graph.edges()) {
                if (!node.id().equals(edge.source())) {
                    continue;
                }
                String handle = edge.sourceHandle() == null ? "" : edge.sourceHandle().strip();
                if (handle.isEmpty()) {
                    errors.add("Switch node '" + node.label() + "' has a connection that is not attached to a case or the "
                        + "default port - it would never be followed. Drag the connection from one of the node's ports.");
                } else if (!ports.contains(handle.toLowerCase(Locale.ROOT))) {
                    errors.add("Switch node '" + node.label() + "' has a connection from port '" + handle
                        + "', which is not one of its cases (renamed or removed?).");
                }
            }
        }
    }

    // ---- per type

    private static void validateSet(String who, Map<String, Object> config, List<String> errors) {
        List<Map<String, Object>> assignments = WorkflowDataOps.mapList(config.get("assignments"));
        int named = 0;
        for (Map<String, Object> a : assignments) {
            String name = WorkflowDataOps.str(a.get("name"));
            if (name == null || name.isBlank()) {
                errors.add(who + " has a field with no name.");
                continue;
            }
            named++;
            String type = WorkflowDataOps.str(a.get("type"));
            Object value = a.get("value");
            if (value instanceof String s && s.contains("{{")) {
                if (type != null && !type.isBlank() && !WorkflowDataOps.SET_TYPES.contains(type.strip().toLowerCase(Locale.ROOT))) {
                    errors.add(who + " field '" + name.strip() + "' has an unknown type '" + type + "'.");
                }
                continue;
            }
            try {
                OPS.typedValue(value, type, name.strip());
            } catch (IllegalArgumentException e) {
                errors.add(who + ": " + e.getMessage());
            }
        }
        if (named == 0) {
            errors.add(who + " has no fields to set - add at least one name and value.");
        }
    }

    private static void validateSort(String who, Map<String, Object> config, List<String> errors) {
        List<Map<String, Object>> keys = WorkflowDataOps.mapList(config.get("sortKeys"));
        String single = WorkflowDataOps.str(config.get("field"));
        if (keys.isEmpty()) {
            if (single == null || single.isBlank()) {
                errors.add(who + " has no sort key - choose the field to sort by.");
            } else {
                checkChoice(who, "direction", config.get("direction"), Set.of("asc", "desc"), errors);
                checkChoice(who, "sortType", config.get("sortType"), Set.of("auto", "string", "number", "date"), errors);
            }
            return;
        }
        for (Map<String, Object> k : keys) {
            checkChoice(who, "direction", k.get("direction"), Set.of("asc", "desc"), errors);
            checkChoice(who, "type", k.get("type"), Set.of("auto", "string", "number", "date"), errors);
        }
    }

    private static void validateAggregate(String who, Map<String, Object> config, List<String> errors) {
        List<Map<String, Object>> specs = WorkflowDataOps.mapList(config.get("aggregations"));
        if (specs.isEmpty()) {
            errors.add(who + " has nothing to calculate - add at least one aggregation.");
            return;
        }
        for (Map<String, Object> s : specs) {
            String op = WorkflowDataOps.str(s.get("operation"));
            if (WorkflowDataOps.normalizeAggregateOperation(op) == null) {
                errors.add(who + " has an unknown operation '" + (op == null ? "" : op)
                    + "' (expected count, sum, avg, min, max, first, last, concat or collect).");
            }
        }
    }

    private static void validateLimit(String who, Map<String, Object> config, List<String> errors) {
        Object count = config.get("count");
        if (count == null || count.toString().isBlank()) {
            errors.add(who + " has no count - set how many items to keep.");
        } else if (!(count instanceof String s && s.contains("{{"))) {
            Integer n = WorkflowDataOps.intOrNull(count);
            if (n == null || n < 0) {
                errors.add(who + " needs a whole number of 0 or more for its count (got '" + count + "').");
            }
        }
        checkChoice(who, "mode", config.get("mode"), Set.of("first", "last"), errors);
    }

    private static void validateSwitch(String who, Map<String, Object> config, List<String> errors) {
        List<Map<String, Object>> cases = WorkflowDataOps.mapList(config.get("cases"));
        if (cases.isEmpty()) {
            errors.add(who + " has no cases - add at least one case with a name and a condition.");
            return;
        }
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> c : cases) {
            String name = WorkflowDataOps.str(c.get("name"));
            if (name == null || name.isBlank()) {
                errors.add(who + " has a case with no name.");
                continue;
            }
            String key = name.strip().toLowerCase(Locale.ROOT);
            if (RESERVED_HANDLES.contains(key)) {
                errors.add(who + " has a case named '" + name.strip() + "', which is reserved - pick another name.");
            } else if (!seen.add(key)) {
                errors.add(who + " has two cases named '" + name.strip() + "' - case names must be unique.");
            }
            requireConditions(who + " case '" + name.strip() + "'", c.get("conditions"), "condition", errors);
            checkCombinator(who, c.get("combinator"), errors);
        }
    }

    private static void validateMerge(String who, Map<String, Object> config, List<String> errors) {
        String mode = WorkflowDataOps.str(config.get("mode"));
        String normalized = mode == null || mode.isBlank() ? "append" : mode.strip().toLowerCase(Locale.ROOT);
        if (!WorkflowDataOps.MERGE_MODES.contains(normalized)) {
            errors.add(who + " has an unknown mode '" + mode + "' (expected append, join or first_non_empty).");
            return;
        }
        if (normalized.equals("join")) {
            String key = WorkflowDataOps.str(config.get("key"));
            if (key == null || key.isBlank()) {
                errors.add(who + " joins inputs but has no key field to match on.");
            }
            checkChoice(who, "joinType", config.get("joinType"), WorkflowDataOps.JOIN_TYPES, errors);
        }
        Object min = config.get("minInputs");
        if (min != null && !min.toString().isBlank()) {
            Integer n = WorkflowDataOps.intOrNull(min);
            if (n == null || n < 0) {
                errors.add(who + " needs a whole number of 0 or more for 'minimum inputs' (got '" + min + "').");
            }
        }
    }

    // ---- shared

    private static void requireConditions(String who, Object raw, String noun, List<String> errors) {
        List<Condition> conditions = WorkflowDataOps.conditions(raw);
        if (conditions.isEmpty()) {
            errors.add(who + " has no " + noun + " - add at least one.");
            return;
        }
        for (Condition c : conditions) {
            String op = WorkflowDataOps.normalizeOperator(c.operator());
            if (op == null) {
                errors.add(who + " uses an unknown operator '" + c.operator() + "'.");
                continue;
            }
            if (op.equals("regex") && !c.value().contains("{{")) {
                if (c.value().length() > WorkflowDataOps.MAX_REGEX_PATTERN_CHARS) {
                    errors.add(who + " has a regex longer than " + WorkflowDataOps.MAX_REGEX_PATTERN_CHARS + " characters.");
                } else {
                    try {
                        Pattern.compile(c.value());
                    } catch (PatternSyntaxException e) {
                        errors.add(who + " has an invalid regex '" + c.value() + "': " + e.getDescription() + ".");
                    }
                }
            }
        }
    }

    private static void checkCombinator(String who, Object combinator, List<String> errors) {
        checkChoice(who, "combinator", combinator, Set.of("and", "or"), errors);
    }

    private static void checkChoice(String who, String key, Object raw, Set<String> allowed, List<String> errors) {
        if (raw == null || raw.toString().isBlank()) {
            return;
        }
        String v = raw.toString().strip().toLowerCase(Locale.ROOT);
        if (!allowed.contains(v)) {
            errors.add(who + " has an invalid " + key + " '" + raw + "'.");
        }
    }

    private static String displayName(String type) {
        return switch (type) {
            case "set" -> "Set";
            case "filter" -> "Filter";
            case "sort" -> "Sort";
            case "aggregate" -> "Aggregate";
            case "limit" -> "Limit";
            case "dedupe" -> "Remove Duplicates";
            case "switch" -> "Switch";
            case "merge" -> "Merge";
            default -> type;
        };
    }
}
