package com.agenticai.core.agents.tools.browseruse;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code agent_browser_profile} (V443): the browser profile an agent runs with (the
 * "agent setting {@code browserProfileId}"). Kept in its own table rather than a column on {@code
 * agent} so the agent entity, its snapshot/version codec and its DTOs stay untouched by this
 * feature.
 */
@Entity
@Table(name = "agent_browser_profile")
public class BrowserProfileAgentLink {

    @Id
    @Column(name = "agent_id")
    private UUID agentId;

    @Column(name = "profile_id", nullable = false)
    private UUID profileId;

    @Column(name = "attached_by", length = 255)
    private String attachedBy;

    @Column(name = "attached_at", nullable = false)
    private Instant attachedAt;

    protected BrowserProfileAgentLink() {
        // JPA
    }

    BrowserProfileAgentLink(UUID agentId, UUID profileId, String attachedBy, Instant now) {
        this.agentId = agentId;
        this.profileId = profileId;
        this.attachedBy = attachedBy;
        this.attachedAt = now;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public UUID getProfileId() {
        return profileId;
    }

    public String getAttachedBy() {
        return attachedBy;
    }

    public Instant getAttachedAt() {
        return attachedAt;
    }

    void repoint(UUID newProfileId, String by, Instant now) {
        this.profileId = newProfileId;
        this.attachedBy = by;
        this.attachedAt = now;
    }
}
