package com.agenticai.core.tables;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tables.DataTableDtos.ColumnSpec;
import com.agenticai.core.tables.DataTableDtos.ColumnView;
import com.agenticai.core.tables.DataTableDtos.Config;
import com.agenticai.core.tables.DataTableDtos.RowView;
import com.agenticai.core.tables.DataTableDtos.TableView;
import com.agenticai.core.tables.DataTableFilter.Condition;
import com.agenticai.core.tables.DataTableQueryBuilder.Where;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Gate, caps and shared helpers behind {@link DataTableService}: the feature flag and plan check,
 * table resolution, row-room and CSV-size limits, and the DTO mapping. Split out of the service
 * only to keep each file reviewable; a plain collaborator, not a bean, so it runs inside the
 * caller's transaction.
 */
final class DataTableAccess {

    private static final int MAX_NAME_LENGTH = 100;
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final DataTableRepository tables;
    private final DataTableRowStore rows;
    private final TenantLimits tenantLimits;
    private final boolean enabled;
    private final boolean freePlanEnabled;
    private final int maxTables;
    private final int maxRowsPerTable;
    private final int maxColumns;
    private final int maxCellBytes;
    private final int maxImportRows;
    private final int maxImportBytes;

    DataTableAccess(
            DataTableRepository tables,
            DataTableRowStore rows,
            TenantLimits tenantLimits,
            boolean enabled,
            boolean freePlanEnabled,
            int maxTables,
            int maxRowsPerTable,
            int maxColumns,
            int maxCellBytes,
            int maxImportRows,
            int maxImportBytes) {
        this.tables = tables;
        this.rows = rows;
        this.tenantLimits = tenantLimits;
        this.enabled = enabled;
        this.freePlanEnabled = freePlanEnabled;
        this.maxTables = maxTables;
        this.maxRowsPerTable = maxRowsPerTable;
        this.maxColumns = maxColumns;
        this.maxCellBytes = maxCellBytes;
        this.maxImportRows = maxImportRows;
        this.maxImportBytes = maxImportBytes;
    }

    /** Effective caps for the current tenant. */
    record Caps(int tables, int rowsPerTable, boolean free) {}

    Config config() {
        if (!enabled) {
            return new Config(
                    false,
                    false,
                    maxTables,
                    maxRowsPerTable,
                    maxColumns,
                    maxCellBytes,
                    DataTableService.MAX_BULK_INSERT,
                    maxImportBytes,
                    maxImportRows);
        }
        TenantPlan plan = currentPlan();
        boolean locked = plan == TenantPlan.FREE && !freePlanEnabled;
        Caps caps = capsFor(plan);
        return new Config(
                true,
                locked,
                caps.tables(),
                caps.rowsPerTable(),
                maxColumns,
                maxCellBytes,
                DataTableService.MAX_BULK_INSERT,
                maxImportBytes,
                maxImportRows);
    }

