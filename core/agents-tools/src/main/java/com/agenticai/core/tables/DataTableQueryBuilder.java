package com.agenticai.core.tables;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.tables.DataTableFilter.Condition;
import com.agenticai.core.tables.DataTableFilter.Op;
import com.agenticai.core.tables.DataTableFilter.Sort;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Renders a typed filter and sort into the SQL fragments {@link DataTableRowStore} appends to its
 * statements. Pure: strings in, strings out, so the whole op x type matrix is unit-tested without a
 * database.
 *
 * <p>Injection posture: every VALUE is a bound named parameter. Column NAMES cannot be bound, so
 * they are written as single-quoted SQL string literals - which is safe only because a name must
 * (a) already exist in the table's declared schema and (b) match {@link DataTableColumn}'s
 * identifier grammar; {@link #literal} re-checks (b) as defence in depth. The row alias is always
 * {@code r} (table {@code data_table_row}).
 *
 * <p>Typing: number columns compare as {@code numeric}, boolean as {@code boolean}, text and date
 * as text (dates are stored in a normal form that sorts chronologically, see {@link
 * DataTableValidator}), json by its text form (contains) or {@code jsonb} equality. A missing or
 * JSON-null cell is SQL NULL, so it never satisfies a comparison; {@code ne} deliberately treats it
 * as "not equal" (the intuitive spreadsheet reading).
 */
public final class DataTableQueryBuilder {

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    // LIKE patterns escape with a backslash, which is PostgreSQL's default escape character - so no
    // ESCAPE clause is written.

    private DataTableQueryBuilder() {}

    /**
     * A SQL boolean expression (empty = no restriction) plus its named parameters, in bind order.
     */
    public record Where(String sql, Map<String, Object> params) {

        public static Where none() {
            return new Where("", Map.of());
        }

        public boolean isEmpty() {
            return sql.isEmpty();
        }
    }

    public static Where where(
            List<DataTableColumn> columns, List<Condition> conditions, int maxCellBytes) {
        if (conditions == null || conditions.isEmpty()) {
            return Where.none();
        }
        List<String> parts = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        for (Condition c : conditions) {
            parts.add(condition(columns, c, params, maxCellBytes));
        }
        return new Where(String.join(" AND ", parts), params);
    }

    /** {@code ORDER BY ...} (always present, always ends in a stable tiebreaker). */
    public static String orderBy(List<DataTableColumn> columns, Sort sort) {
        String dir = sort != null && sort.descending() ? "DESC" : "ASC";
        if (sort == null) {
            return " ORDER BY r.created_at ASC, r.id ASC";
        }
        String c = sort.column();
        return switch (c) {
            case "_created_at" -> " ORDER BY r.created_at " + dir + ", r.id ASC";
            case "_updated_at" -> " ORDER BY r.updated_at " + dir + ", r.id ASC";
            case "_id" -> " ORDER BY r.id " + dir;
            default -> {
                DataTableColumn col = requireColumn(columns, c);
                String expr =
                        col.type() == DataTableColumnType.JSON
                                ? "(r.data -> " + literal(col.name()) + ")"
                                : typed(col);
                yield " ORDER BY " + expr + " " + dir + " NULLS LAST, r.created_at ASC, r.id ASC";
            }
        };
    }

    // ------------------------------------------------------------------

    private static String condition(
            List<DataTableColumn> columns,
            Condition c,
            Map<String, Object> params,
            int maxCellBytes) {
        if ("_id".equals(c.column())) {
            return idCondition(c, params);
        }
        DataTableColumn col = requireColumn(columns, c.column());
        String text = "(r.data ->> " + literal(col.name()) + ")";
        Op op = c.op();
        switch (op) {
            case IS_NULL:
                return text + " IS NULL";
            case NOT_NULL:
                return text + " IS NOT NULL";
            default:
                break;
        }
        requireLegal(col, op);
        if (op == Op.CONTAINS) {
            return "LOWER("
                    + text
                    + ") LIKE "
                    + bind(params, likeContains(stringOf(c.value(), col)));
        }
        if (op == Op.STARTS_WITH) {
            return text + " LIKE " + bind(params, escapeLike(stringOf(c.value(), col)) + "%");
        }
        if (op == Op.IN) {
            List<Object> values = listOf(c.value(), col);
            List<String> names = new ArrayList<>();
            for (Object v : values) {
                names.add(bind(params, comparable(col, v, maxCellBytes)));
            }
            return rhsExpr(col) + " IN (" + String.join(", ", names) + ")";
        }
        Object typedValue = comparable(col, c.value(), maxCellBytes);
        if (typedValue == null) {
            throw new IllegalArgumentException(
                    "The '" + op.wire() + "' condition on '" + col.name() + "' needs a value");
        }
        String param = bind(params, typedValue);
        if (col.type() == DataTableColumnType.JSON) {
            // jsonb equality against the bound JSON text
            String cmp = "(r.data -> " + literal(col.name()) + ") = CAST(" + param + " AS jsonb)";
            return op == Op.NE ? "(" + text + " IS NULL OR NOT (" + cmp + "))" : cmp;
        }
        String lhs = typed(col);
        return switch (op) {
            case EQ -> lhs + " = " + param;
            case NE -> "(" + text + " IS NULL OR " + lhs + " <> " + param + ")";
            case GT -> lhs + " > " + param;
            case GTE -> lhs + " >= " + param;
            case LT -> lhs + " < " + param;
            case LTE -> lhs + " <= " + param;
            default -> throw new IllegalArgumentException("Unsupported operator " + op.wire());
        };
    }

    private static String idCondition(Condition c, Map<String, Object> params) {
        String lhs = "CAST(r.id AS text)";
        switch (c.op()) {
            case EQ:
                return lhs + " = " + bind(params, uuidText(c.value()));
            case NE:
                return lhs + " <> " + bind(params, uuidText(c.value()));
            case IN:
                {
                    List<String> names = new ArrayList<>();
                    for (Object v : listOf(c.value(), null)) {
                        names.add(bind(params, uuidText(v)));
                    }
                    return lhs + " IN (" + String.join(", ", names) + ")";
                }
            default:
                throw new IllegalArgumentException("The row id (_id) supports eq, ne and in");
        }
    }

    private static String uuidText(Object v) {
        try {
            return UUID.fromString(String.valueOf(v).trim()).toString();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + v + "' is not a row id");
        }
    }

    /** Which operators make sense on which column type. */
    private static void requireLegal(DataTableColumn col, Op op) {
        DataTableColumnType t = col.type();
        boolean ok =
                switch (op) {
                    case EQ, NE, IN -> t != DataTableColumnType.JSON || op != Op.IN;
                    case GT, GTE, LT, LTE ->
                            t == DataTableColumnType.NUMBER
                                    || t == DataTableColumnType.DATE
                                    || t == DataTableColumnType.TEXT;
                    case CONTAINS ->
                            t == DataTableColumnType.TEXT
                                    || t == DataTableColumnType.JSON
                                    || t == DataTableColumnType.DATE;
                    case STARTS_WITH ->
                            t == DataTableColumnType.TEXT || t == DataTableColumnType.DATE;
                    default -> true;
                };
        if (!ok) {
            throw new IllegalArgumentException(
                    "The '"
                            + op.wire()
                            + "' operator does not apply to the "
                            + t.wire()
                            + " column '"
                            + col.name()
                            + "'");
        }
    }

    /** The cell as its typed SQL expression. */
    private static String typed(DataTableColumn col) {
        String text = "(r.data ->> " + literal(col.name()) + ")";
        return switch (col.type()) {
            case NUMBER -> "CAST(" + text + " AS numeric)";
            case BOOLEAN -> "CAST(" + text + " AS boolean)";
            default -> text;
        };
    }

    private static String rhsExpr(DataTableColumn col) {
        return typed(col);
    }

    /** Coerces a filter value to the bound-parameter type for the column. */
    private static Object comparable(DataTableColumn col, Object value, int maxCellBytes) {
        if (value == null) {
            return null;
        }
        Object v = DataTableValidator.coerce(col, value, Math.max(maxCellBytes, 1));
        if (v == null) {
            return null;
        }
        return switch (col.type()) {
            case NUMBER ->
                    v instanceof Long l
                            ? BigDecimal.valueOf(l)
                            : BigDecimal.valueOf(((Number) v).doubleValue());
            case JSON -> toJsonText(v);
            default -> v;
        };
    }

    private static String toJsonText(Object v) {
        try {
            return MAPPER.writeValueAsString(v);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("The filter value is not valid JSON");
        }
    }

    private static String stringOf(Object v, DataTableColumn col) {
        if (v == null) {
            throw new IllegalArgumentException(
                    "This condition on '" + col.name() + "' needs a value");
        }
        if (v instanceof String s) {
            return s;
        }
        if (v instanceof Map<?, ?> || v instanceof List<?>) {
            return toJsonText(v);
        }
        return v.toString();
    }

    private static List<Object> listOf(Object v, DataTableColumn col) {
        List<Object> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            out.addAll(l);
        } else if (v instanceof String s) {
            for (String part : s.split(",")) {
                if (!part.isBlank()) {
                    out.add(part.trim());
                }
            }
        } else if (v != null) {
            out.add(v);
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException(
                    "The 'in' condition"
                            + (col == null ? "" : " on '" + col.name() + "'")
                            + " needs at least one value");
        }
        if (out.size() > DataTableFilter.MAX_IN_VALUES) {
            throw new IllegalArgumentException(
                    "An 'in' condition can list at most "
                            + DataTableFilter.MAX_IN_VALUES
                            + " values");
        }
        return out;
    }

    private static String likeContains(String needle) {
        return "%" + escapeLike(needle.toLowerCase(java.util.Locale.ROOT)) + "%";
    }

    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String bind(Map<String, Object> params, Object value) {
        String name = "p" + params.size();
        params.put(name, value);
        return ":" + name;
    }

    private static DataTableColumn requireColumn(List<DataTableColumn> columns, String name) {
        DataTableColumn col = DataTableValidator.find(columns, name);
        if (col == null) {
            throw new IllegalArgumentException("Unknown column '" + name + "'");
        }
        return col;
    }

    /**
     * A column name as a SQL string literal. Re-validates the identifier grammar so no quote can
     * ever reach the SQL.
     */
    static String literal(String name) {
        if (name == null || !DataTableColumn.NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid column name '" + name + "'");
        }
        return "'" + name + "'";
    }
}
