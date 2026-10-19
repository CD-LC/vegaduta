package com.agenticai.core.agents.tools.web;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Fail-closed stand-in for deep_research / verify_citations on resolutions that cannot hold a
 * turn's research evidence: edge tool execution and approval replay both resolve a FRESH tool set
 * to call one tool by name, so the sources fetched earlier in the turn are gone. Same name and
 * schema as the real tool (so lookup-by-name finds it), but every call returns a clear error
 * instead of running against an empty session or spending search quota.
 */
public final class ResearchUnavailableToolCallback implements ToolCallback {

    static final String MESSAGE =
            "{\"error\": \"%s is not available on this execution path (edge session, approval replay or workflow "
                    + "replay): research evidence only lives inside one chat turn. Re-run the research in a normal chat "
                    + "turn instead.\"}";

    private final ToolCallback shape;

    public ResearchUnavailableToolCallback(ToolCallback shape) {
        this.shape = shape;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return shape.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        return String.format(MESSAGE, shape.getToolDefinition().name());
    }
}
