package com.agenticai.core.tables;

import static com.agenticai.core.tables.DataTableAccess.requireFilterOrConfirm;
import static com.agenticai.core.tables.DataTableAccess.rowView;
import static com.agenticai.core.tables.DataTableAccess.toJson;

import com.agenticai.core.tables.DataTableAccess.Caps;
import com.agenticai.core.tables.DataTableDtos.RowPage;
import com.agenticai.core.tables.DataTableDtos.RowQuery;
import com.agenticai.core.tables.DataTableDtos.RowView;
import com.agenticai.core.tables.DataTableDtos.UpsertResult;
import com.agenticai.core.tables.DataTableFilter.Condition;
import com.agenticai.core.tables.DataTableFilter.Op;
import com.agenticai.core.tables.DataTableQueryBuilder.Where;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Row operations of {@link DataTableService} (query, count, insert, update, upsert, delete). Split
 * out of the service only to keep each file reviewable: a plain collaborator, not a bean, so every
 * call runs inside the service method's transaction and under its gate.
 */
final class DataTableRows {

    private final DataTableRepository tables;
    private final DataTableRowStore rows;
    private final DataTableAccess access;
    private final int maxCellBytes;

    DataTableRows(
            DataTableRepository tables,
            DataTableRowStore rows,
            DataTableAccess access,
            int maxCellBytes) {
        this.tables = tables;
        this.rows = rows;
        this.access = access;
        this.maxCellBytes = maxCellBytes;
    }

    private Caps gate() {
        return access.gate();
    }

    private DataTable resolve(String ref) {
        return access.resolve(ref);
    }

    private ResponseStatusException notFound() {
        return access.notFound();
    }

    private void requireRoom(Caps caps, DataTable t, int adding) {
        access.requireRoom(caps, t, adding);
    }

    RowPage queryRows(String tableRef, RowQuery query) {
        gate();
        DataTable t = resolve(tableRef);
        List<DataTableColumn> cols = t.columnDefs();
        Where where = DataTableQueryBuilder.where(cols, query.filter(), maxCellBytes);
        String order = DataTableQueryBuilder.orderBy(cols, query.sort());
        int limit = Math.max(1, Math.min(query.limit(), DataTableService.MAX_PAGE_SIZE));
        int offset = Math.max(0, query.offset());
        List<RowView> items = new ArrayList<>();
        for (DataTableRow r : rows.select(t.getId(), where, order, limit, offset)) {
            items.add(rowView(r));
        }
        return new RowPage(items, rows.count(t.getId(), where), limit, offset);
    }

    long count(String tableRef, List<Condition> filter) {
        gate();
        DataTable t = resolve(tableRef);
        return rows.count(
                t.getId(), DataTableQueryBuilder.where(t.columnDefs(), filter, maxCellBytes));
    }

    RowView insertRow(String tableRef, Map<String, Object> values) {
        return insertRows(tableRef, List.of(values == null ? Map.<String, Object>of() : values))
                .get(0);
    }

