package com.agenticai.core.agents.core;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Master-Roadmap-2026.md item #5 (prompt-template versioning) - one
 * append-only snapshot of an agent's system prompt, taken immediately
 * before that prompt is about to be overwritten (see {@link
 * AgentService#snapshotCurrentPrompt}). There is no "current version" row -
 * the live prompt always lives on {@link Agent#getSystemPrompt()} itself;
 * this table exists purely so a past state can be listed and reverted to.
 */
@Entity
@Table(name = "prompt_version")
public class PromptVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "system_prompt", nullable = false, columnDefinition = "TEXT")
    private String systemPrompt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PromptVersion() {
        // JPA
    }

    private PromptVersion(UUID agentId, String systemPrompt) {
        this.agentId = agentId;
        this.systemPrompt = systemPrompt;
        this.createdAt = Instant.now();
    }

    public static PromptVersion snapshot(UUID agentId, String systemPrompt) {
        return new PromptVersion(agentId, systemPrompt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
