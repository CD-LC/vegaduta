package com.agenticai.core.agents.core;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Objects;

/**
 * Who created an agent (migration V151, nullable, set once by {@code AgentService#create}; null =
 * predates cross-user sharing, which {@code ResourceAccessService} treats as tenant-wide-open) and
 * who made the latest config change (migration V393, written by {@code
 * AgentService#saveVersioned}). Embedded in {@link Agent}; the columns keep their names. Hibernate
 * materialises a null embedded when both columns are null, so {@link Agent} reads defensively.
 */
@Embeddable
public class AgentAuditActors {

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    /** JPA, and the nobody-recorded starting state. */
    protected AgentAuditActors() {}

    public static AgentAuditActors none() {
        return new AgentAuditActors();
    }

    public AgentAuditActors withCreatedBy(String actor) {
        AgentAuditActors a = new AgentAuditActors();
        a.createdBy = actor;
        a.updatedBy = updatedBy;
        return a;
    }

    public AgentAuditActors withUpdatedBy(String actor) {
        AgentAuditActors a = new AgentAuditActors();
        a.createdBy = createdBy;
        a.updatedBy = actor;
        return a;
    }

    public String createdBy() {
        return createdBy;
    }

    public String updatedBy() {
        return updatedBy;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof AgentAuditActors a
                && Objects.equals(createdBy, a.createdBy)
                && Objects.equals(updatedBy, a.updatedBy);
    }

    @Override
    public int hashCode() {
        return Objects.hash(createdBy, updatedBy);
    }
}
