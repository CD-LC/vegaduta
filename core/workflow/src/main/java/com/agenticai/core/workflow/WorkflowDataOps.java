package com.agenticai.core.workflow;

import com.agenticai.core.json.JsonMappers;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.BooleanNode;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.TextNode;

/**
 * The pure, deterministic, no-LLM data operations behind the eight data nodes ({@code set}, {@code
 * filter}, {@code sort}, {@code aggregate}, {@code limit}, {@code dedupe}, {@code switch}, {@code
 * merge}). Everything here is a function of (node config, input text): no Spring, no I/O, no clock
 * reads other than the regex time budget, so every behaviour is unit-testable without a context.
 * {@link WorkflowDataNodeHandler} is the thin adapter the executor dispatches to.
 *
 * <p><b>Items.</b> filter / sort / aggregate / limit / dedupe work on a JSON array of items. With a
 * blank {@code itemsField} the node's input must itself be a JSON array (a blank input counts as an
 * empty one, the same lenience {@code loop} has); with {@code itemsField} set the input is a JSON
 * object and that (dot-)path holds the array - the contract {@code loop} / {@code fan_out} use -
 * and the node's output is the input object with that field replaced, so sibling fields survive.
 * Anything else is an {@link IllegalArgumentException}, which the handler turns into a SKIPPED node
 * with the reason (input passed through unchanged), exactly like the Transform node does for a bad
 * operation.
 *
 * <p><b>Field paths.</b> {@code a.b[0].c}, {@code ["odd key"]}; a blank path is the item itself; a
 * missing path reads as absent (never an error). Negative array indexes count from the end.
 *
 * <p><b>Typing.</b> Values arrive from config as text (expressions are already resolved to text by
 * {@link ExpressionFields}), so comparisons are type-aware rather than text-only: numbers compare
 * as numbers, ISO-8601 dates as instants, everything else as text. Equality ({@code eq}/{@code
 * neq}) only treats a text value as a number when it is in canonical form (no leading zeros -
 * {@code "01234"} stays a string, so ZIP codes and ids are never conflated); the ordering operators
 * ({@code gt}/{@code lt}/{@code gte}/{@code lte}) are explicit numeric intent and read any
 * numeric-looking text as a number.
 *
 * <p><b>Regex safety.</b> A pattern is capped at {@value #MAX_REGEX_PATTERN_CHARS} characters, a
 * tested value at {@value #MAX_REGEX_INPUT_CHARS} (longer values simply do not match), every match
 * runs against a time-limited {@link CharSequence} (a deadline checked inside {@code charAt}, so
 * catastrophic backtracking is interrupted rather than pinning the run thread), a timed-out match
 * is a non-match, three timeouts abort the node and the whole node shares a total regex budget.
 */
final class WorkflowDataOps {

    /**
     * Upper bound on items a data node will process / rows it will produce (a join can multiply
     * rows).
     */
    static final int MAX_ITEMS = 100_000;

    static final int MAX_CONCAT_CHARS = 1_000_000;

    /**
     * The fixed Switch port that receives the run when no case matches. Reserved: no case may use
     * it.
     */
    static final String SWITCH_DEFAULT_HANDLE = "default";

    /** Operators a filter / switch condition may use (after {@link #normalizeOperator}). */
    static final Set<String> OPERATORS =
            Set.of(
                    "eq",
                    "neq",
                    "gt",
                    "lt",
                    "gte",
                    "lte",
                    "contains",
                    "starts",
                    "ends",
                    "empty",
                    "notEmpty",
                    "regex");

    static final int MAX_REGEX_PATTERN_CHARS = DataFilterOps.MAX_REGEX_PATTERN_CHARS;
    static final int MAX_REGEX_INPUT_CHARS = DataFilterOps.MAX_REGEX_INPUT_CHARS;

    static final Set<String> AGGREGATE_OPERATIONS =
            Set.of("count", "sum", "avg", "min", "max", "first", "last", "concat", "collect");
    static final Set<String> MERGE_MODES = Set.of("append", "join", "first_non_empty");
    static final Set<String> JOIN_TYPES = Set.of("left", "inner", "outer");
    static final Set<String> SET_TYPES =
            Set.of("auto", "string", "number", "boolean", "json", "null");

    /** What an operation produced. {@code branch} is non-null only for {@code switch}. */
    record Outcome(
            String output, String summary, String branch, boolean skipped, List<String> log) {

        static Outcome ok(String output, String summary, List<String> log) {
            return new Outcome(output, summary, null, false, log);
        }

        static Outcome routed(String input, String summary, String branch, List<String> log) {
            return new Outcome(input, summary, branch, false, log);
        }

        static Outcome skippedWith(String output, String summary, List<String> log) {
            return new Outcome(output, summary, null, true, log);
        }
    }

