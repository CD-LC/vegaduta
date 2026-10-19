package com.agenticai.core.workflow;

import com.agenticai.core.tables.DataTableDtos.RowPage;
import com.agenticai.core.tables.DataTableDtos.RowQuery;
import com.agenticai.core.tables.DataTableDtos.RowView;
import com.agenticai.core.tables.DataTableDtos.UpsertResult;
import com.agenticai.core.tables.DataTableFilter;
import com.agenticai.core.tables.DataTableFilter.Condition;
import com.agenticai.core.tables.DataTableNodeOps;
import com.agenticai.core.tables.DataTableService;
import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * "Data Table" node ({@code data_table}) - read or write the tenant's Data Tables (see {@link DataTableService})
 * so a workflow can keep state between runs without an external database.
 *
 * <p>Config: {@code table} (name or id, required), {@code operation} = {@code get_rows} (default) |
 * {@code insert_row} | {@code update_rows} | {@code upsert_row} | {@code delete_rows} | {@code count};
 * {@code filter} = list of {@code {column, op, value}} (all must hold; values may be expressions);
 * {@code valuesJson} = JSON object of column to value for insert/update/upsert (insert and upsert fall back to the
 * upstream output when it is a JSON object); {@code keyColumn} for upsert; {@code sortColumn} /
 * {@code sortDirection} / {@code limit} (default 100, max 1000) for get_rows; {@code confirmAll} to let
 * update_rows / delete_rows run with an empty filter (without it that is refused - the guard against wiping a
 * table with a mis-wired expression).
 *
 * <p>Output is JSON: get_rows {@code {"rows":[{"_id":..,"col":..}],"count":n,"total":N}}, insert_row
 * {@code {"inserted":1,"row":{..}}}, update_rows {@code {"updated":n}}, upsert_row
 * {@code {"created":b,"affected":n,"row":{..}}}, delete_rows {@code {"deleted":n}}, count {@code {"count":n}}.
 *
 * <p>Failure contract: with the feature off (or the service absent) the node is SKIPPED and the input passes
 * through, like every optional node; a bad configuration or a refused operation (unknown table or column, value
 * that does not fit its column type, cap hit, delete guard) FAILS the node with the reason, because downstream
 * steps usually rely on the data having been written.
 */
@Component
class WorkflowDataTableNodeHandler {

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 1000;

    private final ObjectProvider<DataTableService> service;
    private final ObjectMapper objectMapper;

