package com.agenticai.core.guardrails;

/**
 * #815 Governance follow-up - the resolved approval-gating signal {@link
 * GuardedToolCallback} actually enforces. Deliberately NOT the same type as
 * {@code com.agenticai.core.agents.core.AgentAutonomyTier} (which lives in
 * core/agents, a module that already depends on core/guardrails - giving
 * this module its own reverse dependency back on core/agents would create a
 * Maven cycle) - {@code AgentToolProvider} (part of the core/agents module,
 * so it can see both types) translates an agent's autonomy tier into one of
 * these three values when it builds a {@link GuardedToolCallback}. See
 * {@code AgentAutonomyTier}'s javadoc for the tier semantics this maps from.
 */
public enum ApprovalGateMode {
    /** {@code ApprovalPolicyService.requiresApproval(actionType)} decides, same as before this column existed. */
    POLICY_DEFAULT,
    /** Every tool call is gated, regardless of the tenant's per-action-type policy. */
    ALWAYS_GATE,
    /**
     * As {@link #ALWAYS_GATE}, except a call whose tool the dispatch gate vouches for as an explicitly classified
     * read-only tool ({@link ToolDispatchGate.Verdict#readOnly()}): that one needs no human. Every other check still
     * runs on it exactly as for any other tier. Chosen by {@code AgentToolGuard} for a SUPERVISED agent only when
     * {@code app.guardrails.supervised.gate-read-only} is false for it and it does not hold the lethal trifecta;
     * never produced by any other path, so a missing or unclassified tool falls through to the gate.
     */
    GATE_UNLESS_READ_ONLY,
    /** No tool call is ever gated for this agent, regardless of the tenant's per-action-type policy. */
    NEVER_GATE
}
