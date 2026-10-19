package com.agenticai.core.mcpserver;

import java.util.Set;
import java.util.UUID;

/**
 * Test-only bridge over {@link McpCallerContext}'s package-private {@code
 * set}/{@code clear} - in production the ONLY writer is {@link
 * McpApiKeyAuthFilter}, and keeping the setters package-private is exactly
 * what enforces that (no tool callback can quietly widen its own grant).
 * Tests for the {@code tools} subpackage live in their own package and
 * therefore need this seam to arrange a caller context at all; it lives in
 * test sources only, so the production visibility guarantee is untouched.
 */
public final class McpCallerContextTestAccess {

    private McpCallerContextTestAccess() {
        // utility class
    }

    public static void set(
        UUID grantId, Set<UUID> exposedAgentIds, Set<UUID> exposedWorkflowIds, Set<UUID> exposedKnowledgeCollectionIds
    ) {
        McpCallerContext.set(grantId, exposedAgentIds, exposedWorkflowIds, exposedKnowledgeCollectionIds);
    }

    public static void clear() {
        McpCallerContext.clear();
    }
}
