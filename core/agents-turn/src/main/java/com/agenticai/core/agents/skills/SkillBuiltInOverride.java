package com.agenticai.core.agents.skills;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A tenant switching a built-in skill off (or back on). No row = enabled. */
@com.agenticai.core.security.TenantConfigEntity
@Entity
@Table(name = "skill_builtin_override")
public class SkillBuiltInOverride {

    @Id
    @Column(length = 64)
    private String name;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SkillBuiltInOverride() {}

    public SkillBuiltInOverride(String name, boolean enabled) {
        this.name = name;
        this.enabled = enabled;
        this.updatedAt = Instant.now();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        this.updatedAt = Instant.now();
    }

    public String getName() {
        return name;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
