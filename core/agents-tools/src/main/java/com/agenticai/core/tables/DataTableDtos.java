package com.agenticai.core.tables;

import com.agenticai.core.tables.DataTableFilter.Condition;
import com.agenticai.core.tables.DataTableFilter.Sort;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Request/response shapes of {@link DataTableService} and {@link DataTableController}. */
public final class DataTableDtos {

    private DataTableDtos() {
    }

    public record ColumnSpec(String name, String type) {
    }

    public record ColumnView(String name, String type) {
    }

    public record TableView(
        UUID id, String name, String description, List<ColumnView> columns, long rowCount, Instant createdAt, Instant updatedAt
    ) {
    }

    public record RowView(UUID id, Map<String, Object> data, Instant createdAt, Instant updatedAt) {
    }

    public record RowPage(List<RowView> items, long total, int limit, int offset) {
    }

    /** {@code sort} may be null (creation order). */
    public record RowQuery(List<Condition> filter, Sort sort, int limit, int offset) {
    }

    /** {@code affected} is how many rows were written (1 for an insert; the number matched for an update). */
    public record UpsertResult(boolean created, int affected, RowView row) {
    }

    public record ImportResult(int inserted, int skipped, List<String> errors, List<String> ignoredHeaders, TableView table) {
    }

    /**
     * What the console needs to decide how to render: {@code enabled} is the deployment flag
     * ({@code app.data-tables.enabled}); {@code planLocked} means the flag is on but this tenant's plan is excluded.
     * The caps are the EFFECTIVE ones for this tenant's plan.
     */
    public record Config(
        boolean enabled, boolean planLocked, int maxTables, int maxRowsPerTable, int maxColumns, int maxCellBytes,
        int maxBulkInsert, int maxImportBytes, int maxImportRows
    ) {
    }

    // ---- request bodies (REST)

    public record CreateTableRequest(String name, String description, List<ColumnSpec> columns) {
    }

    public record UpdateTableRequest(String name, String description) {
    }

    public record AddColumnRequest(String name, String type) {
    }

    public record AlterColumnRequest(String newName, String type) {
    }

    public record RowRequest(Map<String, Object> values) {
    }

    public record BulkRowsRequest(List<Map<String, Object>> rows) {
    }

    public record RowsQueryRequest(Object filter, String sort, Integer limit, Integer offset) {
    }
}
