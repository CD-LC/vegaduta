package com.agenticai.core.agents.branching;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Per-(agent, session) branching state (V450 {@code chat_branch_session}): which message is the tip
 * of the path being shown, the one-shot regenerate hint, and the subject that owns the tree.
 */
@Entity
@Table(name = "chat_branch_session")
public class ChatBranchSession {

    @Id
    private UUID id;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "session_id", nullable = false, length = 128)
    private String sessionId;

    @Column(name = "owner_subject", length = 255)
    private String ownerSubject;

    @Column(name = "active_leaf_message_id")
    private UUID activeLeafMessageId;

    @Column(name = "reuse_user_message_id")
    private UUID reuseUserMessageId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ChatBranchSession() {
        // JPA
    }

    public ChatBranchSession(UUID agentId, String sessionId) {
        this.id = UUID.randomUUID();
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getOwnerSubject() {
        return ownerSubject;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public ChatBranchStore.State toState() {
        return new ChatBranchStore.State(agentId, sessionId, ownerSubject, activeLeafMessageId, reuseUserMessageId);
    }

    public void apply(ChatBranchStore.State state) {
        this.ownerSubject = state.ownerSubject();
        this.activeLeafMessageId = state.activeLeafId();
        this.reuseUserMessageId = state.reuseUserMessageId();
        this.updatedAt = Instant.now();
    }
}