    Caps gate() {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Data tables are not enabled");
        }
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        // Fail closed: with no tenant the Hibernate resolver falls back to the shared `public`
        // schema, where the
        // tenant migrations also run (data_table exists there). A caller that lost its
        // TenantContext must never
        // read or write that store, nor get UNLIMITED caps.
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Data tables need a tenant workspace context");
        }
        if (tenantLimits.isSharedDemoTenant(tenantId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Data tables are not available in the demo workspace");
        }
        TenantPlan plan = currentPlan();
        if (plan == TenantPlan.FREE && !freePlanEnabled) {
            throw new TenantLimitExceededException(
                    "Data tables are a Premium feature - upgrade your plan to store workflow data"
                            + " in tables.");
        }
        return capsFor(plan);
    }

    TenantPlan currentPlan() {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        // No tenant context = a caller with no request (none today): treated as UNLIMITED, same
        // posture as the quality gate.
        TenantPlan plan = tenantId == null ? null : tenantLimits.plan(tenantId);
        return plan == null ? TenantPlan.UNLIMITED : plan;
    }

    Caps capsFor(TenantPlan plan) {
        if (plan == TenantPlan.FREE) {
            return new Caps(
                    Math.min(maxTables, DataTableService.FREE_MAX_TABLES),
                    Math.min(maxRowsPerTable, DataTableService.FREE_MAX_ROWS),
                    true);
        }
        return new Caps(maxTables, maxRowsPerTable, false);
    }

    IllegalArgumentException limit(Caps caps, boolean planBound, String message) {
        return new IllegalArgumentException(
                message
                        + (planBound && caps.free()
                                ? " (the free plan allows fewer - upgrade for more)"
                                : ""));
    }

    /** Finds a table by id (UUID text) or by name (case-insensitive). */
    DataTable resolve(String ref) {
        String r = ref == null ? "" : ref.trim();
        if (r.isEmpty()) {
            throw new IllegalArgumentException("A table (id or name) is required");
        }
        UUID id = parseUuid(r);
        if (id != null) {
            return tables.findById(id).orElseThrow(this::notFound);
        }
        return tables.findByNameIgnoreCase(r)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Data table not found: " + r));
    }

    static UUID parseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Data table not found");
    }

    void requireRoom(Caps caps, DataTable t, int adding) {
        long current = rows.count(t.getId(), Where.none());
        if (current + adding > caps.rowsPerTable()) {
            throw limit(
                    caps,
                    caps.rowsPerTable() < maxRowsPerTable,
                    "Table '"
                            + t.getName()
                            + "' holds "
                            + current
                            + " rows - adding "
                            + adding
                            + " would pass its limit of "
                            + caps.rowsPerTable());
        }
    }

    static void requireFilterOrConfirm(List<Condition> filter, boolean confirmAll, String verb) {
        if ((filter == null || filter.isEmpty()) && !confirmAll) {
            throw new IllegalArgumentException(
                    "Refusing to "
                            + verb
                            + " every row: give a filter, or set confirmAll to true to "
                            + verb
                            + " the whole table");
        }
    }

    void requireCsvSize(String csv) {
        if (csv == null || csv.isBlank()) {
            throw new IllegalArgumentException("The CSV is empty");
        }
        if (csv.getBytes(StandardCharsets.UTF_8).length > maxImportBytes) {
            String size =
                    maxImportBytes >= 1024 * 1024
                            ? (maxImportBytes / (1024 * 1024)) + " MB"
                            : (maxImportBytes / 1024) + " KB";
            throw new IllegalArgumentException(
                    "The CSV is larger than " + size + " - split it and retry");
        }
    }

    List<DataTableColumn> toColumns(List<ColumnSpec> specs) {
        List<DataTableColumn> out = new ArrayList<>();
        if (specs == null) {
            return out;
        }
        if (specs.size() > maxColumns) {
            throw new IllegalArgumentException(
                    "A table can have at most " + maxColumns + " columns");
        }
        Set<String> seen = new HashSet<>();
        for (ColumnSpec s : specs) {
            DataTableColumn c = new DataTableColumn(s.name(), DataTableColumnType.parse(s.type()));
            if (!seen.add(c.name().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Duplicate column name '" + c.name() + "'");
            }
            out.add(c);
        }
        return out;
    }

    static String requireTableName(String raw) {
        String n = raw == null ? "" : raw.trim();
        if (n.isEmpty()) {
            throw new IllegalArgumentException("A table needs a name");
        }
        if (n.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "A table name can be at most " + MAX_NAME_LENGTH + " characters");
        }
        for (int i = 0; i < n.length(); i++) {
            if (Character.isISOControl(n.charAt(i))) {
                throw new IllegalArgumentException(
                        "A table name cannot contain control characters");
            }
        }
        return n;
    }

    static String clean(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    static String toJson(Map<String, Object> m) {
        try {
            return MAPPER.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("The values are not valid JSON");
        }
    }

    static TableView view(DataTable t, long rowCount) {
        List<ColumnView> cols = new ArrayList<>();
        for (DataTableColumn c : t.columnDefs()) {
            cols.add(new ColumnView(c.name(), c.type().wire()));
        }
        return new TableView(
                t.getId(),
                t.getName(),
                t.getDescription(),
                cols,
                rowCount,
                t.getCreatedAt(),
                t.getUpdatedAt());
    }

    static RowView rowView(DataTableRow r) {
        Instant created = r.getCreatedAt();
        return new RowView(r.getId(), r.getData(), created, r.getUpdatedAt());
    }
}
