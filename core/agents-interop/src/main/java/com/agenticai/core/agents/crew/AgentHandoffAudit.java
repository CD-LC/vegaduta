package com.agenticai.core.agents.crew;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One hop's durable audit row - migration V305's {@code agent_handoff_audit}. See that
 * migration's header for why this is a dedicated table rather than a row in the generic {@code
 * audit_log_entry} (V3), and for its relationship to the existing ephemeral step-event
 * {@code handoff} trace ({@code AgentStepEventEmitter#handoff}), which this table's writer
 * ({@link AgentHandoffAuditService}) leaves entirely untouched and reuses unchanged.
 */
@Entity
@Table(name = "agent_handoff_audit")
public class AgentHandoffAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "turn_session_id", nullable = false, length = 255)
    private String turnSessionId;

    @Column(name = "root_agent_id", nullable = false)
    private UUID rootAgentId;

    @Column(name = "from_agent_id", nullable = false)
    private UUID fromAgentId;

    @Column(name = "to_agent_id", nullable = false)
    private UUID toAgentId;

    @Column(name = "capability_tag", length = 100)
    private String capabilityTag;

    @Column(name = "depth", nullable = false)
    private int depth;

    @Column(name = "sub_task", length = 2000)
    private String subTask;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private HandoffStatus status;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AgentHandoffAudit() {
        // JPA
    }

    public AgentHandoffAudit(
        String turnSessionId, UUID rootAgentId, UUID fromAgentId, UUID toAgentId,
        String capabilityTag, int depth, String subTask, HandoffStatus status
    ) {
        this.turnSessionId = turnSessionId;
        this.rootAgentId = rootAgentId;
        this.fromAgentId = fromAgentId;
        this.toAgentId = toAgentId;
        this.capabilityTag = capabilityTag;
        this.depth = depth;
        this.subTask = cap(subTask, 2000);
        this.status = status;
        this.startedAt = Instant.now();
        this.createdAt = this.startedAt;
    }

    private static String cap(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    public UUID getId() {
        return id;
    }

    public String getTurnSessionId() {
        return turnSessionId;
    }

    public UUID getRootAgentId() {
        return rootAgentId;
    }

    public UUID getFromAgentId() {
        return fromAgentId;
    }

    public UUID getToAgentId() {
        return toAgentId;
    }

    public String getCapabilityTag() {
        return capabilityTag;
    }

    public int getDepth() {
        return depth;
    }

    public String getSubTask() {
        return subTask;
    }

    public HandoffStatus getStatus() {
        return status;
    }

    public void setStatus(HandoffStatus status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = cap(errorMessage, 1000);
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
