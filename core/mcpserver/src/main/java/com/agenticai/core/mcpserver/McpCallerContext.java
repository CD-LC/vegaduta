package com.agenticai.core.mcpserver;

import java.util.Set;
import java.util.UUID;

/**
 * Per-request holder of WHICH grant (and therefore which curated capability
 * set) authenticated the current inbound MCP call - the {@code
 * TenantContext} equivalent for this module. {@link McpApiKeyAuthFilter}
 * sets it once the API key has resolved to a real, enabled
 * {@link McpServerAccessGrant}; {@code McpCallAgentToolCallback}/{@code
 * McpListAgentsToolCallback} read it to know which agent ids this specific
 * caller is allowed to touch, workflow-facing tool callbacks (see this
 * module's {@code tools} package) read {@link #exposedWorkflowIds()} the
 * same way, and {@code McpSearchKnowledgeToolCallback} reads
 * {@link #exposedKnowledgeCollectionIds()} to know which Knowledge
 * Collection ids this caller is allowed to search.
 *
 * <p>Deliberately separate from {@code TenantContext} rather than piggy-
 * backing extra fields onto it: {@code TenantContext} is a foundational
 * {@code core/security} type every module depends on, and "which MCP grant"
 * is meaningless outside this one module - adding it there would be exactly
 * the kind of upward, feature-specific dependency this codebase's module
 * boundaries are designed to avoid.
 *
 * <p>{@code exposedKnowledgeCollectionIds} follows this module's own
 * deny-by-default posture, NOT {@code core/knowledge}'s internal per-agent
 * convention: an empty set here means NO collections are searchable by this
 * caller, never "search everything" - see {@code McpSearchKnowledgeToolCallback}'s
 * javadoc for why those two conventions are deliberately inverted.
 */
public final class McpCallerContext {

    private static final ThreadLocal<McpCallerContext> CURRENT = new ThreadLocal<>();

    private final UUID grantId;
    private final Set<UUID> exposedAgentIds;
    private final Set<UUID> exposedWorkflowIds;
    private final Set<UUID> exposedKnowledgeCollectionIds;

    private McpCallerContext(
        UUID grantId, Set<UUID> exposedAgentIds, Set<UUID> exposedWorkflowIds, Set<UUID> exposedKnowledgeCollectionIds
    ) {
        this.grantId = grantId;
        this.exposedAgentIds = exposedAgentIds;
        this.exposedWorkflowIds = exposedWorkflowIds;
        this.exposedKnowledgeCollectionIds = exposedKnowledgeCollectionIds;
    }

    static void set(
        UUID grantId, Set<UUID> exposedAgentIds, Set<UUID> exposedWorkflowIds, Set<UUID> exposedKnowledgeCollectionIds
    ) {
        CURRENT.set(new McpCallerContext(grantId, exposedAgentIds, exposedWorkflowIds, exposedKnowledgeCollectionIds));
    }

    public static java.util.Optional<McpCallerContext> current() {
        return java.util.Optional.ofNullable(CURRENT.get());
    }

    static void clear() {
        CURRENT.remove();
    }

    public UUID grantId() {
        return grantId;
    }

    public boolean isAgentExposed(UUID agentId) {
        return agentId != null && exposedAgentIds.contains(agentId);
    }

    public Set<UUID> exposedAgentIds() {
        return exposedAgentIds;
    }

    public boolean isWorkflowExposed(UUID workflowId) {
        return workflowId != null && exposedWorkflowIds.contains(workflowId);
    }

    public Set<UUID> exposedWorkflowIds() {
        return exposedWorkflowIds;
    }

    public Set<UUID> exposedKnowledgeCollectionIds() {
        return exposedKnowledgeCollectionIds;
    }
}
