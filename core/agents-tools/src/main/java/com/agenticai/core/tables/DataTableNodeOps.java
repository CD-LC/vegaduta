package com.agenticai.core.tables;

import java.util.Locale;
import java.util.Set;

/**
 * The operation vocabulary of the workflow {@code data_table} node, kept here (not in the workflow module's
 * handler) so the dry-run / approval guards in other packages can ask "does this node write?" without depending
 * on the handler class. Pure.
 */
public final class DataTableNodeOps {

    public static final String GET_ROWS = "get_rows";
    public static final String INSERT_ROW = "insert_row";
    public static final String UPDATE_ROWS = "update_rows";
    public static final String UPSERT_ROW = "upsert_row";
    public static final String DELETE_ROWS = "delete_rows";
    public static final String COUNT = "count";

    private static final Set<String> WRITES = Set.of(INSERT_ROW, UPDATE_ROWS, UPSERT_ROW, DELETE_ROWS);
    private static final Set<String> ALL = Set.of(GET_ROWS, INSERT_ROW, UPDATE_ROWS, UPSERT_ROW, DELETE_ROWS, COUNT);

    private DataTableNodeOps() {
    }

    /** Lower-cased, trimmed operation; blank defaults to {@code get_rows} (the read-only default). */
    public static String normalize(Object raw) {
        String t = raw == null ? "" : raw.toString().trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return t.isEmpty() ? GET_ROWS : t;
    }

    public static boolean isKnown(String normalizedOperation) {
        return ALL.contains(normalizedOperation);
    }

    /**
     * True when the operation changes data. An UNKNOWN operation counts as a write: the dry run and the step test
     * must never treat something they cannot classify as a harmless read.
     */
    public static boolean isWrite(Object rawOperation) {
        String op = normalize(rawOperation);
        return WRITES.contains(op) || !ALL.contains(op);
    }
}
