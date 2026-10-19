package com.agenticai.core.tables;

import java.util.Locale;

/** The five cell types a data table column can declare. Stored lower-case in the table's {@code columns} JSON. */
public enum DataTableColumnType {
    TEXT,
    NUMBER,
    BOOLEAN,
    DATE,
    JSON;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parses {@code text|number|boolean|date|json} (case-insensitive); anything else is a 400-worthy IllegalArgumentException. */
    public static DataTableColumnType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("A column needs a type: text, number, boolean, date or json");
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown column type '" + raw.trim() + "' - use text, number, boolean, date or json");
        }
    }
}