    /** One {@code field operator value} test. {@code value} is never null. */
    record Condition(String field, String operator, String value) {}

    /**
     * Shared (thread-safe once configured) mapper for config text that arrives as a JSON string.
     */
    private static final ObjectMapper LIST_MAPPER = JsonMappers.plain();

    private final ObjectMapper mapper;

    WorkflowDataOps(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    // =====================================================================================================
    // SET
    // =====================================================================================================

    /**
     * {@code assignments: [{name, value, type}]}, {@code keepOnlySet: boolean}. An object input is
     * copied and the named (dot-)paths assigned; an array input has every object element updated
     * the same way (the values are resolved once for the node, so per-item values belong in a Loop
     * body where {@code $json} is the item). A non-JSON input starts from an empty object. {@code
     * keepOnlySet} drops every field that was not assigned.
     */
    Outcome set(Map<String, Object> config, String input) {
        List<Map<String, Object>> assignments = mapList(config.get("assignments"));
        boolean keepOnly = bool(config.get("keepOnlySet"), false);
        List<String> log = new ArrayList<>();

        List<String> names = new ArrayList<>();
        List<JsonNode> values = new ArrayList<>();
        for (Map<String, Object> a : assignments) {
            String name = str(a.get("name"));
            if (name == null || name.isBlank()) {
                continue;
            }
            names.add(name.strip());
            values.add(typedValue(a.get("value"), str(a.get("type")), name.strip()));
        }
        if (names.isEmpty() && !keepOnly) {
            return Outcome.skippedWith(
                    input, "Set node has no fields to set - input passed through unchanged.", log);
        }

        JsonNode root = parseOrNull(input);
        JsonNode result;
        if (root != null && root.isArray()) {
            ArrayNode out = mapper.createArrayNode();
            int untouched = 0;
            for (JsonNode element : root) {
                if (element.isObject()) {
                    out.add(applyAssignments((ObjectNode) element, names, values, keepOnly));
                } else if (keepOnly) {
                    out.add(applyAssignments(mapper.createObjectNode(), names, values, true));
                } else {
                    out.add(element);
                    untouched++;
                }
            }
            if (untouched > 0) {
                log.add(untouched + " non-object item(s) were left unchanged.");
            }
            result = out;
        } else if (root != null && root.isObject()) {
            result = applyAssignments((ObjectNode) root, names, values, keepOnly);
        } else {
            if (input != null && !input.isBlank() && !keepOnly) {
                log.add("Input was not a JSON object - started from an empty object.");
            }
            result = applyAssignments(mapper.createObjectNode(), names, values, true);
        }
        String out = result.toString();
        log.add(
                "Set "
                        + names.size()
                        + " field(s): "
                        + String.join(", ", names)
                        + (keepOnly ? " (kept only those)." : "."));
        return Outcome.ok(
                out,
                "Set " + names.size() + " field(s)" + (keepOnly ? ", dropped the rest." : "."),
                log);
    }

    private ObjectNode applyAssignments(
            ObjectNode source, List<String> names, List<JsonNode> values, boolean keepOnly) {
        ObjectNode target;
        if (keepOnly) {
            target = mapper.createObjectNode();
        } else {
            target = source.deepCopy();
        }
        for (int i = 0; i < names.size(); i++) {
            setPath(target, names.get(i), copy(values.get(i)));
        }
        return target;
    }

    /**
     * The JSON value a Set assignment produces from its (already expression-resolved) config value.
     */
    JsonNode typedValue(Object raw, String requestedType, String nameForErrors) {
        String type =
                requestedType == null || requestedType.isBlank()
                        ? "auto"
                        : requestedType.strip().toLowerCase(Locale.ROOT);
        if (!SET_TYPES.contains(type)) {
            throw new IllegalArgumentException(
                    "Field '"
                            + nameForErrors
                            + "' has unknown type '"
                            + requestedType
                            + "' (expected auto, string, number, boolean, json or null).");
        }
        if (raw == null) {
            return "string".equals(type) ? TextNode.valueOf("") : NullNode.getInstance();
        }
        if (!(raw instanceof String)) {
            // API-authored config can carry real JSON types; honour them unless a string was asked
            // for.
            if ("string".equals(type)) {
                return TextNode.valueOf(raw.toString());
            }
            JsonNode tree = mapper.valueToTree(raw);
            return tree == null ? NullNode.getInstance() : tree;
        }
        String s = (String) raw;
        switch (type) {
            case "string":
                return TextNode.valueOf(s);
            case "null":
                return NullNode.getInstance();
            case "number":
                {
                    if (s.isBlank()) {
                        return NullNode.getInstance();
                    }
                    BigDecimal n = lenientNumber(s);
                    if (n == null) {
                        throw new IllegalArgumentException(
                                "Field '"
                                        + nameForErrors
                                        + "': '"
                                        + abbreviate(s, 40)
                                        + "' is not a number.");
                    }
                    return numberNode(n);
                }
            case "boolean":
                {
                    String t = s.strip().toLowerCase(Locale.ROOT);
                    if (t.isEmpty()) {
                        return NullNode.getInstance();
                    }
                    if (t.equals("true")) {
                        return BooleanNode.TRUE;
                    }
                    if (t.equals("false")) {
                        return BooleanNode.FALSE;
                    }
                    throw new IllegalArgumentException(
                            "Field '"
                                    + nameForErrors
                                    + "': '"
                                    + abbreviate(s, 40)
                                    + "' is not true or false.");
                }
            case "json":
                {
                    if (s.isBlank()) {
                        return NullNode.getInstance();
                    }
                    JsonNode parsed = parseOrNull(s);
                    if (parsed == null) {
                        throw new IllegalArgumentException(
                                "Field '" + nameForErrors + "' is not valid JSON.");
                    }
                    return parsed;
                }
            default:
                {
                    // auto: only unambiguous literals change type; anything else is the text as
                    // written.
                    String t = s.strip();
                    if (t.equals("true")) {
                        return BooleanNode.TRUE;
                    }
                    if (t.equals("false")) {
                        return BooleanNode.FALSE;
                    }
                    if (t.equals("null")) {
                        return NullNode.getInstance();
                    }
                    BigDecimal n = canonicalNumber(t);
                    // Identifier-like text stays text: exponent forms ('1e3' product codes) and
                    // integers longer than
                    // 15 digits (card/account/order ids that a double cannot hold). Pick type
                    // 'number' to force one.
                    if (n != null && t.indexOf('e') < 0 && t.indexOf('E') < 0 && t.length() <= 15) {
                        return numberNode(n);
                    }
                    if (!t.isEmpty() && (t.charAt(0) == '{' || t.charAt(0) == '[')) {
                        JsonNode parsed = parseOrNull(t);
                        if (parsed != null && parsed.isContainerNode()) {
                            return parsed;
                        }
                    }
                    return TextNode.valueOf(s);
                }
        }
    }

    // =====================================================================================================
    // FILTER
    // =====================================================================================================

    // =====================================================================================================
    // SORT
    // =====================================================================================================

    // =====================================================================================================
    // AGGREGATE
    // =====================================================================================================

    // =====================================================================================================
    // LIMIT
    // =====================================================================================================

    /** {@code count} (>= 0), {@code mode: first|last}, {@code itemsField}. */
    Outcome limit(Map<String, Object> config, String input) {
        Object rawCount = config.get("count");
        BigDecimal parsed =
                rawCount == null
                        ? null
                        : (rawCount instanceof Number num
                                ? new BigDecimal(num.toString())
                                : lenientNumber(rawCount.toString()));
        if (parsed == null) {
            throw new IllegalArgumentException(
                    "No valid count set (expected a whole number, 0 or more).");
        }
        if (parsed.signum() < 0 || parsed.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException(
                    "Count must be a whole number, 0 or more (got " + rawCount + ").");
        }
        int count =
                parsed.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0
                        ? Integer.MAX_VALUE
                        : parsed.intValue();
        boolean last =
                "last"
                        .equalsIgnoreCase(
                                str(config.get("mode")) == null
                                        ? ""
                                        : str(config.get("mode")).strip());
        Items items = items(input, str(config.get("itemsField")));
        int size = items.array().size();
        int keep = Math.min(count, size);
        ArrayNode out = mapper.createArrayNode();
        int from = last ? size - keep : 0;
        for (int i = from; i < from + keep; i++) {
            out.add(items.array().get(i));
        }
        return Outcome.ok(
                withItems(items, out).toString(),
                "Kept the " + (last ? "last " : "first ") + keep + " of " + size + " item(s).",
                new ArrayList<>());
    }

    // =====================================================================================================
    // DEDUPE
    // =====================================================================================================

    /**
     * {@code dedupeBy} (paths; blank = the whole item; not {@code fields}, which human_input
     * already uses for a different shape), {@code keep: first|last}, {@code ignoreCase}, {@code
     * itemsField}.
     */
    Outcome dedupe(Map<String, Object> config, String input) {
        List<String> fields = stringList(config.get("dedupeBy"));
        boolean keepLast =
                "last"
                        .equalsIgnoreCase(
                                str(config.get("keep")) == null
                                        ? ""
                                        : str(config.get("keep")).strip());
        boolean ignoreCase = bool(config.get("ignoreCase"), false);
        Items items = items(input, str(config.get("itemsField")));
        int size = items.array().size();
        String[] keys = new String[size];
        for (int i = 0; i < size; i++) {
            JsonNode item = items.array().get(i);
            if (fields.isEmpty()) {
                keys[i] = canonical(item, ignoreCase);
            } else {
                StringBuilder sb = new StringBuilder();
                for (String f : fields) {
                    sb.append(canonical(get(item, f), ignoreCase)).append('\u0001');
                }
                keys[i] = sb.toString();
            }
        }
        ArrayNode out = mapper.createArrayNode();
        if (keepLast) {
            Map<String, Integer> lastIndex = new HashMap<>();
            for (int i = 0; i < size; i++) {
                lastIndex.put(keys[i], i);
            }
            for (int i = 0; i < size; i++) {
                if (lastIndex.get(keys[i]) == i) {
                    out.add(items.array().get(i));
                }
            }
        } else {
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < size; i++) {
                if (seen.add(keys[i])) {
                    out.add(items.array().get(i));
                }
            }
        }
        List<String> log = new ArrayList<>();
        log.add(
                "Compared by "
                        + (fields.isEmpty() ? "the whole item" : String.join(", ", fields))
                        + "; kept the "
                        + (keepLast ? "last" : "first")
                        + " of each duplicate.");
        return Outcome.ok(
                withItems(items, out).toString(),
                "Removed "
                        + (size - out.size())
                        + " duplicate(s); "
                        + out.size()
                        + " item(s) remain.",
                log);
    }

