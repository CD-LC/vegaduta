package com.agenticai.core.agents.skills;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Per-agent skill scope. A missing row means the defaults (skills on, every enabled skill
 * available), so existing agents get skills with no backfill. {@code allowedSkillNames} has the
 * same "opt-in allow-list, empty means unrestricted" shape as {@code Agent#allowedToolNames}.
 */
@com.agenticai.core.security.TenantConfigEntity
@Entity
@Table(name = "agent_skill_config")
public class AgentSkillConfig {

    @Id
    @Column(name = "agent_id")
    private UUID agentId;

    @Column(name = "skills_enabled", nullable = false)
    private boolean skillsEnabled;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_allowed_skill", joinColumns = @JoinColumn(name = "agent_id"))
    @Column(name = "skill_name")
    private Set<String> allowedSkillNames = new HashSet<>();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AgentSkillConfig() {}

    public AgentSkillConfig(UUID agentId) {
        this.agentId = agentId;
        this.skillsEnabled = true;
        this.updatedAt = Instant.now();
    }

    public void update(boolean skillsEnabled, Set<String> allowedSkillNames) {
        this.skillsEnabled = skillsEnabled;
        this.allowedSkillNames =
                allowedSkillNames == null ? new HashSet<>() : new HashSet<>(allowedSkillNames);
        this.updatedAt = Instant.now();
    }

    public UUID getAgentId() {
        return agentId;
    }

    public boolean isSkillsEnabled() {
        return skillsEnabled;
    }

    public Set<String> getAllowedSkillNames() {
        return Collections.unmodifiableSet(allowedSkillNames);
    }
}
