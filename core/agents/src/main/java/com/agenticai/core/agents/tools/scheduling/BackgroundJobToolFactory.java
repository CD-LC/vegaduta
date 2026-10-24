package com.agenticai.core.agents.tools.scheduling;

import java.util.UUID;
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
}
