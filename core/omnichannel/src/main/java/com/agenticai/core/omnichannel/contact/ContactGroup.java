package com.agenticai.core.omnichannel.contact;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A named group of {@link ChannelContact}s ("VIP customers", "Sales leads")
 * with an optional group-level agent binding - a member contact with no
 * per-contact {@code agentId} override routes to this agent. Nullable by
 * design: a group can also be a purely organizational label that changes no
 * routing at all.
 *
 * <p>Tenant-scoped by schema-per-tenant (same mechanism as
 * {@code channel_session}); agentId is a plain UUID column rather than a
 * {@code @ManyToOne}, matching ChannelSession's own "routing needs the id,
 * not the entity graph" posture.
 */
@Entity
@Table(name = "contact_group")
public class ContactGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 128, unique = true)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(name = "agent_id")
    private UUID agentId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ContactGroup() {
        // JPA
    }

    public ContactGroup(String name, String description, UUID agentId) {
        this.name = name;
        this.description = description;
        this.agentId = agentId;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(String name, String description, UUID agentId) {
        this.name = name;
        this.description = description;
        this.agentId = agentId;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
