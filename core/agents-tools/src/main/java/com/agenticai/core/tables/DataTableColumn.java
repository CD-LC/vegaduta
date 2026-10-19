package com.agenticai.core.tables;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One column of a data table: a name and a {@link DataTableColumnType}. Names are identifier-like on purpose
 * (letters, digits, underscore; not starting with a digit; up to 64 chars) so they can be written into workflow
 * expressions and filters without quoting, and so the query builder can inline them as SQL string literals
 * without any escaping question. Names starting with an underscore are reserved for system columns
 * ({@code _id}, {@code _created_at}, {@code _updated_at}).
 */
public record DataTableColumn(String name, DataTableColumnType type) {

    public static final int MAX_NAME_LENGTH = 64;
    static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");

    public DataTableColumn {
        name = requireValidName(name);
        if (type == null) {
            throw new IllegalArgumentException("Column '" + name + "' needs a type");
        }
    }

    public static String requireValidName(String raw) {
        String n = raw == null ? "" : raw.trim();
        if (!NAME_PATTERN.matcher(n).matches()) {
            throw new IllegalArgumentException(
                "Invalid column name '" + n + "' - start with a letter, then letters, digits or underscores (max "
                    + MAX_NAME_LENGTH + " characters)");
        }
        return n;
    }

    /** The JSON shape persisted in {@code data_table.columns}. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("type", type.wire());
        return m;
    }

    public static DataTableColumn fromMap(Map<String, Object> m) {
        Object name = m.get("name");
        Object type = m.get("type");
        return new DataTableColumn(name == null ? null : name.toString(), DataTableColumnType.parse(type == null ? null : type.toString()));
    }
}
