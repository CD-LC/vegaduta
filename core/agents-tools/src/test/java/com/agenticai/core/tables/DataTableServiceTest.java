package com.agenticai.core.tables;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tables.DataTableDtos.ColumnSpec;
import com.agenticai.core.tables.DataTableDtos.ImportResult;
import com.agenticai.core.tables.DataTableDtos.RowPage;
import com.agenticai.core.tables.DataTableDtos.RowQuery;
import com.agenticai.core.tables.DataTableDtos.UpsertResult;
import com.agenticai.core.tables.DataTableFilter.Condition;
import com.agenticai.core.tables.DataTableFilter.Op;
import com.agenticai.core.tables.DataTableQueryBuilder.Where;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class DataTableServiceTest {

    private static final UUID TABLE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private DataTableRepository tables;
    private DataTableRowStore rows;
    private TenantLimits limits;
    private DataTable orders;

    @BeforeEach
    void setUp() {
        tables = mock(DataTableRepository.class);
        rows = mock(DataTableRowStore.class);
        limits = mock(TenantLimits.class);
        TenantContext.set(TenantContext.of("t1", "user", Set.of()));
        when(limits.plan("t1")).thenReturn(TenantPlan.UNLIMITED);

        orders =
                new DataTable(
                        "Orders",
                        null,
                        List.of(
                                new DataTableColumn("email", DataTableColumnType.TEXT),
                                new DataTableColumn("qty", DataTableColumnType.NUMBER),
                                new DataTableColumn("meta", DataTableColumnType.JSON)));
        ReflectionTestUtils.setField(orders, "id", TABLE_ID);
        when(tables.findByNameIgnoreCase(anyString()))
                .thenAnswer(
                        inv ->
                                "orders".equalsIgnoreCase(inv.getArgument(0))
                                        ? Optional.of(orders)
                                        : Optional.empty());
        when(tables.findById(TABLE_ID)).thenReturn(Optional.of(orders));
        when(tables.findForUpdate(TABLE_ID)).thenReturn(Optional.of(orders));
        when(tables.save(any(DataTable.class))).thenAnswer(inv -> inv.getArgument(0));
        when(rows.insertAll(eq(TABLE_ID), anyList()))
                .thenAnswer(
                        inv -> {
                            List<DataTableRow> out = new ArrayList<>();
                            for (Object data : (List<?>) inv.getArgument(1)) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> m = (Map<String, Object>) data;
                                out.add(new DataTableRow(TABLE_ID, m));
                            }
                            return out;
                        });
    }

    @Test
    void refusesToRunWithoutTenantContext() {
        TenantContext.clear();
        assertThatThrownBy(() -> service().list())
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service().insertRow("orders", Map.of("email", "a")))
                .isInstanceOf(ResponseStatusException.class);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private DataTableService service() {
        return service(true, true, 50, 100_000);
    }

    private DataTableService service(
            boolean enabled, boolean freePlanEnabled, int maxTables, int maxRows) {
        return new DataTableService(
                tables,
                rows,
                limits,
                enabled,
                freePlanEnabled,
                maxTables,
                maxRows,
                50,
                65_536,
                10_000,
                5_242_880);
    }

    private static List<Condition> eqEmail(String v) {
        return List.of(new Condition("email", Op.EQ, v));
    }

    // ---- flag + plan gate

    @Test
    void withTheFlagOffEveryOperationIsRefusedAndNothingTouchesTheDatabase() {
        DataTableService off = service(false, true, 50, 100_000);

        assertThat(off.isEnabled()).isFalse();
        assertThat(off.config().enabled()).isFalse();
        assertThatThrownBy(off::list)
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> off.create("X", null, List.of()))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> off.queryRows("orders", new RowQuery(List.of(), null, 10, 0)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> off.insertRow("orders", Map.of("email", "a")))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> off.deleteRows("orders", eqEmail("a"), false))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> off.exportCsv("orders"))
                .isInstanceOf(ResponseStatusException.class);

        verifyNoInteractions(tables, rows);
    }

    @Test
    void theSharedDemoTenantIsExcluded() {
        when(limits.isSharedDemoTenant("t1")).thenReturn(true);

        assertThatThrownBy(() -> service().list())
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void freePlanIsLockedWhenTheFreePlanSwitchIsOff() {
        when(limits.plan("t1")).thenReturn(TenantPlan.FREE);
        DataTableService locked = service(true, false, 50, 100_000);

        assertThat(locked.config().planLocked()).isTrue();
        assertThatThrownBy(locked::list)
                .isInstanceOf(TenantLimitExceededException.class)
                .hasMessageContaining("Premium");
    }

    @Test
    void freePlanGetsTighterCapsThanPaid() {
        when(limits.plan("t1")).thenReturn(TenantPlan.FREE);
        DataTableService free = service();

        assertThat(free.config().maxTables()).isEqualTo(DataTableService.FREE_MAX_TABLES);
        assertThat(free.config().maxRowsPerTable()).isEqualTo(DataTableService.FREE_MAX_ROWS);
        when(tables.findByNameIgnoreCase("fourth")).thenReturn(Optional.empty());
        when(tables.count()).thenReturn((long) DataTableService.FREE_MAX_TABLES);

        assertThatThrownBy(() -> free.create("Fourth", null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum of 3");

        when(limits.plan("t1")).thenReturn(TenantPlan.PREMIUM);
        assertThat(service().config().maxTables()).isEqualTo(50);
    }

    // ---- tables + columns

    @Test
    void creatingATableChecksNameUniquenessColumnsAndTheTableCap() {
        when(tables.findByNameIgnoreCase("fresh")).thenReturn(Optional.empty());
        when(tables.count()).thenReturn(0L);

        assertThat(
                        service()
                                .create(
                                        "Fresh",
                                        "desc",
                                        List.of(
                                                new ColumnSpec("a", "text"),
                                                new ColumnSpec("b", "number")))
                                .columns())
                .hasSize(2);
        assertThatThrownBy(() -> service().create("Orders", null, List.of()))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(
                        () ->
                                service()
                                        .create(
                                                "Fresh",
                                                null,
                                                List.of(
                                                        new ColumnSpec("a", "text"),
                                                        new ColumnSpec("A", "text"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate column");
        assertThatThrownBy(
                        () -> service().create("Fresh", null, List.of(new ColumnSpec("a", "blob"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service().create("  ", null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);

        when(tables.count()).thenReturn(2L);
        assertThatThrownBy(() -> service(true, true, 2, 100_000).create("Fresh", null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum of 2");
    }

    @Test
    void anUnknownTableIsA404() {
        when(tables.findByNameIgnoreCase("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().get("ghost"))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void aTypeChangeIsRefusedWhileTheColumnHoldsValues() {
        when(rows.countWithValue(TABLE_ID, "qty")).thenReturn(3L);

        assertThatThrownBy(() -> service().alterColumn("orders", "qty", null, "text"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already has values");
        verify(rows, never()).renameColumn(any(), anyString(), anyString());
    }

    @Test
    void renamingAColumnRewritesTheRowKeysAndTheSchema() {
        assertThat(service().alterColumn("orders", "qty", "amount", null).columns())
                .extracting(DataTableDtos.ColumnView::name)
                .containsExactly("email", "amount", "meta");
        verify(rows).renameColumn(TABLE_ID, "qty", "amount");

        assertThatThrownBy(() -> service().alterColumn("orders", "meta", "email", null))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void addingAndDroppingColumns() {
        assertThat(service().addColumn("orders", "note", "text").columns()).hasSize(4);
        assertThatThrownBy(() -> service().addColumn("orders", "EMAIL", "text"))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        DataTableService svc = service();
        svc.dropColumn("orders", "meta");
        verify(rows).dropColumn(TABLE_ID, "meta");
    }

    // ---- inserts + caps

    @Test
    void insertValidatesAgainstTheColumnTypesAndStripsNulls() {
        service().insertRow("orders", new LinkedHashMap<>(Map.of("email", "a@b.co", "qty", "5")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(rows).insertAll(eq(TABLE_ID), captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0))
                .containsEntry("email", "a@b.co")
                .containsEntry("qty", 5L);

        assertThatThrownBy(() -> service().insertRow("orders", Map.of("qty", "many")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("qty");
        assertThatThrownBy(() -> service().insertRow("orders", Map.of("bogus", 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown column");
        assertThatThrownBy(() -> service().insertRow("orders", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no values");
    }

    @Test
    void aBulkInsertIsCappedAndNamesTheOffendingRow() {
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i <= DataTableService.MAX_BULK_INSERT; i++) {
            tooMany.add(Map.of("email", "x" + i));
        }
        assertThatThrownBy(() -> service().insertRows("orders", tooMany))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limited to 1000");

        assertThatThrownBy(
                        () ->
                                service()
                                        .insertRows(
                                                "orders",
                                                List.of(
                                                        Map.of("email", "ok"),
                                                        Map.of("qty", "bad"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Row 2");
        verify(rows, never()).insertAll(any(), anyList());

        List<Map<String, Object>> max = new ArrayList<>();
        for (int i = 0; i < DataTableService.MAX_BULK_INSERT; i++) {
            max.add(Map.of("email", "x" + i));
        }
        assertThat(service().insertRows("orders", max)).hasSize(DataTableService.MAX_BULK_INSERT);
    }

    @Test
    void thePerTableRowCapIsEnforcedOnInsert() {
        when(rows.count(eq(TABLE_ID), any(Where.class))).thenReturn(9L);
        DataTableService capped = service(true, true, 50, 10);

        assertThatThrownBy(
                        () ->
                                capped.insertRows(
                                        "orders",
                                        List.of(Map.of("email", "a"), Map.of("email", "b"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit of 10");
        verify(rows, never()).insertAll(any(), anyList());

        capped.insertRow("orders", Map.of("email", "fits"));
        verify(rows).insertAll(eq(TABLE_ID), anyList());
    }

    @Test
    void theRowCapMessageMentionsTheUpgradeOnlyForAFreeTenantWhosePlanCapBinds() {
        when(limits.plan("t1")).thenReturn(TenantPlan.FREE);
        when(rows.count(eq(TABLE_ID), any(Where.class)))
                .thenReturn((long) DataTableService.FREE_MAX_ROWS);

        assertThatThrownBy(() -> service().insertRow("orders", Map.of("email", "a")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("upgrade");
    }

    // ---- upsert

    @Test
    void upsertInsertsWhenNoRowHoldsTheKey() {
        when(rows.count(eq(TABLE_ID), any(Where.class))).thenReturn(0L);

        UpsertResult r =
                service().upsertRow("orders", "email", Map.of("email", "a@b.co", "qty", 2));

        assertThat(r.created()).isTrue();
        assertThat(r.affected()).isEqualTo(1);
        verify(rows).insertAll(eq(TABLE_ID), anyList());
        verify(rows, never()).update(any(), any(Where.class), anyString(), anyList());
    }

    @Test
    void upsertPatchesEveryMatchingRowWithoutTouchingTheKey() {
        when(rows.count(eq(TABLE_ID), any(Where.class))).thenReturn(2L);
        when(rows.update(eq(TABLE_ID), any(Where.class), anyString(), anyList())).thenReturn(2);
        DataTableRow existing = new DataTableRow(TABLE_ID, Map.of("email", "a@b.co", "qty", 1L));
        when(rows.select(eq(TABLE_ID), any(Where.class), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(existing));

        UpsertResult r =
                service().upsertRow("orders", "email", Map.of("email", "a@b.co", "qty", 9));

        assertThat(r.created()).isFalse();
        assertThat(r.affected()).isEqualTo(2);
        ArgumentCaptor<Where> where = ArgumentCaptor.forClass(Where.class);
        ArgumentCaptor<String> patch = ArgumentCaptor.forClass(String.class);
        verify(rows).update(eq(TABLE_ID), where.capture(), patch.capture(), anyList());
        assertThat(where.getValue().sql()).isEqualTo("(r.data ->> 'email') = :p0");
        assertThat(where.getValue().params()).containsEntry("p0", "a@b.co");
        assertThat(patch.getValue()).isEqualTo("{\"qty\":9}");
        verify(rows, never()).insertAll(any(), anyList());
    }

    @Test
    void upsertNeedsAKeyColumnThatExistsHasAValueAndIsNotJson() {
        assertThatThrownBy(() -> service().upsertRow("orders", "nope", Map.of("email", "a")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown key column");
        assertThatThrownBy(() -> service().upsertRow("orders", "email", Map.of("qty", 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs a value for the key column");
        assertThatThrownBy(() -> service().upsertRow("orders", "meta", Map.of("meta", "{}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("json column");
    }

    // ---- destructive guards

    @Test
    void deleteWithoutAFilterIsRefusedUnlessConfirmAll() {
        assertThatThrownBy(() -> service().deleteRows("orders", List.of(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Refusing to delete every row");
        assertThatThrownBy(() -> service().deleteRows("orders", null, false))
                .isInstanceOf(IllegalArgumentException.class);
        verify(rows, never()).delete(any(), any(Where.class));

        when(rows.delete(eq(TABLE_ID), any(Where.class))).thenReturn(7);
        assertThat(service().deleteRows("orders", List.of(), true)).isEqualTo(7);
        assertThat(service().deleteRows("orders", eqEmail("a"), false)).isEqualTo(7);
    }

    @Test
    void updateRowsHasTheSameGuardAndRejectsEmptyOrUnknownValues() {
        assertThatThrownBy(() -> service().updateRows("orders", List.of(), Map.of("qty", 1), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Refusing to update every row");
        assertThatThrownBy(() -> service().updateRows("orders", eqEmail("a"), Map.of(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nothing to update");
        assertThatThrownBy(
                        () ->
                                service()
                                        .updateRows(
                                                "orders", eqEmail("a"), Map.of("bogus", 1), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown column");

        when(rows.update(eq(TABLE_ID), any(Where.class), anyString(), anyList())).thenReturn(3);
        assertThat(service().updateRows("orders", eqEmail("a"), Map.of("qty", 4), false))
                .isEqualTo(3);
    }

    @Test
    void aFilterNamingAnUnknownColumnFailsBeforeAnyStatementRuns() {
        assertThatThrownBy(
                        () ->
                                service()
                                        .deleteRows(
                                                "orders",
                                                List.of(new Condition("nope", Op.EQ, "x")),
                                                false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown column");
        verify(rows, never()).delete(any(), any(Where.class));
    }

    // ---- reads

    @Test
    void queryRowsClampsLimitAndOffsetAndReturnsTheTotal() {
        DataTableRow r = new DataTableRow(TABLE_ID, Map.of("email", "a"));
        when(rows.select(eq(TABLE_ID), any(Where.class), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(r));
        when(rows.count(eq(TABLE_ID), any(Where.class))).thenReturn(42L);

        RowPage page = service().queryRows("orders", new RowQuery(List.of(), null, 99_999, -5));

        assertThat(page.limit()).isEqualTo(DataTableService.MAX_PAGE_SIZE);
        assertThat(page.offset()).isZero();
        assertThat(page.total()).isEqualTo(42L);
        assertThat(page.items()).hasSize(1);
        verify(rows).select(eq(TABLE_ID), any(Where.class), anyString(), eq(1000), eq(0));
    }

    // ---- CSV

    @Test
    void csvImportAppendsMappedRowsAndRespectsTheRowCap() {
        when(rows.count(eq(TABLE_ID), any(Where.class))).thenReturn(0L);

        ImportResult result = service().importCsv("orders", "email,qty\na@b.co,1\nc@d.co,2\n");

        assertThat(result.inserted()).isEqualTo(2);
        verify(rows).insertAll(eq(TABLE_ID), anyList());

        when(rows.count(eq(TABLE_ID), any(Where.class))).thenReturn(9L);
        assertThatThrownBy(() -> service(true, true, 50, 10).importCsv("orders", "email\na\nb\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit of 10");
    }

    @Test
    void csvImportRefusesOversizedFilesAndTooManyRows() {
        DataTableService tiny =
                new DataTableService(
                        tables, rows, limits, true, true, 50, 100_000, 50, 65_536, 2, 1024);

        assertThatThrownBy(() -> tiny.importCsv("orders", "email\n" + "x".repeat(2000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("larger than");
        assertThatThrownBy(() -> tiny.importCsv("orders", "email\na\nb\nc\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("More than 2 rows");
        assertThatThrownBy(() -> tiny.importCsv("orders", "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void creatingATableFromCsvInfersColumnsAndImportsTheRows() {
        when(tables.findByNameIgnoreCase("people")).thenReturn(Optional.empty());
        when(tables.count()).thenReturn(0L);
        DataTable[] saved = new DataTable[1];
        when(tables.save(any(DataTable.class)))
                .thenAnswer(
                        inv -> {
                            saved[0] = inv.getArgument(0);
                            ReflectionTestUtils.setField(saved[0], "id", UUID.randomUUID());
                            return saved[0];
                        });
        when(rows.insertAll(any(), anyList())).thenAnswer(inv -> List.of());

        ImportResult r = service().createFromCsv("People", "name,age\nAnn,31\nBob,40\n");

        assertThat(r.inserted()).isEqualTo(2);
        assertThat(saved[0].columnDefs())
                .extracting(DataTableColumn::type)
                .containsExactly(DataTableColumnType.TEXT, DataTableColumnType.NUMBER);
    }

    @Test
    @SuppressWarnings("unchecked")
    void creatingATableFromCsvKeepsDataForHeadersThatWereSanitised() {
        when(tables.findByNameIgnoreCase("odd")).thenReturn(Optional.empty());
        when(tables.count()).thenReturn(0L);
        DataTable[] saved = new DataTable[1];
        when(tables.save(any(DataTable.class)))
                .thenAnswer(
                        inv -> {
                            saved[0] = inv.getArgument(0);
                            ReflectionTestUtils.setField(saved[0], "id", UUID.randomUUID());
                            return saved[0];
                        });
        when(rows.insertAll(any(), anyList())).thenAnswer(inv -> List.of());

        ImportResult r =
                service().createFromCsv("Odd", "First Name,Order #,a,a,2024 sales\nAnn,7,x,y,9\n");

        assertThat(r.ignoredHeaders()).isEmpty();
        assertThat(r.inserted()).isEqualTo(1);
        assertThat(saved[0].columnDefs())
                .extracting(DataTableColumn::name)
                .containsExactly("First_Name", "Order", "a", "a_2", "c_2024_sales");
        ArgumentCaptor<List<Map<String, Object>>> inserted = ArgumentCaptor.forClass(List.class);
        verify(rows).insertAll(any(), inserted.capture());
        assertThat(inserted.getValue()).hasSize(1);
        assertThat(inserted.getValue().get(0))
                .containsOnlyKeys("First_Name", "Order", "a", "a_2", "c_2024_sales");
        assertThat(inserted.getValue().get(0))
                .containsEntry("First_Name", "Ann")
                .containsEntry("a_2", "y");
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateRowsClearsNullCellsInsteadOfStoringJsonNull() {
        when(rows.update(eq(TABLE_ID), any(Where.class), anyString(), anyList())).thenReturn(1);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("qty", 4);
        values.put("email", null);

        service().updateRows("orders", eqEmail("a"), values, false);

        ArgumentCaptor<String> patch = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<String>> cleared = ArgumentCaptor.forClass(List.class);
        verify(rows).update(eq(TABLE_ID), any(Where.class), patch.capture(), cleared.capture());
        assertThat(patch.getValue()).isEqualTo("{\"qty\":4}");
        assertThat(cleared.getValue()).containsExactly("email");
    }

    @Test
    void exportProducesHeaderAndRowsAndRefusesHugeTables() {
        when(rows.count(eq(TABLE_ID), any(Where.class))).thenReturn(2L);
        when(rows.select(eq(TABLE_ID), any(Where.class), anyString(), anyInt(), eq(0)))
                .thenReturn(
                        List.of(
                                new DataTableRow(TABLE_ID, Map.of("email", "a@b.co", "qty", 1L)),
                                new DataTableRow(TABLE_ID, Map.of("email", "c@d.co"))));

        String csv = service().exportCsv("orders");

        assertThat(csv).isEqualTo("email,qty,meta\r\na@b.co,1,\r\nc@d.co,,\r\n");

        when(rows.count(eq(TABLE_ID), any(Where.class)))
                .thenReturn((long) DataTableService.EXPORT_MAX_ROWS + 1);
        assertThatThrownBy(() -> service().exportCsv("orders"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limited to");
    }

    @Test
    void updatingASingleRowByIdPatchesAndClearsCells() {
        DataTableRow existing =
                new DataTableRow(TABLE_ID, new LinkedHashMap<>(Map.of("email", "a", "qty", 1L)));
        UUID rowId = UUID.randomUUID();
        when(rows.find(TABLE_ID, rowId)).thenReturn(Optional.of(existing));
        when(rows.save(any(DataTableRow.class))).thenAnswer(inv -> inv.getArgument(0));
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("qty", null);
        patch.put("email", "z");

        service().updateRow("orders", rowId, patch);

        assertThat(existing.getData()).containsOnly(Map.entry("email", "z"));
    }
}
