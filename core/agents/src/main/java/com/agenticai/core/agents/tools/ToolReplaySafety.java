package com.agenticai.core.agents.tools;

import java.util.Optional;

/**
 * Port (ADR-0023): whether a tool's classification says re-running it is safe. Used by the turn
 * path's verifier escalation; implemented by {@code AgentToolGovernance} in core/agents-tools. No
 * bean = the caller falls back to its tool-name rules.
 */
public interface ToolReplaySafety {

    /**
     * {@code true} when the tool is classified as safe to replay, {@code false} when it is
     * classified as unsafe, empty when it is not classified (or no registry is present).
     */
    Optional<Boolean> replaySafeVerdict(String toolName);
}
