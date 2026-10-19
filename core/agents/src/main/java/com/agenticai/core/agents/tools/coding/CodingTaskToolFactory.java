package com.agenticai.core.agents.tools.coding;

import java.util.UUID;
import org.springframework.ai.tool.ToolCallback;

/**
 * Port that builds the {@code coding_task} tool for one agent.
 *
 * <p>The coding harness lives in {@code core/agents-studio} (ADR-0023), which depends on {@code
 * core/agents}; the tool resolver here ({@code AgentCodingTaskToolResolver}) must not depend back
 * on it. {@code CodingTaskToolCallbackFactory} in {@code agents-studio} implements this over {@code
 * CodingTaskService}.
 */
public interface CodingTaskToolFactory {

    /** The tool's name, the per-agent allow-list entry that opts an agent in. */
    String TOOL_NAME = "coding_task";

    /**
     * The tool bound to {@code agentId}, or {@code null} when the coding harness is switched off
     * ({@code app.coding-harness.enabled}) or its service is not available in this context.
     */
    ToolCallback createIfEnabled(UUID agentId);
}
