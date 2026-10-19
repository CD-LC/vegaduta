package com.agenticai.core.rl.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One ordered step of an {@link Episode}'s trajectory - a system/user/
 * assistant message, a tool call, or a tool result - {@code stepIndex}
 * ascending. Recorded either explicitly by {@code TrajectoryService} or, for
 * the assistant LLM turn itself, by {@code RlTrajectoryAdvisor}.
 *
 * <p>Schema-per-tenant: no tenant_id column (see {@code V110__rl_trajectory_step.sql}).
 */
@Entity
@Table(name = "rl_trajectory_step")
public class TrajectoryStep {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "episode_id", nullable = false)
    private UUID episodeId;

    @Column(name = "step_index", nullable = false)
    private int stepIndex;

    @Column(name = "role", length = 32)
    private String role;

    @Column(name = "action_type", length = 64)
    private String actionType;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Column(name = "tool_name", length = 256)
    private String toolName;

    @Column(name = "tokens")
    private Long tokens;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TrajectoryStep() {
        // JPA
    }

    public TrajectoryStep(
        UUID episodeId,
        int stepIndex,
        String role,
        String actionType,
        String content,
        String toolName,
        Long tokens,
        Long latencyMs,
        Instant createdAt
    ) {
        this.episodeId = episodeId;
        this.stepIndex = stepIndex;
        this.role = role;
        this.actionType = actionType;
        this.content = content;
        this.toolName = toolName;
        this.tokens = tokens;
        this.latencyMs = latencyMs;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEpisodeId() {
        return episodeId;
    }

    public int getStepIndex() {
        return stepIndex;
    }

    public String getRole() {
        return role;
    }

    public String getActionType() {
        return actionType;
    }

    public String getContent() {
        return content;
    }

    public String getToolName() {
        return toolName;
    }

    public Long getTokens() {
        return tokens;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
