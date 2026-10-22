package com.agenticai.core.workflow;

import static com.agenticai.core.workflow.DataPathOps.get;
import static com.agenticai.core.workflow.DataValues.canonical;
import static com.agenticai.core.workflow.DataValues.copy;
import static com.agenticai.core.workflow.DataValues.isAbsent;
import static com.agenticai.core.workflow.DataValues.isEmpty;
import static com.agenticai.core.workflow.DataValues.text;
import static com.agenticai.core.workflow.NumberCanon.canonicalNumber;
import static com.agenticai.core.workflow.NumberCanon.lenientNumber;
import static com.agenticai.core.workflow.NumberCanon.numberNode;
import static com.agenticai.core.workflow.WorkflowDataOps.MAX_CONCAT_CHARS;
import static com.agenticai.core.workflow.WorkflowDataOps.MAX_ITEMS;
import static com.agenticai.core.workflow.WorkflowDataOps.mapList;
import static com.agenticai.core.workflow.WorkflowDataOps.str;
import static com.agenticai.core.workflow.WorkflowDataOps.stringList;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.LongNode;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The {@code aggregate} data operation. {@code aggregations: [{operation, field, as, separator}]},
 * {@code groupBy} (comma separated paths or a list). Without grouping the output is one object;
 * with grouping it is an array with one object per group, in order of first appearance, starting
 * with the group fields.
 *
 * <p>Strategy: each {@link AggregateOp} reduces one group to one JSON value. Extracted from {@link
 * WorkflowDataOps}.
 */
final class DataAggregateOps {

    /**
     * What one aggregation call sees: the group, the field, the separator, the node log and the
     * output name.
     */
    record Ctx(
            ObjectMapper mapper,
            String field,
            String separator,
            List<JsonNode> group,
            List<String> log,
            String as) {}

    /**
     * One reduction of a group to a value. The constant names are the config spellings
     * (lower-cased).
     */
    enum AggregateOp {
        COUNT {
            @Override
            JsonNode apply(Ctx c) {
                if (c.field().isEmpty()) {
                    return LongNode.valueOf(c.group().size());
                }
                long n = 0;
                for (JsonNode item : c.group()) {
                    if (!isEmpty(get(item, c.field()))) {
                        n++;
                    }
                }
                return LongNode.valueOf(n);
            }
        },
        SUM {
            @Override
            JsonNode apply(Ctx c) {
                return sumOrAvg(c, true);
            }
        },
        AVG {
            @Override
            JsonNode apply(Ctx c) {
                return sumOrAvg(c, false);
            }
        },
        MIN {
            @Override
            JsonNode apply(Ctx c) {
                return extreme(c, true);
            }
        },
        MAX {
            @Override
            JsonNode apply(Ctx c) {
                return extreme(c, false);
            }
        },
        FIRST {
            @Override
            JsonNode apply(Ctx c) {
                List<JsonNode> present = presentValues(c.group(), c.field());
                return present.isEmpty() ? NullNode.getInstance() : copy(present.get(0));
            }
        },
        LAST {
            @Override
            JsonNode apply(Ctx c) {
                List<JsonNode> present = presentValues(c.group(), c.field());
                return present.isEmpty()
                        ? NullNode.getInstance()
                        : copy(present.get(present.size() - 1));
            }
        },
        CONCAT {
            @Override
            JsonNode apply(Ctx c) {
                StringBuilder sb = new StringBuilder();
                boolean firstPart = true;
                for (JsonNode v : presentValues(c.group(), c.field())) {
                    String part = text(v);
                    if (sb.length() + part.length() + c.separator().length() > MAX_CONCAT_CHARS) {
                        c.log()
                                .add(
                                        c.as()
                                                + ": concatenated text capped at "
                                                + MAX_CONCAT_CHARS
                                                + " characters.");
                        break;
                    }
                    if (!firstPart) {
                        sb.append(c.separator());
                    }
                    sb.append(part);
                    firstPart = false;
                }
                return StringNode.valueOf(sb.toString());
            }
        },
        COLLECT {
            @Override
            JsonNode apply(Ctx c) {
                ArrayNode out = c.mapper().createArrayNode();
                for (JsonNode v : presentValues(c.group(), c.field())) {
                    out.add(copy(v));
                }
                return out;
            }
        };

        abstract JsonNode apply(Ctx c);

        /**
         * The operation for a config spelling (trimmed, case-insensitive), or null when unknown.
         */
        static AggregateOp parse(String raw) {
            if (raw == null) {
                return null;
            }
            String s = raw.strip().toLowerCase(Locale.ROOT);
            for (AggregateOp op : values()) {
                if (op.name().toLowerCase(Locale.ROOT).equals(s)) {
                    return op;
                }
            }
            return null;
        }

        String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private DataAggregateOps() {}