    WorkflowDataTableNodeHandler(ObjectProvider<DataTableService> service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    /** {@link WorkflowNodeExecutor} has already resolved the node's expression fields before calling this. */
    NodeExecutionResult executeResolved(WorkflowNodeDto node, String input) {
        DataTableService tables = service.getIfAvailable();
        if (tables == null || !tables.isEnabled()) {
            return NodeExecutionResult.skipped(input, "Data tables are not enabled in this deployment - input passed through unchanged.");
        }
        Map<String, Object> config = node.config();
        String table = str(config, "table");
        if (table == null) {
            throw new IllegalArgumentException("Data table node '" + node.label() + "' has no table configured.");
        }
        String op = DataTableNodeOps.normalize(config.get("operation"));
        if (!DataTableNodeOps.isKnown(op)) {
            throw new IllegalArgumentException("Data table node '" + node.label() + "': unknown operation '" + op
                + "' - use get_rows, insert_row, update_rows, upsert_row, delete_rows or count.");
        }
        try {
            return switch (op) {
                case DataTableNodeOps.GET_ROWS -> getRows(tables, table, config);
                case DataTableNodeOps.INSERT_ROW -> insertRow(tables, table, config, input);
                case DataTableNodeOps.UPDATE_ROWS -> updateRows(tables, table, config);
                case DataTableNodeOps.UPSERT_ROW -> upsertRow(tables, table, config, input);
                case DataTableNodeOps.DELETE_ROWS -> deleteRows(tables, table, config);
                default -> count(tables, table, config);
            };
        } catch (ResponseStatusException e) {
            throw new IllegalArgumentException("Data table " + op + " failed: " + e.getReason(), e);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Data table " + op + " failed: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------

    private NodeExecutionResult getRows(DataTableService tables, String table, Map<String, Object> config) {
        int limit = clampLimit(str(config, "limit"));
        RowPage page = tables.queryRows(table, new RowQuery(
            filter(config), DataTableFilter.sortOf(str(config, "sortColumn"), str(config, "sortDirection")), limit, 0));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RowView r : page.items()) {
            rows.add(flatten(r));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("count", rows.size());
        out.put("total", page.total());
        return NodeExecutionResult.ok(json(out), "Read " + rows.size() + " of " + page.total() + " row(s) from '" + table + "'.", null);
    }

    private NodeExecutionResult insertRow(DataTableService tables, String table, Map<String, Object> config, String input) {
        Map<String, Object> values = values(config, input, true);
        RowView row = tables.insertRow(table, values);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("inserted", 1);
        out.put("row", flatten(row));
        return NodeExecutionResult.ok(json(out), "Inserted 1 row into '" + table + "'.", null);
    }

    private NodeExecutionResult updateRows(DataTableService tables, String table, Map<String, Object> config) {
        Map<String, Object> values = values(config, null, false);
        int updated = tables.updateRows(table, filter(config), values, bool(config, "confirmAll"));
        return NodeExecutionResult.ok(json(Map.of("updated", updated)), "Updated " + updated + " row(s) in '" + table + "'.", null);
    }

    private NodeExecutionResult upsertRow(DataTableService tables, String table, Map<String, Object> config, String input) {
        String key = str(config, "keyColumn");
        if (key == null) {
            throw new IllegalArgumentException("upsert_row needs a keyColumn.");
        }
        Map<String, Object> values = values(config, input, true);
        UpsertResult r = tables.upsertRow(table, key, values);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", r.created());
        out.put("affected", r.affected());
        out.put("row", r.row() == null ? null : flatten(r.row()));
        return NodeExecutionResult.ok(json(out),
            (r.created() ? "Inserted a new row into '" : "Updated " + r.affected() + " existing row(s) in '") + table + "'.", null);
    }

    private NodeExecutionResult deleteRows(DataTableService tables, String table, Map<String, Object> config) {
        int deleted = tables.deleteRows(table, filter(config), bool(config, "confirmAll"));
        return NodeExecutionResult.ok(json(Map.of("deleted", deleted)), "Deleted " + deleted + " row(s) from '" + table + "'.", null);
    }

    private NodeExecutionResult count(DataTableService tables, String table, Map<String, Object> config) {
        long n = tables.count(table, filter(config));
        return NodeExecutionResult.ok(json(Map.of("count", n)), "Counted " + n + " row(s) in '" + table + "'.", null);
    }

    // ------------------------------------------------------------------

    /**
     * The condition list. A half-filled row from the canvas form (no column yet) is dropped rather than failing
     * the run; {@code filter} may also be a JSON string or an object of column/value pairs.
     */
    private List<Condition> filter(Map<String, Object> config) {
        Object raw = config.get("filter");
        if (raw instanceof List<?> list) {
            List<Object> kept = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Object column = m.get("column");
                    if (column == null || column.toString().isBlank()) {
                        continue;
                    }
                }
                kept.add(o);
            }
            return DataTableFilter.parse(kept);
        }
        return DataTableFilter.parse(raw);
    }

    /** {@code valuesJson} (text or an already-parsed object); optionally falling back to the upstream JSON object. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> values(Map<String, Object> config, String input, boolean fallbackToInput) {
        Object raw = config.get("valuesJson");
        if (raw == null) {
            raw = config.get("values");
        }
        if (raw instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        String text = raw == null ? "" : raw.toString().trim();
        if (!text.isEmpty()) {
            Object parsed = parse(text);
            if (parsed instanceof Map<?, ?> m) {
                return (Map<String, Object>) m;
            }
            throw new IllegalArgumentException("'valuesJson' must be a JSON object of column names to values.");
        }
        if (fallbackToInput && input != null && input.trim().startsWith("{")) {
            Object parsed = parse(input.trim());
            if (parsed instanceof Map<?, ?> m) {
                return (Map<String, Object>) m;
            }
        }
        throw new IllegalArgumentException("This operation needs 'valuesJson' - a JSON object of column names to values"
            + (fallbackToInput ? " (or a JSON object as the step's input)." : "."));
    }

    private Object parse(String text) {
        try {
            return objectMapper.readValue(text, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("'valuesJson' is not valid JSON.");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private static Map<String, Object> flatten(RowView row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("_id", row.id() == null ? null : row.id().toString());
        if (row.data() != null) {
            out.putAll(row.data());
        }
        return out;
    }

    private static int clampLimit(String raw) {
        if (raw == null) {
            return DEFAULT_LIMIT;
        }
        try {
            return Math.max(1, Math.min(Integer.parseInt(raw.trim()), MAX_LIMIT));
        } catch (NumberFormatException e) {
            return DEFAULT_LIMIT;
        }
    }

    private static boolean bool(Map<String, Object> c, String key) {
        Object v = c.get(key);
        return v instanceof Boolean b ? b : v != null && "true".equalsIgnoreCase(v.toString().trim());
    }

    private static String str(Map<String, Object> c, String key) {
        Object raw = c.get(key);
        return raw == null || raw.toString().isBlank() ? null : raw.toString().trim();
    }
}
