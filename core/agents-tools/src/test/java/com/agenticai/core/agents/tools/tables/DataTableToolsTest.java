package com.agenticai.core.agents.tools.tables;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.tables.DataTableDtos.ColumnView;
import com.agenticai.core.tables.DataTableDtos.RowPage;
import com.agenticai.core.tables.DataTableDtos.RowQuery;
import com.agenticai.core.tables.DataTableDtos.RowView;
import com.agenticai.core.tables.DataTableDtos.TableView;
import com.agenticai.core.tables.DataTableDtos.UpsertResult;
import com.agenticai.core.tables.DataTableFilter.Condition;
import com.agenticai.core.tables.DataTableFilter.Op;
import com.agenticai.core.tables.DataTableService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class DataTableToolsTest {

    private DataTableService service;
    private DataTableQueryToolCallback query;
    private DataTableWriteToolCallback write;

    @BeforeEach
    void setUp() {
        service = mock(DataTableService.class);
        query = new DataTableQueryToolCallback(service);
        write = new DataTableWriteToolCallback(service);
    }

    @Test
    void toolDefinitionsAreNamedAndCarryAParseableSchema() {
        assertThat(query.getToolDefinition().name()).isEqualTo("data_table_query");
        assertThat(write.getToolDefinition().name()).isEqualTo("data_table_write");
        assertThat(query.getToolDefinition().inputSchema())
                .contains("\"action\"")
                .contains("get_rows");
        assertThat(write.getToolDefinition().inputSchema()).contains("delete_rows");
        // schemas are paid for in prompt tokens on every turn they are offered: keep each small
        assertThat(query.getToolDefinition().description().length()).isLessThan(400);
        assertThat(write.getToolDefinition().description().length()).isLessThan(500);
        assertThat(
                        query.getToolDefinition().inputSchema().length()
                                + write.getToolDefinition().inputSchema().length())
                .isLessThan(5000);
    }

    @Test
    void listTablesReturnsTablesWithTheirColumnsAndRendersTimestamps() {
        when(service.list())
                .thenReturn(
                        List.of(
                                new TableView(
                                        UUID.randomUUID(),
                                        "Orders",
                                        null,
                                        List.of(new ColumnView("email", "text")),
                                        3,
                                        Instant.parse("2026-01-01T00:00:00Z"),
                                        Instant.parse("2026-01-02T00:00:00Z"))));

        String out = query.call("{\"action\":\"list_tables\"}");

        assertThat(out)
                .contains("\"name\":\"Orders\"")
                .contains("\"email\"")
                .contains("2026-01-01T00:00:00Z");
    }

    @Test
    void getRowsPassesTheFilterSortAndAClampedLimitAndFlattensRows() {
        UUID id = UUID.randomUUID();
        when(service.queryRows(eq("orders"), any(RowQuery.class)))
                .thenReturn(
                        new RowPage(
                                List.of(
                                        new RowView(
                                                id,
                                                Map.of("email", "a@b.co"),
                                                Instant.now(),
                                                Instant.now())),
                                7,
                                100,
                                0));

        String out =
                query.call(
                        "{\"action\":\"get_rows\",\"table\":\"orders\",\"filter\":[{\"column\":\"qty\",\"op\":\">\",\"value\":2}],"
                                + "\"sortColumn\":\"qty\",\"sortDirection\":\"desc\",\"limit\":5000}");

        ArgumentCaptor<RowQuery> q = ArgumentCaptor.forClass(RowQuery.class);
        verify(service).queryRows(eq("orders"), q.capture());
        assertThat(q.getValue().limit()).isEqualTo(DataTableQueryToolCallback.MAX_LIMIT);
        assertThat(q.getValue().filter()).containsExactly(new Condition("qty", Op.GT, 2));
        assertThat(q.getValue().sort().column()).isEqualTo("qty");
        assertThat(q.getValue().sort().descending()).isTrue();
        assertThat(out)
                .contains("\"total\":7")
                .contains("\"_id\":\"" + id + "\"")
                .contains("a@b.co")
                .doesNotContain("createdAt");
    }

    @Test
    void getRowsDefaultsTheLimit() {
        when(service.queryRows(eq("orders"), any(RowQuery.class)))
                .thenReturn(new RowPage(List.of(), 0, 20, 0));

        query.call("{\"action\":\"get_rows\",\"table\":\"orders\"}");

        ArgumentCaptor<RowQuery> q = ArgumentCaptor.forClass(RowQuery.class);
        verify(service).queryRows(eq("orders"), q.capture());
        assertThat(q.getValue().limit()).isEqualTo(DataTableQueryToolCallback.DEFAULT_LIMIT);
    }

    @Test
    void countReturnsTheNumber() {
        when(service.count(eq("orders"), anyList())).thenReturn(12L);

        assertThat(query.call("{\"action\":\"count\",\"table\":\"orders\"}"))
                .isEqualTo("{\"count\":12}");
    }

    @Test
    void failuresComeBackAsTextNeverAsExceptions() {
        when(service.queryRows(eq("ghost"), any(RowQuery.class)))
                .thenThrow(
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Data table not found: ghost"));

        assertThat(query.call("{\"action\":\"get_rows\",\"table\":\"ghost\"}"))
                .isEqualTo("Could not read rows: Data table not found: ghost");
        assertThat(query.call("{\"action\":\"get_rows\"}"))
                .contains("a table (name or id) is required");
        assertThat(query.call("{\"action\":\"explode\"}"))
                .startsWith("Could not read data tables: action must be");
        assertThat(query.call("not json")).startsWith("Could not read data tables:");
        when(service.list()).thenThrow(new IllegalStateException("db down"));
        assertThat(query.call("{\"action\":\"list_tables\"}"))
                .isEqualTo(
                        "Could not list the data tables: an unexpected error occurred - try again");
    }

    @Test
    void anOverlongResultIsTruncatedWithAHint() {
        String big = "x".repeat(DataTableToolSupport.MAX_RESULT_CHARS * 2);
        when(service.queryRows(eq("orders"), any(RowQuery.class)))
                .thenReturn(
                        new RowPage(
                                List.of(
                                        new RowView(
                                                UUID.randomUUID(),
                                                Map.of("note", big),
                                                Instant.now(),
                                                Instant.now())),
                                1,
                                20,
                                0));

        String out = query.call("{\"action\":\"get_rows\",\"table\":\"orders\"}");

        assertThat(out.length()).isLessThan(DataTableToolSupport.MAX_RESULT_CHARS + 200);
        assertThat(out).contains("[truncated at");
    }

    // ---- write

    @Test
    void insertRowReturnsTheNewRowId() {
        UUID id = UUID.randomUUID();
        when(service.insertRow(eq("orders"), anyMap()))
                .thenReturn(new RowView(id, Map.of(), Instant.now(), Instant.now()));

        String out =
                write.call(
                        "{\"action\":\"insert_row\",\"table\":\"orders\",\"values\":{\"email\":\"a@b.co\"}}");

        assertThat(out).contains("\"inserted\":1").contains(id.toString());
    }

    @Test
    void insertRowsIsCappedPerCall() {
        StringBuilder rows = new StringBuilder("[");
        for (int i = 0; i <= DataTableWriteToolCallback.MAX_INSERT_ROWS; i++) {
            rows.append(i == 0 ? "" : ",").append("{\"email\":\"x").append(i).append("\"}");
        }
        rows.append("]");

        String out =
                write.call(
                        "{\"action\":\"insert_rows\",\"table\":\"orders\",\"rows\":" + rows + "}");

        assertThat(out).contains("at most " + DataTableWriteToolCallback.MAX_INSERT_ROWS);
        verify(service, never()).insertRows(any(), anyList());
    }

    @Test
    void aModelCanNeverConfirmAWholeTableDeleteOrUpdate() {
        when(service.deleteRows(eq("orders"), anyList(), eq(false)))
                .thenThrow(
                        new IllegalArgumentException(
                                "Refusing to delete every row: give a filter"));
        when(service.updateRows(eq("orders"), anyList(), anyMap(), eq(false)))
                .thenThrow(
                        new IllegalArgumentException(
                                "Refusing to update every row: give a filter"));

        // even when the model smuggles a confirmAll flag into the arguments, it is never forwarded
        String del =
                write.call("{\"action\":\"delete_rows\",\"table\":\"orders\",\"confirmAll\":true}");
        String upd =
                write.call(
                        "{\"action\":\"update_rows\",\"table\":\"orders\",\"values\":{\"qty\":1},\"confirmAll\":true}");

        assertThat(del)
                .isEqualTo(
                        "Could not delete the rows: Refusing to delete every row: give a filter");
        assertThat(upd)
                .isEqualTo(
                        "Could not update the rows: Refusing to update every row: give a filter");
        verify(service, never()).deleteRows(any(), any(), eq(true));
        verify(service, never()).updateRows(any(), any(), any(), eq(true));
    }

    @Test
    void deleteAndUpdateForwardTheParsedFilter() {
        when(service.deleteRows(eq("orders"), anyList(), eq(false))).thenReturn(4);
        when(service.updateRows(eq("orders"), anyList(), anyMap(), eq(false))).thenReturn(2);

        assertThat(
                        write.call(
                                "{\"action\":\"delete_rows\",\"table\":\"orders\",\"filter\":{\"email\":\"a@b.co\"}}"))
                .isEqualTo("{\"deleted\":4}");
        assertThat(
                        write.call(
                                "{\"action\":\"update_rows\",\"table\":\"orders\",\"values\":{\"qty\":1},\"filter\":[{\"column\":\"email\",\"value\":\"a\"}]}"))
                .isEqualTo("{\"updated\":2}");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Condition>> filter = ArgumentCaptor.forClass(List.class);
        verify(service).deleteRows(eq("orders"), filter.capture(), eq(false));
        assertThat(filter.getValue()).containsExactly(new Condition("email", Op.EQ, "a@b.co"));
    }

    @Test
    void upsertReportsWhetherItCreatedOrUpdated() {
        when(service.upsertRow(eq("orders"), eq("email"), anyMap()))
                .thenReturn(new UpsertResult(true, 1, null));

        assertThat(
                        write.call(
                                "{\"action\":\"upsert_row\",\"table\":\"orders\",\"keyColumn\":\"email\",\"values\":{\"email\":\"a\"}}"))
                .isEqualTo("{\"created\":true,\"affected\":1}");
        assertThat(
                        write.call(
                                "{\"action\":\"upsert_row\",\"table\":\"orders\",\"values\":{\"email\":\"a\"}}"))
                .contains("keyColumn and values are required");
    }

    @Test
    void writeNeedsATableAndAKnownAction() {
        assertThat(write.call("{\"action\":\"insert_row\"}"))
                .contains("a table (name or id) is required");
        assertThat(write.call("{\"action\":\"truncate\",\"table\":\"orders\"}"))
                .startsWith("Could not write to the data table: action must be");
        assertThat(write.call("{\"action\":\"insert_row\",\"table\":\"orders\"}"))
                .contains("values");
        verify(service, never()).insertRow(any(), anyMap());
        verify(service, never()).deleteRows(any(), anyList(), anyBoolean());
    }
}
