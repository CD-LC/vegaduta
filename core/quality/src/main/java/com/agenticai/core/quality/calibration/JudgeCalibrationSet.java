package com.agenticai.core.quality.calibration;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import java.time.Instant;
import java.util.UUID;

/**
 * A reusable, human-labelled sample for calibrating the tenant's LLM judge. Held in a {@link JudgeCalibrationStore}
 * (no SQL table: schema changes live in another codebase). {@code agentId} is not validated against the agent
 * table, so a calibration history outlives the agent definition.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE,
    isGetterVisibility = JsonAutoDetect.Visibility.NONE)
public class JudgeCalibrationSet {
    private UUID id = UUID.randomUUID();
    private UUID agentId;
    private String name;
    private String createdBy;
    private Instant createdAt;
    private Instant updatedAt;

    protected JudgeCalibrationSet() {
        // Jackson
    }

    public JudgeCalibrationSet(UUID agentId, String name, String createdBy) {
        this.agentId = agentId;
        this.name = name;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void rename(String name) {
        this.name = name;
        this.updatedAt = Instant.now();
    }

    /** Bumps {@code updatedAt} after an item add/remove: the sample changed, so did the set. */
    public void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getName() {
        return name;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
