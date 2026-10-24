package com.agenticai.core.guardrails;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.approval.ApprovalPolicyService;
import com.agenticai.core.approval.ApprovalReplayGrant;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.HumanApprovalGateService;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * The confirmation card's summary line: a tool that implements {@link ToolConfirmationDescriber} supplies the text
 * for its own gated call; anything else (no describer, a blank answer, an exception) keeps the historic
 * {@code "Tool call: <name>"} - describing a call must never stop it being recorded.
 */
class GuardedToolCallbackConfirmationSummaryTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final String TOOL_NAME = "schedule_task";
    private static final String ACTION_TYPE = "mcp_tool." + TOOL_NAME;
    private static final String INPUT = "{\"name\":\"digest\"}";

    private ApprovalPolicyService approvalPolicyService;
    private HumanApprovalGateService approvalGateService;
    private GuardrailService guardrailService;

    /** A tool whose describer is whatever the test hands it. */
    private static final class DescribingTool implements ToolCallback, ToolConfirmationDescriber {
        private final Function<String, String> describer;
        int executions;

        DescribingTool(Function<String, String> describer) {
            this.describer = describer;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name(TOOL_NAME).description("d").inputSchema("{\"type\":\"object\"}").build();
        }

        @Override
        public String call(String toolInput) {
            executions++;
            return "created";
        }

        @Override
        public String describeForConfirmation(String toolInput) {
            return describer.apply(toolInput);
        }
    }

    @BeforeEach
    void setUp() {
        guardrailService = mock(GuardrailService.class);
        when(guardrailService.sanitizeToolOutput(anyString(), anyString(), any()))
            .thenAnswer(invocation -> invocation.getArgument(1));
        approvalPolicyService = mock(ApprovalPolicyService.class);
        when(approvalPolicyService.requiresApproval(ACTION_TYPE)).thenReturn(true);
        approvalGateService = mock(HumanApprovalGateService.class);
        ApprovalRequest pending = mock(ApprovalRequest.class);
        when(pending.getId()).thenReturn(UUID.randomUUID());
        when(approvalGateService.recordToolCallForApproval(
            anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any(), any()))
            .thenReturn(pending);
    }

    @AfterEach
    void cleanUp() {
        ApprovalReplayGrant.clear();
    }

    private String callAndCaptureSummary(ToolCallback delegate) {
        GuardedToolCallback guarded = new GuardedToolCallback(
            delegate, new AtomicInteger(0), 10, guardrailService, approvalPolicyService, approvalGateService,
            AGENT_ID, "s1");
        String result = guarded.call(INPUT);
        assertTrue(result.contains("NOT executed"));
        return result;
    }

    private void verifySummary(String expected) {
        verify(approvalGateService).recordToolCallForApproval(
            eq(ACTION_TYPE), eq(TOOL_NAME), eq(expected), eq(INPUT), anyString(), eq(AGENT_ID), eq("s1"), any(), any());
    }

    @Test
    void aDescribingToolSuppliesTheCardTextAndStillNeverExecutes() {
        DescribingTool tool = new DescribingTool(in -> "Schedule a recurring task \"digest\".\nWhen: Every day at 08:00 (UTC).");

        callAndCaptureSummary(tool);

        verifySummary("Schedule a recurring task \"digest\".\nWhen: Every day at 08:00 (UTC).");
        assertTrue(tool.executions == 0, "a gated call must not execute");
    }

    @Test
    void theDescriberReceivesTheExactArguments() {
        DescribingTool tool = new DescribingTool(in -> "args=" + in);

        callAndCaptureSummary(tool);

        verifySummary("args=" + INPUT);
    }

    @Test
    void aBlankOrNullDescriptionKeepsTheDefaultSummary() {
        callAndCaptureSummary(new DescribingTool(in -> "   "));
        verifySummary("Tool call: " + TOOL_NAME);
    }

    @Test
    void aDescriberThatThrowsNeverStopsTheCallBeingRecorded() {
        DescribingTool tool = new DescribingTool(in -> {
            throw new IllegalStateException("boom");
        });

        callAndCaptureSummary(tool);

        verifySummary("Tool call: " + TOOL_NAME);
    }

    @Test
    void aToolWithoutADescriberKeepsTheHistoricSummary() {
        ToolCallback plain = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(TOOL_NAME);
        when(plain.getToolDefinition()).thenReturn(definition);

        callAndCaptureSummary(plain);

        verifySummary("Tool call: " + TOOL_NAME);
    }
}
