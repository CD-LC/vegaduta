package com.agenticai.core.agents.branching;

import com.agenticai.core.agents.memory.MessageRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One message of a branched conversation (V450 {@code chat_branch_node}). Tenant-schema, no
 * tenant column. {@code parentId} is deliberately not a JPA relation: the tree is always loaded
 * whole per session and walked in memory by {@link ChatBranchTree}.
 */
@Entity
@Table(name = "chat_branch_node")
public class ChatBranchNode {

    @Id
    private UUID id;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "session_id", nullable = false, length = 128)
    private String sessionId;

    @Column(name = "parent_message_id")
    private UUID parentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private MessageRole role;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "sibling_index", nullable = false)
    private int siblingIndex;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ChatBranchNode() {
        // JPA
    }

    public ChatBranchNode(UUID agentId, String sessionId, ChatBranchTree.Node node) {
        this.id = node.id();
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.parentId = node.parentId();
        this.role = node.role();
        this.content = node.content() == null ? "" : node.content();
        this.siblingIndex = node.siblingIndex();
        this.createdAt = node.createdAt() == null ? Instant.now() : node.createdAt();
    }

    public ChatBranchTree.Node toNode() {
        return new ChatBranchTree.Node(id, parentId, role, content, siblingIndex, createdAt);
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
}