    static WorkflowDataOps.Outcome aggregate(
            ObjectMapper mapper,
            Map<String, Object> config,
            Supplier<WorkflowDataOps.Items> itemsSource) {
        List<Map<String, Object>> specs = mapList(config.get("aggregations"));
        if (specs.isEmpty()) {
            throw new IllegalArgumentException("No aggregations configured.");
        }
        List<String> groupBy = stringList(config.get("groupBy"));
        WorkflowDataOps.Items items = itemsSource.get();
        List<String> log = new ArrayList<>();

        Map<String, List<JsonNode>> groups = new LinkedHashMap<>();
        if (groupBy.isEmpty()) {
            List<JsonNode> all = new ArrayList<>();
            items.array().forEach(all::add);
            groups.put("", all);
        } else {
            for (JsonNode item : items.array()) {
                StringBuilder key = new StringBuilder();
                for (String g : groupBy) {
                    key.append(canonical(get(item, g), false)).append('\u0001');
                }
                groups.computeIfAbsent(key.toString(), k -> new ArrayList<>()).add(item);
            }
            if (groups.size() > MAX_ITEMS) {
                throw new IllegalArgumentException(
                        "Too many groups (more than " + MAX_ITEMS + ").");
            }
        }

        ArrayNode rows = mapper.createArrayNode();
        for (List<JsonNode> group : groups.values()) {
            ObjectNode row = mapper.createObjectNode();
            if (!groupBy.isEmpty()) {
                JsonNode first = group.get(0);
                for (String g : groupBy) {
                    JsonNode v = get(first, g);
                    row.set(g, isAbsent(v) ? NullNode.getInstance() : copy(v));
                }
            }
            for (Map<String, Object> spec : specs) {
                AggregateOp op = AggregateOp.parse(str(spec.get("operation")));
                if (op == null) {
                    throw new IllegalArgumentException(
                            "Unknown aggregate operation '"
                                    + str(spec.get("operation"))
                                    + "' (expected count, sum, avg, min, max, first, last, concat"
                                    + " or collect).");
                }
                String field = str(spec.get("field")) == null ? "" : str(spec.get("field")).strip();
                String as =
                        str(spec.get("as")) == null || str(spec.get("as")).isBlank()
                                ? defaultAggregateName(op.id(), field)
                                : str(spec.get("as")).strip();
                String separator =
                        spec.get("separator") == null ? ", " : spec.get("separator").toString();
                row.set(as, op.apply(new Ctx(mapper, field, separator, group, log, as)));
            }
            rows.add(row);
        }
        JsonNode result = groupBy.isEmpty() ? rows.get(0) : rows;
        String summary =
                groupBy.isEmpty()
                        ? "Aggregated " + items.array().size() + " item(s) into one result."
                        : "Aggregated "
                                + items.array().size()
                                + " item(s) into "
                                + rows.size()
                                + " group(s).";
        return WorkflowDataOps.Outcome.ok(result.toString(), summary, log);
    }

    private static JsonNode sumOrAvg(Ctx c, boolean sum) {
        BigDecimal total = BigDecimal.ZERO;
        int numeric = 0;
        int ignored = 0;
        for (JsonNode item : c.group()) {
            JsonNode v = get(item, c.field());
            if (isAbsent(v)) {
                continue;
            }
            BigDecimal x = lenientNumber(v);
            if (x == null) {
                ignored++;
            } else {
                total = total.add(x);
                numeric++;
            }
        }
        if (ignored > 0) {
            c.log().add(c.as() + ": " + ignored + " non-numeric value(s) ignored.");
        }
        if (sum) {
            return numeric == 0 ? LongNode.valueOf(0) : numberNode(total);
        }
        return numeric == 0
                ? NullNode.getInstance()
                : numberNode(total.divide(BigDecimal.valueOf(numeric), MathContext.DECIMAL128));
    }

    private static JsonNode extreme(Ctx c, boolean min) {
        List<JsonNode> present = presentValues(c.group(), c.field());
        if (present.isEmpty()) {
            return NullNode.getInstance();
        }
        boolean numeric = true;
        for (JsonNode v : present) {
            if (canonicalNumber(v) == null) {
                numeric = false;
                break;
            }
        }
        JsonNode best = present.get(0);
        for (int i = 1; i < present.size(); i++) {
            JsonNode v = present.get(i);
            int cmp =
                    numeric
                            ? lenientNumber(v).compareTo(lenientNumber(best))
                            : text(v).compareTo(text(best));
            if (min ? cmp < 0 : cmp > 0) {
                best = v;
            }
        }
        return copy(best);
    }

    /**
     * Values of {@code field} (the item itself when blank) that are neither missing nor JSON null.
     */
    private static List<JsonNode> presentValues(List<JsonNode> group, String field) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode item : group) {
            JsonNode v = get(item, field);
            if (!isAbsent(v)) {
                out.add(v);
            }
        }
        return out;
    }

    static String normalizeAggregateOperation(String raw) {
        AggregateOp op = AggregateOp.parse(raw);
        return op == null ? null : op.id();
    }

    private static String defaultAggregateName(String operation, String field) {
        if (field.isEmpty()) {
            return operation;
        }
        return operation + "_" + field.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }
}
