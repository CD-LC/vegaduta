package com.agenticai.core.agentspi;

import java.util.List;
import org.springframework.ai.tool.ToolCallback;

/**
 * Tools a connected workspace account (Google Workspace today) contributes to every agent of the
 * tenant. Kernel-owned (ADR-0011 rule a): {@code tools.AgentIntegrationToolResolver} asks each
 * source for its tools and wraps every one in the tool guard, and never imports a vendor. The
 * Google implementation is {@code com.agenticai.core.google.agenttools.GoogleWorkspaceToolSource}.
 */
public interface WorkspaceToolSource {

    /**
     * Unguarded tool callbacks for the current tenant's connected account; empty when nothing is
     * connected. Cheap to call once per turn; never throws for a missing connection.
     */
    List<ToolCallback> rawTools();
}