    // =====================================================================================================
    // SWITCH
    // =====================================================================================================

    // =====================================================================================================
    // MERGE
    // =====================================================================================================

    // =====================================================================================================
    // Conditions
    // =====================================================================================================

    // =====================================================================================================
    // Items + JSON helpers
    // =====================================================================================================

    /**
     * The array a node works on, and how to put a result back. {@code wrapper} is null when the
     * input IS the array.
     */
    record Items(ArrayNode array, ObjectNode wrapper, String field) {}

    Items items(String input, String itemsField) {
        String field = itemsField == null ? "" : itemsField.strip();
        String text = input == null ? "" : input.strip();
        if (field.isEmpty()) {
            if (text.isEmpty()) {
                return new Items(mapper.createArrayNode(), null, "");
            }
            JsonNode root = parseOrNull(text);
            if (root == null || !root.isArray()) {
                throw new IllegalArgumentException(
                        "The input is not a JSON array - set 'Items field' if the list sits inside an object.");
            }
            return new Items(checkSize((ArrayNode) root), null, "");
        }
        JsonNode root = parseOrNull(text);
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException(
                    "'Items field' is set but the input is not a JSON object.");
        }
        JsonNode list = get(root, field);
        if (!list.isArray()) {
            throw new IllegalArgumentException(
                    "Field '" + field + "' of the input is not a JSON array.");
        }
        return new Items(checkSize((ArrayNode) list), (ObjectNode) root, field);
    }

    private static ArrayNode checkSize(ArrayNode array) {
        if (array.size() > MAX_ITEMS) {
            throw new IllegalArgumentException(
                    "The list has "
                            + array.size()
                            + " items; data nodes handle at most "
                            + MAX_ITEMS
                            + ".");
        }
        return array;
    }

    static JsonNode withItems(Items items, ArrayNode out) {
        if (items.wrapper() == null) {
            return out;
        }
        ObjectNode copy = items.wrapper().deepCopy();
        setPath(copy, items.field(), out);
        return copy;
    }

    // =====================================================================================================
    // Config helpers
    // =====================================================================================================

    static String str(Object o) {
        return o == null ? null : o.toString();
    }

    static boolean bool(Object o, boolean fallback) {
        if (o instanceof Boolean b) {
            return b;
        }
        if (o instanceof String s) {
            String t = s.strip();
            if (t.equalsIgnoreCase("true")) {
                return true;
            }
            if (t.equalsIgnoreCase("false")) {
                return false;
            }
        }
        return fallback;
    }

    static Integer intOrNull(Object o) {
        if (o == null) {
            return null;
        }
        BigDecimal n =
                o instanceof Number num
                        ? new BigDecimal(num.toString())
                        : lenientNumber(o.toString());
        if (n == null
                || n.stripTrailingZeros().scale() > 0
                || n.abs().compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
            return null;
        }
        return n.intValue();
    }

    /** A config list of objects, tolerant of the list arriving as raw JSON text. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> mapList(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        Object source = raw;
        if (source instanceof String s) {
            if (s.isBlank()) {
                return out;
            }
            try {
                source = LIST_MAPPER.readValue(s, Object.class);
            } catch (Exception e) {
                return out;
            }
        }
        if (source instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        copy.put(String.valueOf(e.getKey()), e.getValue());
                    }
                    out.add(copy);
                }
            }
        }
        return out;
    }

    /** A list of non-blank strings from a list or a comma separated string. */
    static List<String> stringList(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !o.toString().isBlank()) {
                    out.add(o.toString().strip());
                }
            }
        } else if (raw instanceof String s) {
            for (String part : s.split(",")) {
                if (!part.isBlank()) {
                    out.add(part.strip());
                }
            }
        }
        return out;
    }

    /**
     * Distinct values in first-seen order; used by tests and by the web-parity check of the
     * operator lists.
     */
    static Set<String> operatorNames() {
        return new LinkedHashSet<>(
                List.of(
                        "eq",
                        "neq",
                        "gt",
                        "lt",
                        "gte",
                        "lte",
                        "contains",
                        "starts",
                        "ends",
                        "empty",
                        "notEmpty",
                        "regex"));
    }

    // =====================================================================================================
    // Delegates (the helpers now live in NumberCanon / DataPathOps / DataValues)
    // =====================================================================================================

    static JsonNode get(JsonNode root, String path) {
        return DataPathOps.get(root, path);
    }

    static void setPath(ObjectNode root, String path, JsonNode value) {
        DataPathOps.setPath(root, path, value);
    }

    static List<Object> tokenize(String path) {
        return DataPathOps.tokenize(path);
    }

    static boolean isAbsent(JsonNode v) {
        return DataValues.isAbsent(v);
    }

    static boolean isEmpty(JsonNode v) {
        return DataValues.isEmpty(v);
    }

    static String text(JsonNode v) {
        return DataValues.text(v);
    }

    static String outputText(JsonNode v) {
        return DataValues.outputText(v);
    }

    static String canonical(JsonNode v, boolean ignoreCase) {
        return DataValues.canonical(v, ignoreCase);
    }

    static BigDecimal canonicalNumber(JsonNode v) {
        return NumberCanon.canonicalNumber(v);
    }

    static BigDecimal canonicalNumber(String s) {
        return NumberCanon.canonicalNumber(s);
    }

    static BigDecimal lenientNumber(JsonNode v) {
        return NumberCanon.lenientNumber(v);
    }

    static BigDecimal lenientNumber(String s) {
        return NumberCanon.lenientNumber(s);
    }

    static String numberText(BigDecimal n) {
        return NumberCanon.numberText(n);
    }

    static JsonNode numberNode(BigDecimal n) {
        return NumberCanon.numberNode(n);
    }

    static Long parseDate(String raw) {
        return NumberCanon.parseDate(raw);
    }

    /** Parses JSON text; null when blank or not valid JSON. */
    JsonNode parseOrNull(String text) {
        return DataValues.parse(mapper, text);
    }

    private static JsonNode copy(JsonNode n) {
        return DataValues.copy(n);
    }

    private static String abbreviate(String s, int max) {
        return DataValues.abbreviate(s, max);
    }

    // =====================================================================================================
    // Operation delegates (the logic lives in the Data*Ops classes)
    // =====================================================================================================

    Outcome sort(Map<String, Object> config, String input) {
        return DataSortOps.sort(mapper, config, items(input, str(config.get("itemsField"))));
    }

    Outcome aggregate(Map<String, Object> config, String input) {
        return DataAggregateOps.aggregate(
                mapper, config, () -> items(input, str(config.get("itemsField"))));
    }

    Outcome merge(Map<String, Object> config, String input) {
        return DataJoinOps.merge(mapper, config, input);
    }

    Outcome filter(Map<String, Object> config, String input) {
        return DataFilterOps.filter(
                mapper, config, () -> items(input, str(config.get("itemsField"))));
    }

    Outcome switchRoute(Map<String, Object> config, String input) {
        return DataFilterOps.switchRoute(mapper, config, input);
    }

    static String normalizeAggregateOperation(String raw) {
        return DataAggregateOps.normalizeAggregateOperation(raw);
    }

    static String normalizeOperator(String raw) {
        return DataFilterOps.normalizeOperator(raw);
    }

    static List<Condition> conditions(Object raw) {
        return DataFilterOps.conditions(raw);
    }
}
