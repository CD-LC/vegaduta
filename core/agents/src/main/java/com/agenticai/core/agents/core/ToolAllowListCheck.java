package com.agenticai.core.agents.core;

import com.agenticai.core.agents.tools.AgentToolPort;
import com.agenticai.core.agents.tools.ToolDescriptor;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The soft check {@code PATCH /api/agents/{id}/tool-access} runs before saving an allow-list: a
 * name the agent cannot currently resolve is almost always a typo that silently locks out the
 * intended tool, so it is logged loudly. Never a rejection - MCP tool names are discovered at
 * runtime, and a temporarily-down server must not make its tools unpersistable.
 */
final class ToolAllowListCheck {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private ToolAllowListCheck() {}

    /** Logs the requested names not currently resolvable for {@code agentId}; never throws. */
    static void warnOnUnknownNames(
            AgentToolPort agentToolProvider, UUID agentId, Set<String> allowedToolNames) {
        if (allowedToolNames == null || allowedToolNames.isEmpty()) {
            return;
        }
        try {
            Set<String> available = new HashSet<>();
            for (ToolDescriptor tool : agentToolProvider.listAvailableToolsFor(agentId)) {
                available.add(tool.name());
            }
            Set<String> unknown = new HashSet<>(allowedToolNames);
            unknown.removeAll(available);
            if (!unknown.isEmpty()) {
                log.warn(
                        "Agent {} tool allow-list contains {} name(s) not currently resolvable"
                                + " (typo or unavailable MCP server?): {}",
                        agentId,
                        unknown.size(),
                        unknown);
            }
        } catch (RuntimeException e) {
            // Tool discovery itself failing (e.g. an MCP probe error)
            // must not block the write either.
            log.warn(
                    "Could not resolve available tools for agent {} to sanity-check the"
                            + " allow-list: {}",
                    agentId,
                    e.toString());
        }
    }
}