    List<RowView> insertRows(String tableRef, List<Map<String, Object>> input) {
        Caps caps = gate();
        if (input == null || input.isEmpty()) {
            throw new IllegalArgumentException("Nothing to insert - provide at least one row");
        }
        if (input.size() > DataTableService.MAX_BULK_INSERT) {
            throw new IllegalArgumentException(
                    "A bulk insert is limited to " + DataTableService.MAX_BULK_INSERT + " rows per call - split it");
        }
        DataTable t = tables.findForUpdate(resolve(tableRef).getId()).orElseThrow(this::notFound);
        List<DataTableColumn> cols = t.columnDefs();
        List<Map<String, Object>> normalized = new ArrayList<>(input.size());
        int n = 0;
        for (Map<String, Object> raw : input) {
            n++;
            Map<String, Object> clean;
            try {
                clean =
                        DataTableValidator.withoutNulls(
                                DataTableValidator.normalizeRow(cols, raw, maxCellBytes));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        input.size() > 1 ? "Row " + n + ": " + e.getMessage() : e.getMessage());
            }
            if (clean.isEmpty()) {
                throw new IllegalArgumentException(
                        input.size() > 1 ? "Row " + n + " has no values" : "The row has no values");
            }
            normalized.add(clean);
        }
        requireRoom(caps, t, normalized.size());
        List<RowView> out = new ArrayList<>(normalized.size());
        for (DataTableRow r : rows.insertAll(t.getId(), normalized)) {
            out.add(rowView(r));
        }
        return out;
    }

    /**
     * Patches one row by id: each given column is set, a null value clears the cell, other columns
     * are untouched.
     */
    RowView updateRow(String tableRef, UUID rowId, Map<String, Object> values) {
        gate();
        DataTable t = resolve(tableRef);
        Map<String, Object> patch =
                DataTableValidator.normalizeRow(t.columnDefs(), values, maxCellBytes);
        if (patch.isEmpty()) {
            throw new IllegalArgumentException(
                    "Nothing to update - provide at least one column value");
        }
        DataTableRow row =
                rows.find(t.getId(), rowId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Row not found"));
        Map<String, Object> merged = new LinkedHashMap<>(row.getData());
        for (Map.Entry<String, Object> e : patch.entrySet()) {
            if (e.getValue() == null) {
                merged.remove(e.getKey());
            } else {
                merged.put(e.getKey(), e.getValue());
            }
        }
        row.setData(merged);
        return rowView(rows.save(row));
    }

    /**
     * Patches every row matching {@code filter}. An empty filter would rewrite the whole table, so
     * it is refused unless {@code confirmAll}. Returns how many rows matched and were updated.
     */
    int updateRows(
            String tableRef,
            List<Condition> filter,
            Map<String, Object> values,
            boolean confirmAll) {
        gate();
        DataTable t = resolve(tableRef);
        requireFilterOrConfirm(filter, confirmAll, "update");
        List<DataTableColumn> cols = t.columnDefs();
        Map<String, Object> patch = DataTableValidator.normalizeRow(cols, values, maxCellBytes);
        if (patch.isEmpty()) {
            throw new IllegalArgumentException(
                    "Nothing to update - provide at least one column value");
        }
        Where where = DataTableQueryBuilder.where(cols, filter, maxCellBytes);
        return applyPatch(t.getId(), where, patch);
    }

    /**
     * Splits a normalized patch into the cells to set and the cells to clear (null value), then
     * merges in SQL.
     */
    private int applyPatch(UUID tableId, Where where, Map<String, Object> patch) {
        List<String> cleared = new ArrayList<>();
        for (Map.Entry<String, Object> e : patch.entrySet()) {
            if (e.getValue() == null) {
                cleared.add(e.getKey());
            }
        }
        return rows.update(tableId, where, toJson(DataTableValidator.withoutNulls(patch)), cleared);
    }

    /**
     * Insert-or-update keyed by one column: when no row holds {@code values[keyColumn]} a row is
     * inserted, else every matching row is patched with the other values. The key column must be
     * given a non-empty value and cannot be a json column.
     */
    UpsertResult upsertRow(String tableRef, String keyColumn, Map<String, Object> values) {
        Caps caps = gate();
        DataTable t = tables.findForUpdate(resolve(tableRef).getId()).orElseThrow(this::notFound);
        List<DataTableColumn> cols = t.columnDefs();
        DataTableColumn key = DataTableValidator.find(cols, keyColumn);
        if (key == null) {
            throw new IllegalArgumentException("Unknown key column '" + keyColumn + "'");
        }
        if (key.type() == DataTableColumnType.JSON) {
            throw new IllegalArgumentException("A json column cannot be the upsert key");
        }
        Map<String, Object> normalized =
                DataTableValidator.normalizeRow(cols, values, maxCellBytes);
        Object keyValue = normalized.get(key.name());
        if (keyValue == null) {
            throw new IllegalArgumentException(
                    "Upsert needs a value for the key column '" + key.name() + "'");
        }
        Where where =
                DataTableQueryBuilder.where(
                        cols, List.of(new Condition(key.name(), Op.EQ, keyValue)), maxCellBytes);
        if (rows.count(t.getId(), where) == 0) {
            requireRoom(caps, t, 1);
            List<DataTableRow> saved =
                    rows.insertAll(t.getId(), List.of(DataTableValidator.withoutNulls(normalized)));
            return new UpsertResult(true, 1, rowView(saved.get(0)));
        }
        Map<String, Object> patch = new LinkedHashMap<>(normalized);
        patch.remove(key.name());
        int affected;
        if (patch.isEmpty()) {
            affected = (int) Math.min(Integer.MAX_VALUE, rows.count(t.getId(), where));
        } else {
            affected = applyPatch(t.getId(), where, patch);
        }
        List<DataTableRow> first =
                rows.select(t.getId(), where, DataTableQueryBuilder.orderBy(cols, null), 1, 0);
        return new UpsertResult(false, affected, first.isEmpty() ? null : rowView(first.get(0)));
    }

    /**
     * Deletes every row matching {@code filter}; an empty filter needs {@code confirmAll}. Returns
     * the count deleted.
     */
    int deleteRows(String tableRef, List<Condition> filter, boolean confirmAll) {
        gate();
        DataTable t = resolve(tableRef);
        requireFilterOrConfirm(filter, confirmAll, "delete");
        return rows.delete(
                t.getId(), DataTableQueryBuilder.where(t.columnDefs(), filter, maxCellBytes));
    }

    void deleteRow(String tableRef, UUID rowId) {
        gate();
        DataTable t = resolve(tableRef);
        Where where =
                DataTableQueryBuilder.where(
                        t.columnDefs(),
                        List.of(new Condition("_id", Op.EQ, rowId.toString())),
                        maxCellBytes);
        if (rows.delete(t.getId(), where) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Row not found");
        }
    }
}
