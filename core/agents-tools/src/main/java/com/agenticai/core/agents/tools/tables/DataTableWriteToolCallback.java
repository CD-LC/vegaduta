package com.agenticai.core.agents.tools.tables;

import com.agenticai.core.tables.DataTableDtos.RowView;
import com.agenticai.core.tables.DataTableDtos.UpsertResult;
import com.agenticai.core.tables.DataTableFilter;
import com.agenticai.core.tables.DataTableService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * {@code data_table_write} - the WRITE half of the agent Data Tables pair: insert one or a few
 * rows, update the rows matching a filter, upsert by a key column, or delete the rows matching a
 * filter. A tenant-scoped DURABLE side effect (see BuiltInToolClassifications) but not egress:
 * nothing leaves the workspace.
 *
 * <p>Deliberately narrower than the REST/workflow surface: {@code update_rows} and {@code
 * delete_rows} REQUIRE a non-empty filter - there is no {@code confirmAll} here, so a model cannot
 * wipe or rewrite a whole table with one call (a human does that in the console, a workflow author
 * with the explicit node flag). Bulk inserts are capped at {@link #MAX_INSERT_ROWS} rows per call.
 * Failures come back as short text, never as a thrown exception.
 */
public class DataTableWriteToolCallback implements ToolCallback {

    public static final String TOOL_NAME = "data_table_write";
    static final int MAX_INSERT_ROWS = 100;
    private static final Logger log = LoggerFactory.getLogger(DataTableWriteToolCallback.class);

    private final DataTableService service;

    public DataTableWriteToolCallback(DataTableService service) {
        this.service = service;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description(
                        "Write to a workspace data table (stored records that persist between runs). action insert_row adds "
                                + "a row; insert_rows adds several; update_rows changes the rows matching a filter; upsert_row "
                                + "updates the row whose key column matches or adds it; delete_rows removes the rows matching a "
                                + "filter. update_rows and delete_rows need a non-empty filter.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "action": {"type": "string", "enum": ["insert_row", "insert_rows", "update_rows", "upsert_row", "delete_rows"]},
                        "table": {"type": "string", "description": "Table name or id."},
                        "values": {"type": "object", "description": "Column name to value (insert_row, update_rows, upsert_row)."},
                        "rows": {"type": "array", "items": {"type": "object"}, "description": "Rows to add (insert_rows, up to 100)."},
                        "keyColumn": {"type": "string", "description": "Column that identifies the row (upsert_row)."},
                        "filter": {
                            "type": "array",
                            "description": "Conditions that must all hold (update_rows, delete_rows).",
                            "items": {
                                "type": "object",
                                "properties": {
                                    "column": {"type": "string"},
                                    "op": {"type": "string", "enum": ["eq", "ne", "gt", "gte", "lt", "lte", "contains", "starts_with", "in", "is_null", "not_null"]},
                                    "value": {}
                                },
                                "required": ["column"]
                            }
                        }
                    },
                    "required": ["action", "table"]
                }
                """)
                .build();
    }

    @Override
    public String call(String toolInput) {
        String what = "write to the data table";
        try {
            Map<String, Object> args = DataTableToolSupport.parseArgs(toolInput);
            String a = DataTableToolSupport.str(args, "action");
            String act = a == null ? "" : a.toLowerCase(Locale.ROOT);
            String table = DataTableToolSupport.str(args, "table");
            if (table == null) {
                throw new IllegalArgumentException("a table (name or id) is required");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            switch (act) {
                case "insert_row":
                    {
                        what = "insert the row";
                        Map<String, Object> values = DataTableToolSupport.mapArg(args, "values");
                        if (values == null) {
                            throw new IllegalArgumentException(
                                    "values (an object of column to value) is required");
                        }
                        RowView row = service.insertRow(table, values);
                        out.put("inserted", 1);
                        out.put("rowId", row.id());
                        return DataTableToolSupport.json(out);
                    }
                case "insert_rows":
                    {
                        what = "insert the rows";
                        List<Map<String, Object>> rows = new ArrayList<>();
                        for (Object o : DataTableToolSupport.list(args.get("rows"))) {
                            if (!(o instanceof Map<?, ?>)) {
                                throw new IllegalArgumentException(
                                        "every entry of rows must be an object of column to value");
                            }
                            @SuppressWarnings("unchecked")
                            Map<String, Object> m = (Map<String, Object>) o;
                            rows.add(m);
                        }
                        if (rows.size() > MAX_INSERT_ROWS) {
                            throw new IllegalArgumentException(
                                    "insert_rows takes at most "
                                            + MAX_INSERT_ROWS
                                            + " rows per call");
                        }
                        out.put("inserted", service.insertRows(table, rows).size());
                        return DataTableToolSupport.json(out);
                    }
                case "update_rows":
                    {
                        what = "update the rows";
                        Map<String, Object> values = DataTableToolSupport.mapArg(args, "values");
                        if (values == null) {
                            throw new IllegalArgumentException(
                                    "values (an object of column to value) is required");
                        }
                        // confirmAll is never passed through from a model: an empty filter is
                        // refused by the service.
                        out.put(
                                "updated",
                                service.updateRows(
                                        table,
                                        DataTableFilter.parse(args.get("filter")),
                                        values,
                                        false));
                        return DataTableToolSupport.json(out);
                    }
                case "upsert_row":
                    {
                        what = "upsert the row";
                        Map<String, Object> values = DataTableToolSupport.mapArg(args, "values");
                        String key = DataTableToolSupport.str(args, "keyColumn");
                        if (values == null || key == null) {
                            throw new IllegalArgumentException("keyColumn and values are required");
                        }
                        UpsertResult r = service.upsertRow(table, key, values);
                        out.put("created", r.created());
                        out.put("affected", r.affected());
                        return DataTableToolSupport.json(out);
                    }
                case "delete_rows":
                    {
                        what = "delete the rows";
                        out.put(
                                "deleted",
                                service.deleteRows(
                                        table, DataTableFilter.parse(args.get("filter")), false));
                        return DataTableToolSupport.json(out);
                    }
                default:
                    return "Could not write to the data table: action must be insert_row, insert_rows, update_rows, upsert_row or delete_rows.";
            }
        } catch (RuntimeException e) {
            if (!(e instanceof IllegalArgumentException)) {
                log.debug("data_table_write failed", e);
            }
            return DataTableToolSupport.failure(what, e);
        }
    }
}
