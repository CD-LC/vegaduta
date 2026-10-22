package com.agenticai.core.workflow;

import static com.agenticai.core.workflow.DataPathOps.get;
import static com.agenticai.core.workflow.DataValues.isAbsent;
import static com.agenticai.core.workflow.DataValues.text;
import static com.agenticai.core.workflow.NumberCanon.canonicalNumber;
import static com.agenticai.core.workflow.NumberCanon.lenientNumber;
import static com.agenticai.core.workflow.NumberCanon.parseDate;
import static com.agenticai.core.workflow.WorkflowDataOps.bool;
import static com.agenticai.core.workflow.WorkflowDataOps.mapList;
import static com.agenticai.core.workflow.WorkflowDataOps.str;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * The {@code sort} data operation. {@code sortKeys: [{field, direction: asc|desc, type:
 * auto|string|number|date}]} (or the single-key shorthand {@code field} + {@code direction}),
 * {@code caseSensitive}, {@code itemsField}. Stable; missing / null values sort last in both
 * directions. {@code auto} picks number, then date, then text by looking at every value of the key
 * once (so the comparator is a consistent total order even on mixed data).
 *
 * <p>Strategy: each key column is sorted by a {@link SortType}, which knows how to derive a
 * comparable key from a JSON value and how to compare two keys. Extracted from {@link
 * WorkflowDataOps}.
 */
final class DataSortOps {

    /** How one sort column turns a value into a key and compares two keys. */
    enum SortType {
        NUMBER("number") {
            @Override
            Object key(JsonNode v) {
                return lenientNumber(v);
            }

            @Override
            int compare(Object a, Object b, boolean caseSensitive) {
                return ((BigDecimal) a).compareTo((BigDecimal) b);
            }
        },
        DATE("date") {
            @Override
            Object key(JsonNode v) {
                if (v.isNumber()) {
                    return v.asLong();
                }
                return v.isString() ? parseDate(v.asString()) : null;
            }

            @Override
            int compare(Object a, Object b, boolean caseSensitive) {
                return Long.compare((Long) a, (Long) b);
            }
        },
        STRING("string") {
            @Override
            Object key(JsonNode v) {
                return text(v);
            }

            @Override
            int compare(Object a, Object b, boolean caseSensitive) {
                return caseSensitive
                        ? ((String) a).compareTo((String) b)
                        : ((String) a).compareToIgnoreCase((String) b);
            }
        };

        private final String id;

        SortType(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }

        /**
         * The comparable key of a present value (null when it cannot be keyed, which sorts last).
         */
        abstract Object key(JsonNode v);

        abstract int compare(Object a, Object b, boolean caseSensitive);

        /** An explicit type wins; {@code auto} (or anything unknown) inspects every value once. */
        static SortType resolve(String requested, List<JsonNode> values) {
            String r = requested == null ? "auto" : requested.strip().toLowerCase(Locale.ROOT);
            switch (r) {
                case "number":
                    return NUMBER;
                case "date":
                    return DATE;
                case "string":
                    return STRING;
                default:
                    break;
            }
            boolean anyValue = false;
            boolean allNumbers = true;
            boolean allDates = true;
            for (JsonNode v : values) {
                if (isAbsent(v)) {
                    continue;
                }
                anyValue = true;
                if (canonicalNumber(v) == null) {
                    allNumbers = false;
                }
                if (!v.isString() || parseDate(v.asString()) == null) {
                    allDates = false;
                }
            }
            if (!anyValue) {
                return STRING;
            }
            if (allNumbers) {
                return NUMBER;
            }
            return allDates ? DATE : STRING;
        }
    }

    private DataSortOps() {}

    static WorkflowDataOps.Outcome sort(
            ObjectMapper mapper, Map<String, Object> config, WorkflowDataOps.Items items) {
        List<Map<String, Object>> specs = mapList(config.get("sortKeys"));
        if (specs.isEmpty()
                && str(config.get("field")) != null
                && !str(config.get("field")).isBlank()) {
            Map<String, Object> single = new LinkedHashMap<>();
            single.put("field", config.get("field"));
            single.put("direction", config.get("direction"));
            single.put("type", config.get("sortType"));
            specs = List.of(single);
        }
        boolean caseSensitive = bool(config.get("caseSensitive"), false);
        List<JsonNode> list = new ArrayList<>();
        items.array().forEach(list::add);
        List<String> log = new ArrayList<>();
        if (specs.isEmpty()) {
            log.add("No sort key configured - order unchanged.");
            return WorkflowDataOps.Outcome.ok(
                    WorkflowDataOps.withItems(items, items.array()).toString(),
                    "Sort: no key set, " + list.size() + " item(s) left in order.",
                    log);
        }

        int n = list.size();
        List<Column> columns = new ArrayList<>();
        for (Map<String, Object> spec : specs) {
            String field = str(spec.get("field"));
            String requested = str(spec.get("type"));
            boolean desc =
                    "desc"
                            .equalsIgnoreCase(
                                    str(spec.get("direction")) == null
                                            ? ""
                                            : str(spec.get("direction")).strip());
            List<JsonNode> raw = new ArrayList<>(n);
            for (JsonNode item : list) {
                raw.add(get(item, field == null ? "" : field));
            }
            SortType type = SortType.resolve(requested, raw);
            Object[] keys = new Object[n];
            for (int i = 0; i < n; i++) {
                keys[i] = isAbsent(raw.get(i)) ? null : type.key(raw.get(i));
            }
            columns.add(new Column(desc, type, keys));
            log.add(
                    "Sorted by "
                            + (field == null || field.isBlank() ? "(item)" : field)
                            + " "
                            + (desc ? "descending" : "ascending")
                            + " as "
                            + type.id()
                            + ".");
        }
        List<Integer> order = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            order.add(i);
        }
        Comparator<Integer> cmp =
                (a, b) -> {
                    for (Column c : columns) {
                        int r = c.compare(a, b, caseSensitive);
                        if (r != 0) {
                            return r;
                        }
                    }
                    return 0;
                };
        order.sort(cmp); // List.sort is a stable merge sort: ties keep their input order
        ArrayNode out = mapper.createArrayNode();
        for (int idx : order) {
            out.add(list.get(idx));
        }
        return WorkflowDataOps.Outcome.ok(
                WorkflowDataOps.withItems(items, out).toString(), "Sorted " + n + " item(s).", log);
    }

    private static final class Column {
        private final boolean desc;
        private final SortType type;
        private final Object[] keys;

        Column(boolean desc, SortType type, Object[] keys) {
            this.desc = desc;
            this.type = type;
            this.keys = keys;
        }

        int compare(int i, int j, boolean caseSensitive) {
            Object a = keys[i];
            Object b = keys[j];
            if (a == null && b == null) {
                return 0;
            }
            if (a == null) {
                return 1;
            }
            if (b == null) {
                return -1;
            }
            int r = type.compare(a, b, caseSensitive);
            return desc ? -r : r;
        }
    }
}
