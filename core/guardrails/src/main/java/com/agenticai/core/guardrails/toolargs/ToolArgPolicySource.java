package com.agenticai.core.guardrails.toolargs;

import java.util.UUID;

/**
 * Where {@link ToolArgPolicyCallback} gets an agent's argument policies and where it reports a refusal.
 *
 * <p>An interface (not a concrete class) because {@code core/guardrails} cannot see {@code core/agents},
 * which stores the policies per agent. The agents module supplies the implementation.
 *
 * <p>Implementations are called on the tool-execution thread with the turn's tenant context already restored,
 * so a tenant-schema read is safe. {@link #evaluatorFor} may be called once per tool call: cache.
 */
public interface ToolArgPolicySource {

    /** Master switch ({@code app.tool-arg-policies.enabled}). When false the wrapper is never installed. */
    boolean isEnabled();

    /**
     * The evaluator for this agent's policies, or {@code null} when the agent has none. A throwing
     * implementation is treated as "no policy" by the caller (fail-open on the LOOKUP; a policy that exists
     * but cannot be evaluated is fail-closed in the evaluator).
     */
    ToolArgPolicyEvaluator evaluatorFor(UUID agentId);

    /**
     * Called after a call was refused. For audit and metrics. {@code reason} contains no argument values.
     * Must not throw; the caller swallows anything that does.
     */
    default void onViolation(UUID agentId, String sessionId, String toolName, String reason) {
    }
}
