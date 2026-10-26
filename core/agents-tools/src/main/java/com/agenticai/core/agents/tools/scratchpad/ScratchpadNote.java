package com.agenticai.core.agents.tools.scratchpad;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One note in an agent's scratchpad - a persistent, agent-authored key/value notepad that survives
 * turns (see migration {@code V672__agent_scratchpad.sql}).
 *
 * <p>Scope is {@code (agentId, sessionId)}: a {@code null} {@link #sessionId} is an agent-long-term
 * note visible from every session of that agent, a non-null one belongs to that chat session only.
 * The database enforces one note per {@code (agent_id, session_id, key)} via a {@code COALESCE}
 * unique index, so {@code AgentScratchpadService#write} is an upsert, never an insert-only.
 *
 * <p>Tenant-schema entity, no {@code tenant_id} column: this platform is schema-per-tenant, so the
 * row's schema IS its tenancy (same convention as {@code CustomCodeTool}, {@code AgentRow}, and
 * every other tenant-scoped entity in this module).
 *
 * <p>The write-time bounds (key shape, {@link #byteSize} ceiling, notes per scope) live in {@link
 * ScratchpadProperties} and are enforced by {@code AgentScratchpadService}; nothing here assumes a
 * stored row passed today's configured caps.
 */
@Entity
@Table(name = "agent_scratchpad")
public class ScratchpadNote {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    // NULL = agent-long-term note, visible from any of the agent's sessions.
    @Column(name = "session_id")
    private UUID sessionId;

    // Free-form within ^[a-zA-Z0-9_.-]{1,64}$, enforced in the service - TEXT
    // here so the cap stays configuration, not schema.
    @Column(name = "key", nullable = false, columnDefinition = "TEXT")
    private String key;

    // UTF-8 byte-capped at write time (app.tools.scratchpad.max-note-bytes);
    // TEXT because the cap is a product/abuse bound, not a storage one.
    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    // body's UTF-8 byte length at write time, kept so list views and cap
    // auditing never have to re-decode the body.
    @Column(name = "byte_size", nullable = false)
    private int byteSize;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ScratchpadNote() {
        // JPA
    }

    public ScratchpadNote(UUID agentId, UUID sessionId, String key, String body, int byteSize) {
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.key = key;
        this.body = body;
        this.byteSize = byteSize;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Overwrites the note content. Always bumps {@code updatedAt} - it is the list ordering. */
    public void update(String body, int byteSize) {
        this.body = body;
        this.byteSize = byteSize;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getAgentId() {
        return agentId;
    }

    /** Null means agent-long-term: the note is not tied to any one session. */
    public UUID getSessionId() {
        return sessionId;
    }

    public String getKey() {
        return key;
    }

    public String getBody() {
        return body;
    }

    public int getByteSize() {
        return byteSize;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
