package com.agenticai.core.agents.tools.scheduling;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.ai.tool.ToolCallback;

/**
 * Port that builds the {@code continue_in_background} tool for one agent.
 *
 * <p>Agent jobs live in {@code core/agents-jobs} (ADR-0023), which depends on {@code core/agents};
 * the tool resolver here ({@code AgentBackgroundJobToolResolver}) must not depend back on it.
 * {@code BackgroundJobToolCallbackFactory} in {@code agents-jobs} implements this over {@code
 * AgentJobService}.
 */
public interface BackgroundJobToolFactory {

    /** The tool's name, the per-agent allow-list entry that opts an agent in. */
    String TOOL_NAME = "continue_in_background";

    /**
     * The tool bound to {@code agentId}, or {@code null} when agent jobs are not available in this
     * context (no {@code AgentJobService} bean).
     */
    ToolCallback createIfEnabled(UUID agentId);

    /**
     * As {@link #createIfEnabled(UUID)}, for a turn whose taint the caller can read: {@code taintSources}
     * returns what tainted the turn, or null when it is clean. A job started from a tainted turn keeps
     * that taint (a goal written after reading hostile content must not become a clean autonomous run).
     */
    default ToolCallback createIfEnabled(UUID agentId, Supplier<List<String>> taintSources) {
        return createIfEnabled(agentId);
    }
}
