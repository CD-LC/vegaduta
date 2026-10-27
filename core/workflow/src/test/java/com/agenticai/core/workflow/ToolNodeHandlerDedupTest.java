package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.AgentToolProvider;
import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.output.WorkflowOutputDedupGuard;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * A {@code tool} node's side effect is whatever the resolved tool does, so workflow execution's
 * at-least-once posture (node retry, Temporal activity retry, recovered run) must not reach it
 * twice: the handler claims (runId, nodeId, hash of the resolved call input) through the same {@link
 * WorkflowOutputDedupGuard} the output destinations use, suppresses a replayed claim, and releases
 * the claim when the call never completed so a legitimate retry can still go through.
 */
class ToolNodeHandlerDedupTest {

    private static final UUID AGENT = UUID.randomUUID();
    private static final String RUN = UUID.randomUUID().toString();

    private final AgentToolProvider toolProvider = mock(AgentToolProvider.class);
    private final WorkflowOutputDedupGuard dedupGuard = mock(WorkflowOutputDedupGuard.class);
    private final ToolNodeHandler handler = new ToolNodeHandler(toolProvider, dedupGuard);

    @Test
    void aToolCallIsClaimedBeforeItRunsAndItsResultReturned() {
        ToolCallback cb = tool("send_alert", "delivered");
        when(toolProvider.resolveToolCandidatesFor(AGENT, RUN, RUN)).thenReturn(List.of(cb));
        when(dedupGuard.identityKey(eq("tool"), eq(RUN), eq("n1"), anyString())).thenReturn("k1");
        when(dedupGuard.tryClaim("k1")).thenReturn(true);

        NodeExecutionResult r = handler.handle(inv(node(), "in", null));

        assertEquals("OK", r.status());
        assertEquals("delivered", r.output());
        verify(cb).call("in");
        verify(dedupGuard, never()).release(anyString());
    }

    @Test
    void anAlreadyClaimedCallIsSuppressedInsteadOfReInvokingTheTool() {
        ToolCallback cb = tool("send_alert", "delivered");
        when(toolProvider.resolveToolCandidatesFor(AGENT, RUN, RUN)).thenReturn(List.of(cb));
        when(dedupGuard.identityKey(eq("tool"), eq(RUN), eq("n1"), anyString())).thenReturn("k1");
        when(dedupGuard.tryClaim("k1")).thenReturn(false);

        NodeExecutionResult r = handler.handle(inv(node(), "in", null));

        assertEquals("OK", r.status());
        assertTrue(r.output().contains("\"deduplicated\":true"));
        verify(cb, never()).call(anyString());
    }

    @Test
    void aFailedCallReleasesItsClaimSoALegitimateRetryCanReachTheTool() {
        ToolCallback cb = tool("send_alert", "delivered");
        when(cb.call(anyString())).thenThrow(new RuntimeException("channel down"));
        when(toolProvider.resolveToolCandidatesFor(AGENT, RUN, RUN)).thenReturn(List.of(cb));
        when(dedupGuard.identityKey(eq("tool"), eq(RUN), eq("n1"), anyString())).thenReturn("k1");
        when(dedupGuard.tryClaim("k1")).thenReturn(true);

        NodeExecutionResult r = handler.handle(inv(node(), "in", null));

        assertEquals("SKIPPED", r.status());
        verify(dedupGuard).release("k1");
    }

    @Test
    void loopIterationsClaimDifferentKeysSoDistinctItemsAreNotSuppressed() {
        ToolCallback cb = tool("send_alert", "ok");
        when(toolProvider.resolveToolCandidatesFor(AGENT, RUN, RUN)).thenReturn(List.of(cb));
        when(dedupGuard.identityKey(eq("tool"), eq(RUN), eq("n1"), anyString()))
                .thenAnswer(inv -> "k:" + inv.getArgument(3));
        when(dedupGuard.tryClaim(anyString())).thenReturn(true);

        List<String> items = List.of("a", "b");
        handler.handle(inv(node(), "in", ExpressionScope.iteration("a", 0, items)));
        handler.handle(inv(node(), "in", ExpressionScope.iteration("b", 1, items)));

        verify(dedupGuard).identityKey("tool", RUN, "n1", "0|in");
        verify(dedupGuard).identityKey("tool", RUN, "n1", "1|in");
        verify(dedupGuard).tryClaim("k:0|in");
        verify(dedupGuard).tryClaim("k:1|in");
    }

    private ToolCallback tool(String name, String reply) {
        ToolCallback cb = mock(ToolCallback.class);
        ToolDefinition def = mock(ToolDefinition.class);
        when(def.name()).thenReturn(name);
        when(cb.getToolDefinition()).thenReturn(def);
        when(cb.call(anyString())).thenReturn(reply);
        return cb;
    }

    private WorkflowNodeDto node() {
        return new WorkflowNodeDto(
                "n1",
                "tool",
                Map.of(),
                Map.of("config", Map.of("agentId", AGENT.toString(), "toolName", "send_alert")));
    }

    private NodeInvocation inv(WorkflowNodeDto node, String input, ExpressionScope scope) {
        return new NodeInvocation(
                node,
                node,
                input,
                "wf",
                "wf",
                RUN,
                "tester",
                null,
                List.of(),
                null,
                null,
                scope,
                false);
    }
}
