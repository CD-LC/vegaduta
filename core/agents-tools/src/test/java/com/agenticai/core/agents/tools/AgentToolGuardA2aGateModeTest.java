package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.guardrails.ApprovalGateMode;
import org.junit.jupiter.api.Test;

/** a2a_call_agent keeps its classification confirmation gate even for FULLY_AUTONOMOUS agents. */
class AgentToolGuardA2aGateModeTest {

    private final AgentToolGuard guard =
            new AgentToolGuard(
                    null, null, null, null, null, 10, null, null, null, null, null, null);

    @Test
    void fullyAutonomousStaysUngatedForOrdinaryTools() {
        assertEquals(
                ApprovalGateMode.NEVER_GATE,
                guard.approvalGateModeFor(AgentAutonomyTier.FULLY_AUTONOMOUS, "fetch_web_page"));
    }

    @Test
    void fullyAutonomousDegradesToPolicyDefaultForA2a() {
        assertEquals(
                ApprovalGateMode.POLICY_DEFAULT,
                guard.approvalGateModeFor(AgentAutonomyTier.FULLY_AUTONOMOUS, "a2a_call_agent"));
    }

    @Test
    void otherTiersAreUnchangedForA2a() {
        assertEquals(
                ApprovalGateMode.ALWAYS_GATE,
                guard.approvalGateModeFor(AgentAutonomyTier.SUPERVISED, "a2a_call_agent"));
        assertEquals(
                ApprovalGateMode.POLICY_DEFAULT,
                guard.approvalGateModeFor(AgentAutonomyTier.SEMI_AUTONOMOUS, "a2a_call_agent"));
    }
}
