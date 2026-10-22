package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.agenticai.core.agents.core.AgentNotFoundException;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Shared fixture of the {@link OrchestratorService} tests: a mocked agent service and approval
 * gate.
 */
abstract class OrchestratorServiceTestBase {

    AgentService agentService;
    HumanApprovalGateService approvalGateService;
    OrchestratorService orchestrator;

    static final UUID EXISTING_AGENT_ID = UUID.randomUUID();
    static final UUID MISSING_AGENT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        agentService = mock(AgentService.class);
        // validate() calls the unchecked requireExists (2026-08-04 fix - see
        // that method's javadoc), not the per-user-access-checked get(), so
        // that's what needs stubbing here to simulate a dangling agentId.
        doThrow(new AgentNotFoundException(MISSING_AGENT_ID))
                .when(agentService)
                .requireExists(MISSING_AGENT_ID);
        approvalGateService = mock(HumanApprovalGateService.class);
        orchestrator =
                new OrchestratorService(
                        agentService,
                        approvalGateService,
                        com.agenticai.core.json.JsonMappers.plain(),
                        mock(ObjectProvider.class));
    }

    static WorkflowNodeDto node(String id, String type, Map<String, Object> config) {
        return new WorkflowNodeDto(id, type, Map.of(), Map.of("config", config, "label", id));
    }

    static WorkflowEdgeDto edge(String id, String source, String target) {
        return new WorkflowEdgeDto(id, source, target, null, null);
    }

    /**
     * Per-node workflow triggers - every trigger node now needs its own 'channel' config; WEBHOOK
     * is the default here since it's the one channel allowed to repeat.
     */
    static WorkflowNodeDto trigger(String id) {
        return trigger(id, "WEBHOOK");
    }

    static WorkflowNodeDto trigger(String id, String channel) {
        return node(id, "trigger", Map.of("channel", channel));
    }

    List<String> errorsFrom(WorkflowGraphDto graph) {
        return assertThrows(WorkflowValidationException.class, () -> orchestrator.validate(graph))
                .getErrors();
    }
}
