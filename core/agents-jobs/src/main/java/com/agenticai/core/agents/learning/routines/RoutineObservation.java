package com.agenticai.core.agents.learning.routines;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One scrubbed request the person made (migration V633); the raw material of the routines loop. */
@Entity
@Table(name = "routine_observations")
public class RoutineObservation {

    @Id private UUID id;

    @Column(name = "subject", nullable = false, length = 255)
    private String subject;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "source", nullable = false, length = 8)
    private String source;

    @Column(name = "norm_text", nullable = false, length = 600)
    private String normText;

    @Column(name = "raw_text", nullable = false, length = 500)
    private String rawText;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    protected RoutineObservation() {}

    RoutineObservation(
            UUID id,
            String subject,
            UUID agentId,
            String source,
            String normText,
            String rawText,
            Instant observedAt) {
        this.id = id;
        this.subject = subject;
        this.agentId = agentId;
        this.source = source;
        this.normText = normText;
        this.rawText = rawText;
        this.observedAt = observedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getSubject() {
        return subject;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getSource() {
        return source;
    }

    public String getNormText() {
        return normText;
    }

    public String getRawText() {
        return rawText;
    }

    public Instant getObservedAt() {
        return observedAt;
    }
}
