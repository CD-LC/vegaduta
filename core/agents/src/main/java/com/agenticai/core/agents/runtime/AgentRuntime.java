package com.agenticai.core.agents.runtime;

import java.util.UUID;

/**
 * What the paths that ENFORCE a runtime limit (the tool guard, the turn, agent jobs) ask for: the settings a
 * turn of an agent runs under. Implemented by {@code AgentRuntimeSettingsResolver}, the one place the
 * platform, tenant, agent and request layers are applied; this interface is all the enforcing code depends
 * on, so it never depends on the resolver's own package (which reads the agent store and the tenant store).
 */
public interface AgentRuntime {

    /**
     * The settings a turn of {@code agentId} runs under, with the request-layer {@code caps} of one run
     * (null = none), which can only tighten what the other layers allow.
     */
    AgentRuntimeSettings forAgent(UUID agentId, RuntimeLimits caps);

    /** The settings a turn of {@code agentId} runs under. */
    default AgentRuntimeSettings forAgent(UUID agentId) {
        return forAgent(agentId, null);
    }

    /**
     * The tool loop's per-invocation round cap for a turn with {@code settings}: a backstop that follows the
     * turn's call ceiling and is never below the platform's.
     */
    int toolLoopRounds(AgentRuntimeSettings settings);

    /** What the model is told when a tool call outlives the tool timeout and is stopped. */
    String toolTimeoutMessage();
}
