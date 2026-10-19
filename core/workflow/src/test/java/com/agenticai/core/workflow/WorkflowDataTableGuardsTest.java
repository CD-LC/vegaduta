package com.agenticai.core.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.tables.DataTableNodeOps;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.guidance.WorkflowSendApprovalGuard;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The data_table node's WRITE operations are side effects that a dry run must simulate and a step test must refuse,
 * while its reads (get_rows, count) run for real in both. An operation nobody can classify counts as a write.
 */
class WorkflowDataTableGuardsTest {

    private static WorkflowNodeDto node(Map<String, Object> config) {
        return new WorkflowNodeDto("n1", "data_table", null, Map.of("label", "Table", "config", config));
    }

    @Test
    void writeOperationsAreSimulatableSendsAndReadsAreNot() {
        for (String write : new String[] {"insert_row", "update_rows", "upsert_row", "delete_rows", "INSERT_ROW", " delete-rows "}) {
            assertThat(WorkflowSendApprovalGuard.isSimulatableSend(node(Map.of("operation", write)))).as(write).isTrue();
        }
        for (String read : new String[] {"get_rows", "count", "COUNT"}) {
            assertThat(WorkflowSendApprovalGuard.isSimulatableSend(node(Map.of("operation", read)))).as(read).isFalse();
        }
        // no operation configured = get_rows, the read-only default
        assertThat(WorkflowSendApprovalGuard.isSimulatableSend(node(Map.of()))).isFalse();
        // an operation nobody can classify must never be treated as a harmless read
        assertThat(WorkflowSendApprovalGuard.isSimulatableSend(node(Map.of("operation", "truncate")))).isTrue();
    }

    @Test
    void theStepTestRefusesWritesButAllowsReads() {
        assertThat(WorkflowNodeTestService.refusalReason(node(Map.of("operation", "delete_rows")))).contains("writes to a table");
        assertThat(WorkflowNodeTestService.refusalReason(node(Map.of("operation", "insert_row")))).isNotNull();
        assertThat(WorkflowNodeTestService.refusalReason(node(Map.of("operation", "get_rows")))).isNull();
        assertThat(WorkflowNodeTestService.refusalReason(node(Map.of("operation", "count")))).isNull();
        assertThat(WorkflowNodeTestService.refusalReason(node(Map.of()))).isNull();
    }

    @Test
    void aDryRunStandsInForAWriteAndChangesNothing() {
        WorkflowNodeExecutor.NodeExecutionResult r = WorkflowDryRun.simulate(
            node(Map.of("table", "orders", "operation", "delete_rows", "valuesJson", "")), "upstream");

        assertThat(r.status()).isEqualTo("OK");
        assertThat(r.output()).isEqualTo("upstream");
        assertThat(r.summary()).startsWith(WorkflowDryRun.SUMMARY_PREFIX).contains("would change the data table orders (delete_rows)");
    }

    @Test
    void theOperationVocabularyIsStable() {
        assertThat(DataTableNodeOps.normalize(null)).isEqualTo("get_rows");
        assertThat(DataTableNodeOps.normalize("  Upsert-Row ")).isEqualTo("upsert_row");
        assertThat(DataTableNodeOps.isKnown("count")).isTrue();
        assertThat(DataTableNodeOps.isKnown("drop")).isFalse();
    }

    @Test
    void theNodeTypeIsRegisteredWithItsExpressionFields() {
        assertThat(WorkflowNodeType.fromWire("data_table")).isEqualTo(WorkflowNodeType.DATA_TABLE);
        assertThat(WorkflowNodeType.DATA_TABLE.isSupported()).isTrue();
        assertThat(ExpressionFields.paths("data_table"))
            .containsExactly("table", "keyColumn", "valuesJson", "filter[].value", "sortColumn", "limit");
    }
}
