package com.agenticai.core.agents.hooks;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One scope's hook configuration (migration V440): the workspace defaults ({@code scope_key = 'tenant'}) or one
 * agent's own list ({@code scope_key = <agent uuid>}). The hook array is stored as the validated JSON {@link
 * HookSchema} produces and is read and written whole. Tenant-schema table, so there is no tenant column.
 */
@Entity
@Table(name = "agent_hook_config")
public class AgentHookConfig {

    static final String TENANT_KEY = "tenant";

    @Id
    @Column(name = "scope_key", length = 64)
    private String scopeKey;

    /** Null for the tenant row. The FK cascades, so deleting an agent deletes its hooks. */
    @Column(name = "agent_id")
    private UUID agentId;

    @Column(name = "hooks_json", nullable = false, columnDefinition = "TEXT")
    private String hooksJson = "[]";

    /** Tenant row only: {@code {"webhookHosts":[...]}}. {@code {}} for agent rows. */
    @Column(name = "settings_json", nullable = false, columnDefinition = "TEXT")
    private String settingsJson = "{}";

    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected AgentHookConfig() {
    }

    static AgentHookConfig forTenant() {
        AgentHookConfig c = new AgentHookConfig();
        c.scopeKey = TENANT_KEY;
        return c;
    }

    static AgentHookConfig forAgent(UUID agentId) {
        AgentHookConfig c = new AgentHookConfig();
        c.scopeKey = agentId.toString();
        c.agentId = agentId;
        return c;
    }

    static String keyFor(UUID agentId) {
        return agentId.toString();
    }

    public String getScopeKey() {
        return scopeKey;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getHooksJson() {
        return hooksJson;
    }

    public String getSettingsJson() {
        return settingsJson;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    void update(String hooksJson, String settingsJson, String updatedBy) {
        this.hooksJson = hooksJson == null ? "[]" : hooksJson;
        if (settingsJson != null) {
            this.settingsJson = settingsJson;
        }
        this.updatedBy = updatedBy;
        this.updatedAt = Instant.now();
    }
}
