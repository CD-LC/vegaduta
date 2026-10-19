package com.agenticai.core.mcpserver;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One tenant-admin-issued API key for the INBOUND MCP server surface, plus
 * the curated, opt-in set of this tenant's own Agents/Workflows/knowledge
 * collections it may be used to call. Mirrors {@code McpServerRegistration}'s
 * "entity stays a dumb persistence shape" posture (no crypto/hashing logic
 * on the entity itself - see {@link McpServerAccessGrantService} and
 * {@link McpApiKeyHasher} for that boundary) and {@code AgentToolProvider}'s
 * per-agent opt-in principle ("nothing is exposed by default, an admin has
 * to explicitly grant it") applied here at the whole-grant level instead of
 * per-tool.
 *
 * <p><b>Scope note (updated):</b> {@code exposedAgentIds} is enforced by
 * {@code McpCallAgentToolCallback}, {@code exposedWorkflowIds} is enforced by
 * {@code McpCallWorkflowToolCallback}, and {@code exposedKnowledgeCollectionIds}
 * is enforced by {@code McpSearchKnowledgeToolCallback} - all three fields are
 * now actually read and enforced by a tool, not just persisted/admin-settable
 * scaffolding. See each class's own javadoc and {@code McpServerToolConfig}'s.
 *
 * <p>No explicit {@code tenantId}/schema column - same as every other
 * tenant-owned entity in this schema (e.g. {@code WorkflowDefinition}):
 * isolation comes from which tenant's Postgres schema the row physically
 * lives in, resolved from {@link com.agenticai.core.security.TenantContext}
 * by Hibernate's multi-tenant connection provider, not from a column value.
 */
@Entity
@Table(name = "mcp_server_access_grant")
public class McpServerAccessGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "api_key_hash", nullable = false, unique = true, length = 64)
    private String apiKeyHash;

    @Column(name = "api_key_display_prefix", nullable = false, length = 16)
    private String apiKeyDisplayPrefix;

    @Column(nullable = false)
    private boolean enabled;

    /** JSON array of agent UUID strings - see class javadoc; empty array means nothing is exposed, never "everything". */
    @Column(name = "exposed_agent_ids", nullable = false, columnDefinition = "TEXT")
    private String exposedAgentIds;

    /** JSON array of workflow UUID strings - enforced by {@code McpCallWorkflowToolCallback} (class javadoc). */
    @Column(name = "exposed_workflow_ids", nullable = false, columnDefinition = "TEXT")
    private String exposedWorkflowIds;

    /** JSON array of knowledge-collection UUID strings - enforced by {@code McpSearchKnowledgeToolCallback} (class javadoc). */
    @Column(name = "exposed_knowledge_collection_ids", nullable = false, columnDefinition = "TEXT")
    private String exposedKnowledgeCollectionIds;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    protected McpServerAccessGrant() {
        // JPA
    }

    public McpServerAccessGrant(
        String name, String apiKeyHash, String apiKeyDisplayPrefix,
        String exposedAgentIdsJson, String exposedWorkflowIdsJson, String exposedKnowledgeCollectionIdsJson
    ) {
        this.name = name;
        this.apiKeyHash = apiKeyHash;
        this.apiKeyDisplayPrefix = apiKeyDisplayPrefix;
        this.enabled = true;
        this.exposedAgentIds = exposedAgentIdsJson;
        this.exposedWorkflowIds = exposedWorkflowIdsJson;
        this.exposedKnowledgeCollectionIds = exposedKnowledgeCollectionIdsJson;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        this.updatedAt = Instant.now();
    }

    public void rotateKey(String newApiKeyHash, String newApiKeyDisplayPrefix) {
        this.apiKeyHash = newApiKeyHash;
        this.apiKeyDisplayPrefix = newApiKeyDisplayPrefix;
        this.updatedAt = Instant.now();
    }

    public void updateExposedIds(String exposedAgentIdsJson, String exposedWorkflowIdsJson, String exposedKnowledgeCollectionIdsJson) {
        this.exposedAgentIds = exposedAgentIdsJson;
        this.exposedWorkflowIds = exposedWorkflowIdsJson;
        this.exposedKnowledgeCollectionIds = exposedKnowledgeCollectionIdsJson;
        this.updatedAt = Instant.now();
    }

    public void recordUsage() {
        this.lastUsedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getApiKeyHash() {
        return apiKeyHash;
    }

    public String getApiKeyDisplayPrefix() {
        return apiKeyDisplayPrefix;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getExposedAgentIds() {
        return exposedAgentIds;
    }

    public String getExposedWorkflowIds() {
        return exposedWorkflowIds;
    }

    public String getExposedKnowledgeCollectionIds() {
        return exposedKnowledgeCollectionIds;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }
}
