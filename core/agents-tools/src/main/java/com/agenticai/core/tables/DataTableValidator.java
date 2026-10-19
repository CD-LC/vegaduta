package com.agenticai.core.tables;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Validates and normalises cell values against a table's declared column types. Pure and static: no Spring, no
 * database, so every rule is unit-testable.
 *
 * <p>Normal forms: {@code text} -> String; {@code number} -> Long when integral and in range, else Double
 * (NaN/Infinity rejected); {@code boolean} -> Boolean; {@code date} -> {@code yyyy-MM-dd} for a date-only value or
 * a UTC ISO-8601 instant for a date-time (so lexicographic order is chronological within each form); {@code json}
 * -> any JSON value (a String that parses as an object/array is parsed). An empty string is "no value" for every
 * type except text. Every cell is capped at {@code maxCellBytes} UTF-8 bytes of its JSON form.
 */
public final class DataTableValidator {

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private DataTableValidator() {
    }

    /**
     * Normalises a (possibly partial) row. Unknown column names are rejected. A null value is kept as a null entry
     * so a patch can clear a cell; callers that insert drop the null entries with {@link #withoutNulls}.
     */
    public static Map<String, Object> normalizeRow(List<DataTableColumn> columns, Map<String, Object> raw, int maxCellBytes) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            DataTableColumn col = find(columns, e.getKey());
            if (col == null) {
                throw new IllegalArgumentException("Unknown column '" + e.getKey() + "'" + hint(columns));
            }
            out.put(col.name(), coerce(col, e.getValue(), maxCellBytes));
        }
        return out;
    }

    public static Map<String, Object> withoutNulls(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getValue() != null) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    /** Column lookup by exact name first, then case-insensitively (so a CSV header "Email" finds {@code email}). */
    public static DataTableColumn find(List<DataTableColumn> columns, String name) {
        if (name == null) {
            return null;
        }
        String n = name.trim();
        for (DataTableColumn c : columns) {
            if (c.name().equals(n)) {
                return c;
            }
        }
        for (DataTableColumn c : columns) {
            if (c.name().equalsIgnoreCase(n)) {
                return c;
            }
        }
        return null;
    }

    /** Coerces one value to the column's normal form; throws IllegalArgumentException (safe to show) when it does not fit. */
    public static Object coerce(DataTableColumn col, Object value, int maxCellBytes) {
        Object out = coerceType(col, value);
        if (out != null) {
            checkSize(col, out, maxCellBytes);
        }
        return out;
    }

    private static Object coerceType(DataTableColumn col, Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s && s.isEmpty() && col.type() != DataTableColumnType.TEXT) {
            return null;
        }
        return switch (col.type()) {
            case TEXT -> toText(col, value);
            case NUMBER -> toNumber(col, value);
            case BOOLEAN -> toBoolean(col, value);
            case DATE -> toDate(col, value);
            case JSON -> toJson(col, value);
        };
    }

    private static Object toText(DataTableColumn col, Object value) {
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        throw bad(col, "text");
    }

    private static Object toNumber(DataTableColumn col, Object value) {
        BigDecimal bd;
        try {
            if (value instanceof BigDecimal b) {
                bd = b;
            } else if (value instanceof Double || value instanceof Float) {
                double d = ((Number) value).doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    throw bad(col, "a finite number");
                }
                bd = BigDecimal.valueOf(d);
            } else if (value instanceof Number n) {
                bd = new BigDecimal(n.toString());
            } else if (value instanceof String s) {
                bd = new BigDecimal(s.trim());
            } else {
                throw bad(col, "a number");
            }
        } catch (NumberFormatException e) {
            throw bad(col, "a number");
        }
        BigDecimal stripped = bd.signum() == 0 ? BigDecimal.ZERO : bd.stripTrailingZeros();
        if (stripped.scale() <= 0 && stripped.precision() - stripped.scale() <= 18) {
            return stripped.longValueExact();
        }
        double d = bd.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw bad(col, "a number in range");
        }
        return d;
    }

    private static Object toBoolean(DataTableColumn col, Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            double d = n.doubleValue();
            if (d == 1.0) {
                return Boolean.TRUE;
            }
            if (d == 0.0) {
                return Boolean.FALSE;
            }
        }
        if (value instanceof String s) {
            String t = s.trim().toLowerCase(Locale.ROOT);
            if (t.equals("true") || t.equals("yes") || t.equals("y") || t.equals("1")) {
                return Boolean.TRUE;
            }
            if (t.equals("false") || t.equals("no") || t.equals("n") || t.equals("0")) {
                return Boolean.FALSE;
            }
        }
        throw bad(col, "true or false");
    }

    private static Object toDate(DataTableColumn col, Object value) {
        if (!(value instanceof String s)) {
            throw bad(col, "an ISO-8601 date (yyyy-MM-dd) or date-time");
        }
        String t = s.trim();
        try {
            return LocalDate.parse(t).toString();
        } catch (DateTimeParseException ignored) {
            // not date-only
        }
        try {
            return Instant.parse(t).toString();
        } catch (DateTimeParseException ignored) {
            // not a UTC instant
        }
        try {
            return OffsetDateTime.parse(t).toInstant().toString();
        } catch (DateTimeParseException ignored) {
            // no offset
        }
        try {
            return LocalDateTime.parse(t).toInstant(ZoneOffset.UTC).toString();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        throw bad(col, "an ISO-8601 date (yyyy-MM-dd) or date-time");
    }

    private static Object toJson(DataTableColumn col, Object value) {
        if (value instanceof String s) {
            String t = s.trim();
            if (t.startsWith("{") || t.startsWith("[")) {
                try {
                    return MAPPER.readValue(t, Object.class);
                } catch (JsonProcessingException e) {
                    throw bad(col, "valid JSON");
                }
            }
            return s;
        }
        if (value instanceof Map<?, ?> || value instanceof List<?> || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        throw bad(col, "valid JSON");
    }

    private static void checkSize(DataTableColumn col, Object normalized, int maxCellBytes) {
        int bytes;
        try {
            bytes = MAPPER.writeValueAsString(normalized).getBytes(StandardCharsets.UTF_8).length;
        } catch (JsonProcessingException e) {
            throw bad(col, "valid JSON");
        }
        if (bytes > maxCellBytes) {
            throw new IllegalArgumentException(
                "Value for column '" + col.name() + "' is " + bytes + " bytes - the limit is " + maxCellBytes + " bytes per cell");
        }
    }

    private static IllegalArgumentException bad(DataTableColumn col, String expected) {
        return new IllegalArgumentException("Column '" + col.name() + "' (" + col.type().wire() + ") expects " + expected);
    }

    private static String hint(List<DataTableColumn> columns) {
        if (columns.isEmpty()) {
            return " - this table has no columns yet";
        }
        StringBuilder sb = new StringBuilder(" - columns are ");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(columns.get(i).name());
            if (i >= 14) {
                sb.append(", ...");
                break;
            }
        }
        return sb.toString();
    }
}
