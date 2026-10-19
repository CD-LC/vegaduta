package com.agenticai.core.tables;

import static com.agenticai.core.tables.DataTableAccess.clean;
import static com.agenticai.core.tables.DataTableAccess.requireTableName;
import static com.agenticai.core.tables.DataTableAccess.view;

import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.tables.DataTableAccess.Caps;
import com.agenticai.core.tables.DataTableDtos.ColumnSpec;
import com.agenticai.core.tables.DataTableDtos.Config;
import com.agenticai.core.tables.DataTableDtos.ImportResult;
import com.agenticai.core.tables.DataTableDtos.RowPage;
import com.agenticai.core.tables.DataTableDtos.RowQuery;
import com.agenticai.core.tables.DataTableDtos.RowView;
import com.agenticai.core.tables.DataTableDtos.TableView;
import com.agenticai.core.tables.DataTableDtos.UpsertResult;
import com.agenticai.core.tables.DataTableFilter.Condition;
import com.agenticai.core.tables.DataTableQueryBuilder.Where;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Tenant-scoped Data Tables: the spreadsheet-like store workflows ({@code data_table} node), agents
 * ({@code data_table_query} / {@code data_table_write}) and the console share, so an automation can
 * keep state without an external database. Everything runs in the caller's tenant schema
 * (TenantContext through the JPA multi-tenancy; there is no tenant id parameter anywhere).
 *
 * <p><b>Gate.</b> The whole feature sits behind {@code app.data-tables.enabled} (default OFF: every
 * operation here refuses with 404, the console reads {@link #config()}, the node reports itself
 * unavailable and the agent tools are never offered). When on, a FREE-plan tenant may use it under
 * tighter caps ({@link #FREE_MAX_TABLES} / {@link #FREE_MAX_ROWS}) unless {@code
 * app.data-tables.free-plan-enabled=false} turns it into a Premium feature (403 {@link
 * TenantLimitExceededException}). The plan read is {@code TenantLimits#plan} (effective, free-mode
 * aware) because this spends no model budget. The shared /try demo tenant is excluded: it is one
 * schema serving every anonymous visitor, so a writable store there would be shared state between
 * strangers.
 *
 * <p><b>Limits</b> ({@code app.data-tables.*}): tables per tenant, rows per table, columns per
 * table, bytes per cell; bulk insert is capped at {@link #MAX_BULK_INSERT} rows per call and CSV
 * import at {@code max-import-bytes} / {@code max-import-rows}. A destructive filter operation
 * (update_rows, delete_rows) with no condition is refused unless the caller passes {@code
 * confirmAll}.
 *
 * <p>Errors use only the exception types the platform's handler already maps:
 * IllegalArgumentException (400), ResponseStatusException (404/409) and
 * TenantLimitExceededException (403).
 */
@Service
public class DataTableService {

    public static final int MAX_BULK_INSERT = 1000;
    public static final int MAX_PAGE_SIZE = 1000;
    static final int FREE_MAX_TABLES = 3;
    static final int FREE_MAX_ROWS = 1000;
    static final int EXPORT_MAX_ROWS = 50_000;

    private final DataTableRepository tables;
    private final DataTableRowStore rows;
    private final boolean enabled;
    private final int maxTables;
    private final int maxRowsPerTable;
    private final int maxColumns;
    private final int maxCellBytes;
    private final int maxImportRows;
    private final int maxImportBytes;
    private final DataTableAccess access;
    private final DataTableRows rowOps;

    public DataTableService(
            DataTableRepository tables,
            DataTableRowStore rows,
            TenantLimits tenantLimits,
            @Value("${app.data-tables.enabled:false}") boolean enabled,
            @Value("${app.data-tables.free-plan-enabled:true}") boolean freePlanEnabled,
            @Value("${app.data-tables.max-tables:50}") int maxTables,
            @Value("${app.data-tables.max-rows-per-table:100000}") int maxRowsPerTable,
            @Value("${app.data-tables.max-columns:50}") int maxColumns,
            @Value("${app.data-tables.max-cell-bytes:65536}") int maxCellBytes,
            @Value("${app.data-tables.max-import-rows:10000}") int maxImportRows,
            @Value("${app.data-tables.max-import-bytes:1048576}") int maxImportBytes) {
        this.tables = tables;
        this.rows = rows;
        this.enabled = enabled;
        this.maxTables = Math.max(1, maxTables);
        this.maxRowsPerTable = Math.max(1, maxRowsPerTable);
        this.maxColumns = Math.max(1, maxColumns);
        this.maxCellBytes = Math.max(16, maxCellBytes);
        this.maxImportRows = Math.max(1, maxImportRows);
        this.maxImportBytes = Math.max(1024, maxImportBytes);
        this.access =
                new DataTableAccess(
                        tables,
                        rows,
                        tenantLimits,
                        enabled,
                        freePlanEnabled,
                        this.maxTables,
                        this.maxRowsPerTable,
                        this.maxColumns,
                        this.maxCellBytes,
                        this.maxImportRows,
                        this.maxImportBytes);
        this.rowOps = new DataTableRows(tables, rows, access, this.maxCellBytes);
    }

    /**
     * The deployment flag alone - what the node handler and the tool resolver check before offering
     * anything.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /** Effective caps for the current tenant. */

    // ------------------------------------------------------------------
    // Gate + config (implemented by DataTableAccess)
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Config config() {
        return access.config();
    }

    private Caps gate() {
        return access.gate();
    }

    private IllegalArgumentException limit(Caps caps, boolean planBound, String message) {
        return access.limit(caps, planBound, message);
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

    private void requireCsvSize(String csv) {
        access.requireCsvSize(csv);
    }

    private List<DataTableColumn> toColumns(List<ColumnSpec> specs) {
        return access.toColumns(specs);
    }

    // ------------------------------------------------------------------
    // Tables
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<TableView> list() {
        gate();
        Map<UUID, Long> counts = rows.countsByTable();
        List<TableView> out = new ArrayList<>();
        for (DataTable t : tables.findAll(Sort.by(Sort.Direction.ASC, "name"))) {
            out.add(view(t, counts.getOrDefault(t.getId(), 0L)));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public TableView get(String tableRef) {
        gate();
        DataTable t = resolve(tableRef);
        return view(t, rows.count(t.getId(), Where.none()));
    }

    @Transactional
    public TableView create(String rawName, String description, List<ColumnSpec> columnSpecs) {
        Caps caps = gate();
        String name = requireTableName(rawName);
        if (tables.findByNameIgnoreCase(name).isPresent()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "A data table named '" + name + "' already exists");
        }
        if (tables.count() >= caps.tables()) {
            throw limit(
                    caps,
                    caps.tables() < maxTables,
                    "This workspace already has the maximum of " + caps.tables() + " data tables");
        }
        List<DataTableColumn> defs = toColumns(columnSpecs);
        return view(tables.save(new DataTable(name, clean(description, 2000), defs)), 0);
    }

    /**
     * Creates a table from CSV text: header = columns (names sanitised, types inferred), then
     * imports every row.
     */
    @Transactional
    public ImportResult createFromCsv(String rawName, String csv) {
        Caps caps = gate();
        String name = requireTableName(rawName);
        requireCsvSize(csv);
        DataTableCsv.Parsed parsed = DataTableCsv.parse(csv, maxImportRows);
        List<DataTableColumn> defs = DataTableCsv.inferColumns(parsed, maxColumns);
        if (parsed.rows().size() > caps.rowsPerTable()) {
            throw limit(
                    caps,
                    caps.rowsPerTable() < maxRowsPerTable,
                    "The file has "
                            + parsed.rows().size()
                            + " rows - a table can hold at most "
                            + caps.rowsPerTable());
        }
        if (tables.findByNameIgnoreCase(name).isPresent()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "A data table named '" + name + "' already exists");
        }
        if (tables.count() >= caps.tables()) {
            throw limit(
                    caps,
                    caps.tables() < maxTables,
                    "This workspace already has the maximum of " + caps.tables() + " data tables");
        }
        DataTable saved = tables.save(new DataTable(name, null, defs));
        // The columns were derived from the header by position (names sanitised and de-duplicated),
        // so map the rows
        // against those names; matching the raw header text would drop every column whose name was
        // rewritten.
        List<String> columnNames = new ArrayList<>();
        for (DataTableColumn d : defs) {
            columnNames.add(d.name());
        }
        DataTableCsv.Mapped mapped =
                DataTableCsv.toRows(
                        defs, new DataTableCsv.Parsed(columnNames, parsed.rows()), maxCellBytes);
        if (!mapped.rows().isEmpty()) {
            rows.insertAll(saved.getId(), mapped.rows());
        }
        return new ImportResult(
                mapped.rows().size(),
                mapped.skipped(),
                mapped.errors(),
                mapped.ignoredHeaders(),
                view(saved, mapped.rows().size()));
    }

    @Transactional
    public TableView update(String tableRef, String newName, String description) {
        gate();
        DataTable t = resolve(tableRef);
        if (newName != null) {
            String name = requireTableName(newName);
            tables.findByNameIgnoreCase(name)
                    .ifPresent(
                            other -> {
                                if (!other.getId().equals(t.getId())) {
                                    throw new ResponseStatusException(
                                            HttpStatus.CONFLICT,
                                            "A data table named '" + name + "' already exists");
                                }
                            });
            t.setName(name);
        }
        if (description != null) {
            t.setDescription(clean(description, 2000));
        }
        t.touch();
        DataTable saved = tables.save(t);
        return view(saved, rows.count(saved.getId(), Where.none()));
    }

    @Transactional
    public void delete(String tableRef) {
        gate();
        tables.delete(resolve(tableRef));
    }

    @Transactional
    public TableView addColumn(String tableRef, String rawName, String rawType) {
        gate();
        DataTable t = tables.findForUpdate(resolve(tableRef).getId()).orElseThrow(this::notFound);
        List<DataTableColumn> defs = t.columnDefs();
        if (defs.size() >= maxColumns) {
            throw new IllegalArgumentException(
                    "A table can have at most " + maxColumns + " columns");
        }
        DataTableColumn col = new DataTableColumn(rawName, DataTableColumnType.parse(rawType));
        if (DataTableValidator.find(defs, col.name()) != null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The table already has a column named '" + col.name() + "'");
        }
        defs.add(col);
        t.setColumnDefs(defs);
        t.touch();
        DataTable saved = tables.save(t);
        return view(saved, rows.count(saved.getId(), Where.none()));
    }

    /**
     * Renames a column and/or changes its type. A rename rewrites the key in every row; a type
     * change is only allowed while no row holds a value in that column (existing cells are never
     * silently reinterpreted).
     */
    @Transactional
    public TableView alterColumn(String tableRef, String column, String newName, String newType) {
        gate();
        DataTable t = tables.findForUpdate(resolve(tableRef).getId()).orElseThrow(this::notFound);
        List<DataTableColumn> defs = t.columnDefs();
        DataTableColumn current = DataTableValidator.find(defs, column);
        if (current == null) {
            throw new IllegalArgumentException("Unknown column '" + column + "'");
        }
        String targetName = current.name();
        DataTableColumnType targetType = current.type();
        if (newType != null && !newType.isBlank()) {
            DataTableColumnType parsed = DataTableColumnType.parse(newType);
            if (parsed != current.type()) {
                if (rows.countWithValue(t.getId(), current.name()) > 0) {
                    throw new IllegalArgumentException(
                            "Column '"
                                    + current.name()
                                    + "' already has values, so its type cannot change - add a new"
                                    + " column and copy the data across");
                }
                targetType = parsed;
            }
        }
        if (newName != null && !newName.isBlank() && !newName.trim().equals(current.name())) {
            String valid = DataTableColumn.requireValidName(newName);
            DataTableColumn clash = DataTableValidator.find(defs, valid);
            if (clash != null
                    && clash.name().equalsIgnoreCase(valid)
                    && !clash.name().equals(current.name())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "The table already has a column named '" + valid + "'");
            }
            rows.renameColumn(t.getId(), current.name(), valid);
            targetName = valid;
        }
        List<DataTableColumn> next = new ArrayList<>();
        for (DataTableColumn c : defs) {
            next.add(
                    c.name().equals(current.name())
                            ? new DataTableColumn(targetName, targetType)
                            : c);
        }
        t.setColumnDefs(next);
        t.touch();
        DataTable saved = tables.save(t);
        return view(saved, rows.count(saved.getId(), Where.none()));
    }

    @Transactional
    public TableView dropColumn(String tableRef, String column) {
        gate();
        DataTable t = tables.findForUpdate(resolve(tableRef).getId()).orElseThrow(this::notFound);
        List<DataTableColumn> defs = t.columnDefs();
        DataTableColumn current = DataTableValidator.find(defs, column);
        if (current == null) {
            throw new IllegalArgumentException("Unknown column '" + column + "'");
        }
        rows.dropColumn(t.getId(), current.name());
        defs.removeIf(c -> c.name().equals(current.name()));
        t.setColumnDefs(defs);
        t.touch();
        DataTable saved = tables.save(t);
        return view(saved, rows.count(saved.getId(), Where.none()));
    }

    // ------------------------------------------------------------------
    // Rows (implemented by DataTableRows)
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public RowPage queryRows(String tableRef, RowQuery query) {
        return rowOps.queryRows(tableRef, query);
    }

    @Transactional(readOnly = true)
    public long count(String tableRef, List<Condition> filter) {
        return rowOps.count(tableRef, filter);
    }

    @Transactional
    public RowView insertRow(String tableRef, Map<String, Object> values) {
        return rowOps.insertRow(tableRef, values);
    }

    @Transactional
    public List<RowView> insertRows(String tableRef, List<Map<String, Object>> input) {
        return rowOps.insertRows(tableRef, input);
    }

    /**
     * Patches one row by id: each given column is set, a null value clears the cell, other columns
     * are untouched.
     */
    @Transactional
    public RowView updateRow(String tableRef, UUID rowId, Map<String, Object> values) {
        return rowOps.updateRow(tableRef, rowId, values);
    }

    /**
     * Patches every row matching {@code filter}. An empty filter would rewrite the whole table, so
     * it is refused unless {@code confirmAll}. Returns how many rows matched and were updated.
     */
    @Transactional
    public int updateRows(
            String tableRef,
            List<Condition> filter,
            Map<String, Object> values,
            boolean confirmAll) {
        return rowOps.updateRows(tableRef, filter, values, confirmAll);
    }

    /**
     * Insert-or-update keyed by one column: when no row holds {@code values[keyColumn]} a row is
     * inserted, else every matching row is patched with the other values. The key column must be
     * given a non-empty value and cannot be a json column.
     */
    @Transactional
    public UpsertResult upsertRow(String tableRef, String keyColumn, Map<String, Object> values) {
        return rowOps.upsertRow(tableRef, keyColumn, values);
    }

    /**
     * Deletes every row matching {@code filter}; an empty filter needs {@code confirmAll}. Returns
     * the count deleted.
     */
    @Transactional
    public int deleteRows(String tableRef, List<Condition> filter, boolean confirmAll) {
        return rowOps.deleteRows(tableRef, filter, confirmAll);
    }

    @Transactional
    public void deleteRow(String tableRef, UUID rowId) {
        rowOps.deleteRow(tableRef, rowId);
    }

    // ------------------------------------------------------------------
    // CSV
    // ------------------------------------------------------------------

    /**
     * Appends the rows of a CSV file to an existing table; headers are matched to columns, bad rows
     * are skipped and reported.
     */
    @Transactional
    public ImportResult importCsv(String tableRef, String csv) {
        Caps caps = gate();
        requireCsvSize(csv);
        DataTable t = tables.findForUpdate(resolve(tableRef).getId()).orElseThrow(this::notFound);
        DataTableCsv.Parsed parsed = DataTableCsv.parse(csv, maxImportRows);
        DataTableCsv.Mapped mapped = DataTableCsv.toRows(t.columnDefs(), parsed, maxCellBytes);
        requireRoom(caps, t, mapped.rows().size());
        if (!mapped.rows().isEmpty()) {
            rows.insertAll(t.getId(), mapped.rows());
        }
        return new ImportResult(
                mapped.rows().size(),
                mapped.skipped(),
                mapped.errors(),
                mapped.ignoredHeaders(),
                view(t, rows.count(t.getId(), Where.none())));
    }

    /**
     * The whole table as CSV (declared columns only, in order). Refused above {@value
     * #EXPORT_MAX_ROWS} rows.
     */
    @Transactional(readOnly = true)
    public String exportCsv(String tableRef) {
        gate();
        DataTable t = resolve(tableRef);
        long total = rows.count(t.getId(), Where.none());
        if (total > EXPORT_MAX_ROWS) {
            throw new IllegalArgumentException(
                    "This table has "
                            + total
                            + " rows - a CSV export is limited to "
                            + EXPORT_MAX_ROWS);
        }
        List<DataTableColumn> cols = t.columnDefs();
        List<Map<String, Object>> all = new ArrayList<>();
        String order = DataTableQueryBuilder.orderBy(cols, null);
        for (int offset = 0; offset < total; offset += MAX_PAGE_SIZE) {
            for (DataTableRow r :
                    rows.select(t.getId(), Where.none(), order, MAX_PAGE_SIZE, offset)) {
                all.add(r.getData());
            }
        }
        return DataTableCsv.export(cols, all);
    }
}
