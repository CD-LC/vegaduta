package com.agenticai.core.agents.learning.preferences;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One observed style request (migration V623). Holds a signal key, never the person's message. */
@Entity
@Table(name = "preference_signals")
public class PreferenceSignal {

    @Id private UUID id;

    @Column(name = "subject", nullable = false, length = 255)
    private String subject;

    @Column(name = "agent_id")
    private UUID agentId;

    @Column(name = "signal_key", nullable = false, length = 64)
    private String signalKey;

    @Column(name = "source", nullable = false, length = 16)
    private String source;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PreferenceSignal() {}

    PreferenceSignal(String subject, UUID agentId, String signalKey, String source, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.subject = subject;
        this.agentId = agentId;
        this.signalKey = signalKey;
        this.source = source;
        this.createdAt = createdAt;
    }

    public String getSubject() {
        return subject;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getSignalKey() {
        return signalKey;
    }

    public String getSource() {
        return source;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
