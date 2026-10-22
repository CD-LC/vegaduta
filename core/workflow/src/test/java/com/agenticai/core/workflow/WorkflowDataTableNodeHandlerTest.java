package com.agenticai.core.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.tables.DataTableDtos.RowPage;
import com.agenticai.core.tables.DataTableDtos.RowQuery;
import com.agenticai.core.tables.DataTableDtos.RowView;
import com.agenticai.core.tables.DataTableDtos.UpsertResult;
import com.agenticai.core.tables.DataTableFilter.Condition;
import com.agenticai.core.tables.DataTableFilter.Op;
import com.agenticai.core.tables.DataTableService;
import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class WorkflowDataTableNodeHandlerTest {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    private DataTableService service;
    private WorkflowDataTableNodeHandler handler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = mock(DataTableService.class);
        when(service.isEnabled()).thenReturn(true);
        ObjectProvider<DataTableService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        handler = new WorkflowDataTableNodeHandler(provider, MAPPER);
    }

    private static WorkflowNodeDto node(Map<String, Object> config) {
        return new WorkflowNodeDto(
                "n1", "data_table", null, Map.of("label", "Orders table", "config", config));
    }

    private static Map<String, Object> config(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static JsonNode json(NodeExecutionResult r) throws Exception {
        return MAPPER.readTree(r.output());
    }

    // ---- flag-off identity

    @Test
    void withTheFlagOffTheNodeIsSkippedAndTheInputPassesThroughUntouched() {
        when(service.isEnabled()).thenReturn(false);

        NodeExecutionResult r =
                handler.executeResolved(
                        node(
                                config(
                                        "table",
                                        "orders",
                                        "operation",
                                        "delete_rows",
                                        "confirmAll",
                                        true)),
                        "{\"in\":1}");

        assertThat(r.status()).isEqualTo("SKIPPED");
        assertThat(r.output()).isEqualTo("{\"in\":1}");
        assertThat(r.summary()).contains("not enabled");
        verify(service).isEnabled();
        verifyNoMoreInteractions(service);
    }

    @Test
    @SuppressWarnings("unchecked")
    void withTheServiceAbsentTheNodeIsSkippedToo() {
        ObjectProvider<DataTableService> none = mock(ObjectProvider.class);
        when(none.getIfAvailable()).thenReturn(null);

        NodeExecutionResult r =
                new WorkflowDataTableNodeHandler(none, MAPPER)
                        .executeResolved(node(config("table", "orders")), "in");

        assertThat(r.status()).isEqualTo("SKIPPED");
        assertThat(r.output()).isEqualTo("in");
    }

    // ---- reads

    @Test
    void getRowsPassesFilterSortAndLimitAndFlattensTheRows() throws Exception {
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
                                40,
                                25,
                                0));

        NodeExecutionResult r =
                handler.executeResolved(
                        node(
                                config(
                                        "table",
                                        "orders",
                                        "operation",
                                        "get_rows",
                                        "filter",
                                        List.of(
                                                Map.of("column", "qty", "op", "gt", "value", "2"),
                                                Map.of(
                                                        "column", "", "op", "eq", "value",
                                                        "dropped")),
                                        "sortColumn",
                                        "qty",
                                        "sortDirection",
                                        "desc",
                                        "limit",
                                        "25")),
                        "ignored");

        ArgumentCaptor<RowQuery> q = ArgumentCaptor.forClass(RowQuery.class);
        verify(service).queryRows(eq("orders"), q.capture());
        assertThat(q.getValue().filter()).containsExactly(new Condition("qty", Op.GT, "2"));
        assertThat(q.getValue().sort().column()).isEqualTo("qty");
        assertThat(q.getValue().sort().descending()).isTrue();
        assertThat(q.getValue().limit()).isEqualTo(25);
        assertThat(r.status()).isEqualTo("OK");
        JsonNode out = json(r);
        assertThat(out.get("count").asInt()).isEqualTo(1);
        assertThat(out.get("total").asInt()).isEqualTo(40);
        assertThat(out.get("rows").get(0).get("_id").asString()).isEqualTo(id.toString());
        assertThat(out.get("rows").get(0).get("email").asString()).isEqualTo("a@b.co");
        assertThat(r.summary()).contains("1 of 40");
    }

    @Test
    void getRowsIsTheDefaultOperationAndTheLimitIsClamped() {
        when(service.queryRows(eq("orders"), any(RowQuery.class)))
                .thenReturn(new RowPage(List.of(), 0, 100, 0));

        handler.executeResolved(node(config("table", "orders", "limit", "999999")), "");
        handler.executeResolved(node(config("table", "orders", "limit", "junk")), "");
        handler.executeResolved(node(config("table", "orders")), "");

        ArgumentCaptor<RowQuery> q = ArgumentCaptor.forClass(RowQuery.class);
        verify(service, org.mockito.Mockito.times(3)).queryRows(eq("orders"), q.capture());
        assertThat(q.getAllValues()).extracting(RowQuery::limit).containsExactly(1000, 100, 100);
    }

    @Test
    void theFilterMayBeAJsonStringOrAnObject() {
        when(service.queryRows(eq("orders"), any(RowQuery.class)))
                .thenReturn(new RowPage(List.of(), 0, 100, 0));

        handler.executeResolved(
                node(config("table", "orders", "filter", "{\"status\":\"open\"}")), "");

        ArgumentCaptor<RowQuery> q = ArgumentCaptor.forClass(RowQuery.class);
        verify(service).queryRows(eq("orders"), q.capture());
        assertThat(q.getValue().filter()).containsExactly(new Condition("status", Op.EQ, "open"));
    }

    @Test
    void countReturnsTheNumber() throws Exception {
        when(service.count(eq("orders"), anyList())).thenReturn(12L);

        NodeExecutionResult r =
                handler.executeResolved(node(config("table", "orders", "operation", "count")), "");

        assertThat(json(r).get("count").asLong()).isEqualTo(12L);
    }

    // ---- writes

    @Test
    void insertRowUsesValuesJsonOtherwiseTheUpstreamObject() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.insertRow(eq("orders"), anyMap()))
                .thenReturn(new RowView(id, Map.of("email", "a"), Instant.now(), Instant.now()));

        NodeExecutionResult explicit =
                handler.executeResolved(
                        node(
                                config(
                                        "table",
                                        "orders",
                                        "operation",
                                        "insert_row",
                                        "valuesJson",
                                        "{\"email\":\"a\",\"qty\":\"3\"}")),
                        "ignored");
        handler.executeResolved(
                node(config("table", "orders", "operation", "insert_row")),
                "{\"email\":\"from-input\"}");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> values = ArgumentCaptor.forClass(Map.class);
        verify(service, org.mockito.Mockito.times(2)).insertRow(eq("orders"), values.capture());
        assertThat(values.getAllValues().get(0))
                .containsEntry("email", "a")
                .containsEntry("qty", "3");
        assertThat(values.getAllValues().get(1)).containsEntry("email", "from-input");
        assertThat(json(explicit).get("inserted").asInt()).isEqualTo(1);
        assertThat(json(explicit).get("row").get("_id").asString()).isEqualTo(id.toString());
    }

    @Test
    void insertWithNoValuesAnywhereFailsTheNode() {
        assertThatThrownBy(
                        () ->
                                handler.executeResolved(
                                        node(config("table", "orders", "operation", "insert_row")),
                                        "plain text"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valuesJson");
        assertThatThrownBy(
                        () ->
                                handler.executeResolved(
                                        node(
                                                config(
                                                        "table",
                                                        "orders",
                                                        "operation",
                                                        "insert_row",
                                                        "valuesJson",
                                                        "[1]")),
                                        ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON object");
        assertThatThrownBy(
                        () ->
                                handler.executeResolved(
                                        node(
                                                config(
                                                        "table",
                                                        "orders",
                                                        "operation",
                                                        "insert_row",
                                                        "valuesJson",
                                                        "{oops")),
                                        ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not valid JSON");
        verify(service, never()).insertRow(any(), anyMap());
    }

    @Test
    void updateRowsNeedsExplicitValuesAndForwardsConfirmAll() throws Exception {
        when(service.updateRows(eq("orders"), anyList(), anyMap(), eq(true))).thenReturn(9);

        assertThatThrownBy(
                        () ->
                                handler.executeResolved(
                                        node(config("table", "orders", "operation", "update_rows")),
                                        "{\"qty\":1}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valuesJson");

        NodeExecutionResult r =
                handler.executeResolved(
                        node(
                                config(
                                        "table",
                                        "orders",
                                        "operation",
                                        "update_rows",
                                        "valuesJson",
                                        "{\"qty\":1}",
                                        "confirmAll",
                                        "true")),
                        "");

        assertThat(json(r).get("updated").asInt()).isEqualTo(9);
    }

    @Test
    void deleteWithoutAFilterFailsTheNodeWithTheServicesReasonUnlessConfirmAll() throws Exception {
        when(service.deleteRows(eq("orders"), anyList(), eq(false)))
                .thenThrow(
                        new IllegalArgumentException(
                                "Refusing to delete every row: give a filter"));
        when(service.deleteRows(eq("orders"), anyList(), eq(true))).thenReturn(5);

        assertThatThrownBy(
                        () ->
                                handler.executeResolved(
                                        node(config("table", "orders", "operation", "delete_rows")),
                                        ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "Data table delete_rows failed: Refusing to delete every row: give a filter");

        NodeExecutionResult confirmed =
                handler.executeResolved(
                        node(
                                config(
                                        "table",
                                        "orders",
                                        "operation",
                                        "delete_rows",
                                        "confirmAll",
                                        true)),
                        "");
        assertThat(json(confirmed).get("deleted").asInt()).isEqualTo(5);
    }

    @Test
    void upsertNeedsAKeyColumnAndReportsCreatedOrUpdated() throws Exception {
        when(service.upsertRow(eq("orders"), eq("email"), anyMap()))
                .thenReturn(new UpsertResult(false, 2, null));

        assertThatThrownBy(
                        () ->
                                handler.executeResolved(
                                        node(
                                                config(
                                                        "table",
                                                        "orders",
                                                        "operation",
                                                        "upsert_row",
                                                        "valuesJson",
                                                        "{\"email\":\"a\"}")),
                                        ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyColumn");

        NodeExecutionResult r =
                handler.executeResolved(
                        node(
                                config(
                                        "table",
                                        "orders",
                                        "operation",
                                        "upsert_row",
                                        "keyColumn",
                                        "email",
                                        "valuesJson",
                                        "{\"email\":\"a\"}")),
                        "");

        assertThat(json(r).get("created").asBoolean()).isFalse();
        assertThat(json(r).get("affected").asInt()).isEqualTo(2);
        assertThat(json(r).get("row").isNull()).isTrue();
    }

    // ---- configuration errors

    @Test
    void aMissingTableOrAnUnknownOperationFailsTheNode() {
        assertThatThrownBy(() -> handler.executeResolved(node(config("operation", "get_rows")), ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no table configured");
        assertThatThrownBy(
                        () ->
                                handler.executeResolved(
                                        node(config("table", "orders", "operation", "truncate")),
                                        ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown operation 'truncate'");
    }

    @Test
    void aServiceRefusalSurfacesAsAnIllegalArgumentWithItsReason() {
        when(service.queryRows(eq("ghost"), any(RowQuery.class)))
                .thenThrow(
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Data table not found: ghost"));

        assertThatThrownBy(() -> handler.executeResolved(node(config("table", "ghost")), ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Data table get_rows failed: Data table not found: ghost");
    }

    @Test
    void operationNamesAreForgivingAboutCaseAndSeparators() {
        when(service.count(eq("orders"), anyList())).thenReturn(1L);

        NodeExecutionResult r =
                handler.executeResolved(
                        node(config("table", "orders", "operation", " COUNT ")), "");

        assertThat(r.status()).isEqualTo("OK");
    }
}
