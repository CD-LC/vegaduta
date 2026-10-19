package com.agenticai.core.guardrails.procedures;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * Attachment of a {@link Procedure} to an agent, with a {@code position} so
 * multiple attached procedures render (and read) in a deterministic,
 * author-controlled order. A procedure is reusable across agents - the SOP
 * is workspace content, the link is the binding.
 */
@Entity
@Table(
    name = "agent_procedure",
    uniqueConstraints = @UniqueConstraint(name = "uq_agent_procedure", columnNames = {"agent_id", "procedure_id"})
)
public class AgentProcedureLink {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "procedure_id", nullable = false)
    private UUID procedureId;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AgentProcedureLink() {
        // JPA
    }

    public AgentProcedureLink(UUID agentId, UUID procedureId, int position) {
        this.agentId = agentId;
        this.procedureId = procedureId;
        this.position = position;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public UUID getProcedureId() {
        return procedureId;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }
}
