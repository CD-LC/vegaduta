package com.agenticai.core.google.agenttools;

import com.agenticai.core.google.GoogleSheetsClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Exposes {@link GoogleSheetsClient} as a native tool - see {@code GoogleDriveToolCallback}'s
 * javadoc for the shared action-parameter reasoning.
 */
public class GoogleSheetsToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(GoogleSheetsToolCallback.class);
    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    private final GoogleSheetsClient client;

    public GoogleSheetsToolCallback(GoogleSheetsClient client) {
        this.client = client;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("google_sheets")
                .description(
                        "Reads and writes cell ranges in a Google Sheet the connected account can access. Actions: "
                                + "'read_range' (requires spreadsheet_id, range in A1 notation e.g. 'Sheet1!A1:C10'), "
                                + "'write_range' (requires spreadsheet_id, range, and rows - a list of rows, each row a list "
                                + "of cell values as strings; overwrites the cells in range), "
                                + "'append_rows' (requires spreadsheet_id, range naming the table e.g. 'Leads' or 'Leads!A:F', "
                                + "and rows; adds the rows after the table's last row and never overwrites; values are "
                                + "stored as plain text, formulas are not evaluated).")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "action": {"type": "string", "enum": ["read_range", "write_range", "append_rows"]},
                        "spreadsheet_id": {"type": "string"},
                        "range": {"type": "string", "description": "A1 notation, e.g. 'Sheet1!A1:C10'"},
                        "rows": {
                            "type": "array",
                            "description": "Required for 'write_range' and 'append_rows' - list of rows, each a list of cell value strings",
                            "items": {"type": "array", "items": {"type": "string"}}
                        }
                    },
                    "required": ["action", "spreadsheet_id", "range"]
                }
                """)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String toolInput) {
        try {
            Map<String, Object> args = MAPPER.readValue(toolInput, Map.class);
            String action = String.valueOf(args.get("action"));
            String spreadsheetId = asString(args.get("spreadsheet_id"));
            String range = asString(args.get("range"));
            if (spreadsheetId == null || range == null) {
                return "{\"error\": \"spreadsheet_id and range are required\"}";
            }
            return switch (action) {
                case "read_range" ->
                        MAPPER.writeValueAsString(client.readRange(spreadsheetId, range));
                case "write_range" -> {
                    List<List<String>> rows = rowsOf(args.get("rows"));
                    if (rows == null) {
                        yield "{\"error\": \"rows is required for action=write_range\"}";
                    }
                    client.writeRange(spreadsheetId, range, rows);
                    yield "{\"status\": \"ok\"}";
                }
                case "append_rows" -> {
                    List<List<String>> rows = rowsOf(args.get("rows"));
                    if (rows == null || rows.isEmpty()) {
                        yield "{\"error\": \"rows is required for action=append_rows\"}";
                    }
                    if (rows.size() > MAX_APPEND_ROWS) {
                        yield errorJson("append_rows takes at most " + MAX_APPEND_ROWS + " rows per call");
                    }
                    yield MAPPER.writeValueAsString(client.appendRows(spreadsheetId, range, rows));
                }
                default -> "{\"error\": \"Unknown action - use read_range, write_range or append_rows\"}";
            };
        } catch (Exception e) {
            // Class only: a Sheets error body can quote the cell values sent, which may be customer text.
            log.warn("google_sheets tool call failed: {}", e.getClass().getSimpleName());
            return errorJson("Google Sheets call failed: " + e.getMessage());
        }
    }

    /** One call appends a bounded batch; a bulk import belongs in a workflow, not a single tool call. */
    static final int MAX_APPEND_ROWS = 200;

    /** {@code rows} as row-major strings ({@code null} cells become ""), or null when it is not a list. */
    private static List<List<String>> rowsOf(Object rowsRaw) {
        if (!(rowsRaw instanceof List<?> rawRows)) {
            return null;
        }
        List<List<String>> rows = new ArrayList<>();
        for (Object rawRow : rawRows) {
            List<String> row = new ArrayList<>();
            if (rawRow instanceof List<?> rawCells) {
                for (Object cell : rawCells) {
                    row.add(cell == null ? "" : cell.toString());
                }
            }
            rows.add(row);
        }
        return rows;
    }

    // Same bug class fixed in KnowledgeSearchToolCallback (see its
    // errorJson() javadoc) - hand-built "{\"error\": \"...\"}" strings break
    // whenever the interpolated exception message itself contains a raw
    // '"' (e.g. a Google API error quoting a resource id/URL), producing
    // output that looks like JSON but fails to parse.
    private static String errorJson(String message) {
        try {
            return MAPPER.writeValueAsString(Map.of("error", message));
        } catch (Exception e) {
            return "{\"error\": \"error\"}";
        }
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
