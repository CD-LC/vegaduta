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
    /** No tool call is ever gated for this agent, regardless of the tenant's per-action-type policy. */
    NEVER_GATE
}
