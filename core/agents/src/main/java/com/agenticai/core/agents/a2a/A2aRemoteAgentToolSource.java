package com.agenticai.core.agents.a2a;

import org.springframework.ai.tool.ToolCallback;

/**
 * Port (ADR-0023): how the tool resolver in {@code core/agents} offers the {@code a2a_call_agent}
 * built-in without depending on the outbound A2A client, which lives in {@code
 * core/agents-interop} ({@code A2aClientService} implements this). An absent bean means the
 * feature is off, exactly as an absent client bean did before the split.
 */
public interface A2aRemoteAgentToolSource {

    /** The built-in tool's name; agents opt in by naming it in their tool allow-list. */
    String TOOL_NAME = "a2a_call_agent";

    /** {@code app.a2a.client.enabled}. */
    boolean isEnabled();

    /** At least one operator allow-listed host; without one every call would be refused. */
    boolean hasAllowedHosts();

    /**
     * A fresh {@code a2a_call_agent} callback. One instance per resolved tool set, because the
     * callback counts its calls against the per-turn cap.
     */
    ToolCallback newCallAgentTool();
}
