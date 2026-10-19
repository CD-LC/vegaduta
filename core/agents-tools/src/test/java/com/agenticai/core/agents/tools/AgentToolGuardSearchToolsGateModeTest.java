package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.agents.tools.search.CallToolToolCallback;
import com.agenticai.core.agents.tools.search.SearchToolsToolCallback;
import com.agenticai.core.guardrails.ApprovalGateMode;
import org.junit.jupiter.api.Test;

/**
 * search_tools and call_tool are dispatch plumbing and are never gated themselves (the deferred
 * target keeps its own gate), so a SUPERVISED agent is not parked on the dispatcher.
 */
class AgentToolGuardSearchToolsGateModeTest {

    private final AgentToolGuard guard =
            new AgentToolGuard(
                    null, null, null, null, null, 10, null, null, null, null, null, null);

    @Test
    void dispatcherToolsAreNeverGatedForAnyTier() {
        for (AgentAutonomyTier tier : AgentAutonomyTier.values()) {
            assertEquals(
                    ApprovalGateMode.NEVER_GATE,
                    guard.approvalGateModeFor(tier, SearchToolsToolCallback.NAME));
            assertEquals(
                    ApprovalGateMode.NEVER_GATE,
                    guard.approvalGateModeFor(tier, CallToolToolCallback.NAME));
        }
    }

    @Test
    void theTargetToolStillGetsItsTierGate() {
        assertEquals(
                ApprovalGateMode.ALWAYS_GATE,
                guard.approvalGateModeFor(AgentAutonomyTier.SUPERVISED, "send_email"));
        assertEquals(
                ApprovalGateMode.POLICY_DEFAULT,
                guard.approvalGateModeFor(AgentAutonomyTier.SEMI_AUTONOMOUS, "send_email"));
    }
}
