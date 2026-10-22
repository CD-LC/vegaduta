package com.agenticai.core.tables;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The filter + sort vocabulary shared by the REST API, the workflow node and the agent tools. A
 * filter is a list of {@link Condition}s that must ALL hold (AND); there is deliberately no OR /
 * nesting - "simple column conditions". It is accepted in three spellings so each caller can use
 * the natural one:
 *
 * <ul>
 *   <li>a list of {@code {column, op, value}} objects (the canonical form);
 *   <li>an object shorthand {@code {"status": "open", "owner": "ravi"}} meaning equality on each
 *       key;
 *   <li>a JSON string of either of the above (config fields and query parameters arrive as text).
 * </ul>
 *
 * Pure and static - parsing only; typing the values and rendering SQL is {@link
 * DataTableQueryBuilder}'s job.
 */
public final class DataTableFilter {

    public static final int MAX_CONDITIONS = 20;
    public static final int MAX_IN_VALUES = 100;
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private DataTableFilter() {}

    /**
     * Condition operators. {@code wire} is the canonical spelling; {@link #parse} also takes common
     * aliases.
     */
    public enum Op {
        EQ("eq"),
        NE("ne"),
        GT("gt"),
        GTE("gte"),
        LT("lt"),
        LTE("lte"),
        CONTAINS("contains"),
        STARTS_WITH("starts_with"),
        IN("in"),
        IS_NULL("is_null"),
        NOT_NULL("not_null");

        private final String wire;

        Op(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        public boolean takesValue() {
            return this != IS_NULL && this != NOT_NULL;
        }

        public static Op parse(String raw) {
            String t =
                    raw == null
                            ? ""
                            : raw.trim()
                                    .toLowerCase(Locale.ROOT)
                                    .replace('-', '_')
                                    .replace(' ', '_');
            return switch (t) {
                case "eq", "=", "==", "equals", "is" -> EQ;
                case "ne", "!=", "<>", "not_equals", "neq" -> NE;
                case "gt", ">" -> GT;
                case "gte", ">=", "ge" -> GTE;
                case "lt", "<" -> LT;
                case "lte", "<=", "le" -> LTE;
                case "contains", "like", "includes" -> CONTAINS;
                case "starts_with", "startswith", "begins_with" -> STARTS_WITH;
                case "in" -> IN;
                case "is_null", "isnull", "is_empty", "empty" -> IS_NULL;
                case "not_null", "notnull", "is_not_null", "not_empty" -> NOT_NULL;
                default ->
                        throw new IllegalArgumentException(
                                "Unknown filter operator '"
                                        + (raw == null ? "" : raw.trim())
                                        + "' - use eq, ne, gt, gte, lt, lte, contains, starts_with, in, is_null or not_null");
            };
        }
    }

    /**
     * One column condition. {@code value} is raw (untyped) until the query builder binds it to the
     * column type.
     */
    public record Condition(String column, Op op, Object value) {}

    /**
     * One sort key; {@code column} may be a system column ({@code _created_at}, {@code
     * _updated_at}, {@code _id}).
     */
    public record Sort(String column, boolean descending) {}

    /**
     * Parses any accepted filter spelling. Null, blank, an empty list or an empty object = no
     * filter (an empty list, never null). Malformed input is an IllegalArgumentException (400).
     */
    public static List<Condition> parse(Object raw) {
        Object spec = raw;
        if (spec instanceof String s) {
            String t = s.trim();
            if (t.isEmpty()) {
                return List.of();
            }
            try {
                spec = MAPPER.readValue(t, Object.class);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException(
                        "The filter is not valid JSON - use a list of {column, op, value} or an object like {\"status\": \"open\"}");
            }
        }
        if (spec == null) {
            return List.of();
        }
        List<Condition> out = new ArrayList<>();
        if (spec instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.add(new Condition(String.valueOf(e.getKey()), Op.EQ, e.getValue()));
            }
        } else if (spec instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m)) {
                    throw new IllegalArgumentException(
                            "Each filter condition must be an object like {\"column\": \"status\", \"op\": \"eq\", \"value\": \"open\"}");
                }
                Object column = m.get("column");
                if (column == null || column.toString().isBlank()) {
                    throw new IllegalArgumentException("A filter condition needs a column");
                }
                Object op = m.get("op");
                Op parsed = op == null || op.toString().isBlank() ? Op.EQ : Op.parse(op.toString());
                out.add(new Condition(column.toString().trim(), parsed, m.get("value")));
            }
        } else {
            throw new IllegalArgumentException(
                    "The filter must be a list of conditions or an object of column/value pairs");
        }
        if (out.size() > MAX_CONDITIONS) {
            throw new IllegalArgumentException(
                    "A filter can have at most " + MAX_CONDITIONS + " conditions");
        }
        return out;
    }

    /** {@code "col"}, {@code "col:desc"}, {@code "col:asc"}; blank/null = no explicit sort. */
    public static Sort parseSort(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String t = raw.trim();
        int colon = t.lastIndexOf(':');
        if (colon < 0) {
            return new Sort(t, false);
        }
        String dir = t.substring(colon + 1).trim().toLowerCase(Locale.ROOT);
        if (!dir.equals("asc") && !dir.equals("desc")) {
            throw new IllegalArgumentException("Sort direction must be asc or desc");
        }
        return new Sort(t.substring(0, colon).trim(), dir.equals("desc"));
    }

    public static Sort sortOf(String column, String direction) {
        if (column == null || column.isBlank()) {
            return null;
        }
        String d = direction == null ? "asc" : direction.trim().toLowerCase(Locale.ROOT);
        if (!d.isEmpty() && !d.equals("asc") && !d.equals("desc")) {
            throw new IllegalArgumentException("Sort direction must be asc or desc");
        }
        return new Sort(column.trim(), d.equals("desc"));
    }

    /** Canonical wire form of a filter, for echoing back to a client or into a trace line. */
    public static List<Map<String, Object>> toWire(List<Condition> conditions) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Condition c : conditions) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("column", c.column());
            m.put("op", c.op().wire());
            if (c.op().takesValue()) {
                m.put("value", c.value());
            }
            out.add(m);
        }
        return out;
    }
}
