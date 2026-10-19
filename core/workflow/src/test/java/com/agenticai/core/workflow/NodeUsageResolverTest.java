package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.chat.LlmCallLog;
import com.agenticai.core.chat.LlmCallLogService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class NodeUsageResolverTest {

    private static final UUID AGENT = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-09-29T10:00:00Z");

    private LlmCallLogService callLog;
    private TenantLimits limits;
    private NodeUsageResolver resolver;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        callLog = mock(LlmCallLogService.class);
        limits = mock(TenantLimits.class);
        ObjectProvider<LlmCallLogService> callLogProvider = mock(ObjectProvider.class);
        ObjectProvider<TenantLimits> limitsProvider = mock(ObjectProvider.class);
        when(callLogProvider.getIfAvailable()).thenReturn(callLog);
        when(limitsProvider.getIfAvailable()).thenReturn(limits);
        resolver = new NodeUsageResolver(callLogProvider, limitsProvider, mock(ObjectProvider.class));
        TenantContext.set(TenantContext.of("acme", "tester", java.util.Set.of()));
        when(limits.plan("acme")).thenReturn(TenantPlan.PREMIUM);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static WorkflowNodeDto node(String type) {
        return new WorkflowNodeDto("n1", type, Map.of(), Map.of("config", Map.of("agentId", AGENT.toString())));
    }

    private static NodeTraceEntry entry() {
        return new NodeTraceEntry("n1", "agent", "A", "OK", "ok", T0, T0.plusSeconds(3), List.of());
    }

    private static LlmCallLog row(Long in, Long out, String cost) {
        LlmCallLog row = mock(LlmCallLog.class);
        when(row.getPromptTokens()).thenReturn(in);
        when(row.getCompletionTokens()).thenReturn(out);
        when(row.getEstimatedCostUsd()).thenReturn(cost == null ? null : new BigDecimal(cost));
        return row;
    }

    @Test
    void sumsEveryCallTheAgentMadeInsideTheNodeWindow() {
        // Rows built BEFORE the outer when(): stubbing inside a stubbing is UnfinishedStubbing.
        List<LlmCallLog> rows = List.of(row(100L, 20L, "0.0010"), row(50L, 5L, "0.0005"));
        when(callLog.forSessionAgentWindow("run-1", AGENT, T0, T0.plusSeconds(3))).thenReturn(rows);
        NodeTraceEntry e = resolver.attach(entry(), node("agent"), "run-1");
        assertEquals(150L, e.tokensIn());
        assertEquals(25L, e.tokensOut());
        assertEquals(new BigDecimal("0.0015"), e.costUsd());
    }

    @Test
    void noRowsMeansUnknownNotZero() {
        when(callLog.forSessionAgentWindow(anyString(), any(), any(), any())).thenReturn(List.of());
        NodeTraceEntry e = resolver.attach(entry(), node("supervisor"), "run-1");
        assertNull(e.tokensIn());
        assertNull(e.tokensOut());
        assertNull(e.costUsd());
    }

    @Test
    void unknownCostStaysNullWhileTokensAreKept() {
        List<LlmCallLog> rows = List.of(row(10L, 2L, null));
        when(callLog.forSessionAgentWindow(anyString(), any(), any(), any())).thenReturn(rows);
        NodeTraceEntry e = resolver.attach(entry(), node("agent"), "run-1");
        assertEquals(10L, e.tokensIn());
        assertNull(e.costUsd());
    }

    @Test
    void freePlanGetsNoUsageBecauseItIsAPremiumFeature() {
        when(limits.plan("acme")).thenReturn(TenantPlan.FREE);
        NodeTraceEntry e = resolver.attach(entry(), node("agent"), "run-1");
        assertNull(e.tokensIn());
        verify(callLog, never()).forSessionAgentWindow(anyString(), any(), any(), any());
    }

    @Test
    void nonLlmNodesAreNeverLookedUp() {
        NodeTraceEntry e = resolver.attach(entry(), node("http"), "run-1");
        assertNull(e.tokensIn());
        verify(callLog, never()).forSessionAgentWindow(anyString(), any(), any(), any());
    }

    @Test
    void malformedAgentIdIsUnknownNotAnError() {
        WorkflowNodeDto bad = new WorkflowNodeDto("n1", "agent", Map.of(), Map.of("config", Map.of("agentId", "nope")));
        assertNull(resolver.attach(entry(), bad, "run-1").tokensIn());
    }
}
