package com.agenticai.core.tables;

import com.agenticai.core.tenancy.ListPaging;
import com.agenticai.core.tables.DataTableDtos.AddColumnRequest;
import com.agenticai.core.tenancy.ListPaging;
import com.agenticai.core.tables.DataTableDtos.AlterColumnRequest;
import com.agenticai.core.tables.DataTableDtos.BulkRowsRequest;
import com.agenticai.core.tables.DataTableDtos.Config;
import com.agenticai.core.tables.DataTableDtos.CreateTableRequest;
import com.agenticai.core.tables.DataTableDtos.ImportResult;
import com.agenticai.core.tables.DataTableDtos.RowPage;
import com.agenticai.core.tables.DataTableDtos.RowQuery;
import com.agenticai.core.tables.DataTableDtos.RowRequest;
import com.agenticai.core.tables.DataTableDtos.RowView;
import com.agenticai.core.tables.DataTableDtos.TableView;
import com.agenticai.core.tables.DataTableDtos.UpdateTableRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * {@code /api/data-tables}: the console's view of Data Tables. Thin on purpose - the feature flag, plan gate,
 * limits, validation and status codes are all decided in {@link DataTableService}. {@code {table}} is a table id
 * or its (case-insensitive) name. Authenticated like every other tenant route (no SecurityConfig entry).
 */
@RestController
@RequestMapping("/api/data-tables")
public class DataTableController {

    /** Refuse an absurd upload before reading it; the configured, much lower import cap is applied by the service. */
    private static final long HARD_UPLOAD_LIMIT_BYTES = 20L * 1024 * 1024;

    private final DataTableService service;

    public DataTableController(DataTableService service) {
        this.service = service;
    }

    @GetMapping("/config")
    public Config config() {
        return service.config();
    }

    // ---- tables

    @GetMapping
    public ResponseEntity<List<TableView>> list(@RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        return ListPaging.window(
            service.list(),
            limit, offset, 50, 500);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TableView create(@RequestBody CreateTableRequest request) {
        return service.create(request.name(), request.description(), request.columns());
    }

    /** Multipart CSV: creates a table named {@code name} from the file (header = columns, types inferred). */
    @PostMapping("/import")
    @ResponseStatus(HttpStatus.CREATED)
    public ImportResult createFromCsv(@RequestParam("name") String name, @RequestParam("file") MultipartFile file) {
        return service.createFromCsv(name, readCsv(file));
    }

    @GetMapping("/{table}")
    public TableView get(@PathVariable String table) {
        return service.get(table);
    }

    @PutMapping("/{table}")
    public TableView update(@PathVariable String table, @RequestBody UpdateTableRequest request) {
        return service.update(table, request.name(), request.description());
    }

    @DeleteMapping("/{table}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String table) {
        service.delete(table);
    }

    // ---- columns

    @PostMapping("/{table}/columns")
    @ResponseStatus(HttpStatus.CREATED)
    public TableView addColumn(@PathVariable String table, @RequestBody AddColumnRequest request) {
        return service.addColumn(table, request.name(), request.type());
    }

    @PutMapping("/{table}/columns/{column}")
    public TableView alterColumn(@PathVariable String table, @PathVariable String column, @RequestBody AlterColumnRequest request) {
        return service.alterColumn(table, column, request.newName(), request.type());
    }

    @DeleteMapping("/{table}/columns/{column}")
    public TableView dropColumn(@PathVariable String table, @PathVariable String column) {
        return service.dropColumn(table, column);
    }

    // ---- rows

    /**
     * {@code filter} is JSON: a list of {@code {column, op, value}} or an object of column/value pairs;
     * {@code sort} is {@code column} or {@code column:desc}.
     */
    @GetMapping("/{table}/rows")
    public RowPage rows(
        @PathVariable String table,
        @RequestParam(required = false) String filter,
        @RequestParam(required = false) String sort,
        @RequestParam(defaultValue = "50") int limit,
        @RequestParam(defaultValue = "0") int offset
    ) {
        return service.queryRows(table, new RowQuery(DataTableFilter.parse(filter), DataTableFilter.parseSort(sort), limit, offset));
    }

    @PostMapping("/{table}/rows")
    @ResponseStatus(HttpStatus.CREATED)
    public RowView insertRow(@PathVariable String table, @RequestBody RowRequest request) {
        return service.insertRow(table, request.values());
    }

    @PostMapping("/{table}/rows/bulk")
    @ResponseStatus(HttpStatus.CREATED)
    public List<RowView> insertRows(@PathVariable String table, @RequestBody BulkRowsRequest request) {
        return service.insertRows(table, request.rows());
    }

    @PutMapping("/{table}/rows/{rowId}")
    public RowView updateRow(@PathVariable String table, @PathVariable UUID rowId, @RequestBody RowRequest request) {
        return service.updateRow(table, rowId, request.values());
    }

    @DeleteMapping("/{table}/rows/{rowId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRow(@PathVariable String table, @PathVariable UUID rowId) {
        service.deleteRow(table, rowId);
    }

    // ---- CSV

    /** Multipart CSV appended to an existing table (headers matched to columns). */
    @PostMapping("/{table}/import")
    public ImportResult importCsv(@PathVariable String table, @RequestParam("file") MultipartFile file) {
        return service.importCsv(table, readCsv(file));
    }

    @GetMapping(value = "/{table}/export.csv", produces = "text/csv")
    public ResponseEntity<byte[]> exportCsv(@PathVariable String table) {
        byte[] body = service.exportCsv(table).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"data-table.csv\"")
            .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
            .body(body);
    }

    private static String readCsv(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Choose a .csv file to import");
        }
        if (file.getSize() > HARD_UPLOAD_LIMIT_BYTES) {
            throw new IllegalArgumentException("File is too large - split it and retry");
        }
        try {
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read that file");
        }
    }
}
