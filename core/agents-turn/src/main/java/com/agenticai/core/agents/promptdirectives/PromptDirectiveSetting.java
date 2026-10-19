package com.agenticai.core.agents.promptdirectives;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** One tenant's change to a built-in prompt directive (migration V654). */
@Entity
@Table(name = "prompt_directive_setting")
public class PromptDirectiveSetting {

    @Id
    @Column(name = "directive_key", nullable = false, length = 64)
    private String directiveKey;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "text_override")
    private String textOverride;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    protected PromptDirectiveSetting() {}

    PromptDirectiveSetting(
            String directiveKey, boolean enabled, String textOverride, String updatedBy) {
        this.directiveKey = directiveKey;
        apply(enabled, textOverride, updatedBy);
    }

    void apply(boolean enabled, String textOverride, String updatedBy) {
        this.enabled = enabled;
        this.textOverride = textOverride;
        this.updatedBy = updatedBy;
        this.updatedAt = Instant.now();
    }

    public String getDirectiveKey() {
        return directiveKey;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getTextOverride() {
        return textOverride;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }
}
