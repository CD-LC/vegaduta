package com.agenticai.core.tenancy.demo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One line of a sandbox's chat transcript (migration V146) - the durable copy
 * the read-only phase renders from.
 *
 * <p>The demo sandbox is editable for {@code demo.editable-hours} (default
 * 24h) after creation, then stays viewable read-only until it expires (see
 * {@link DemoSessionService#isReadOnly}). The live conversation existed only
 * in the browser tab's state and the short-TTL Redis session memory - both
 * gone long before the 5-day view window closes - so every turn is also
 * appended here, capped by construction at the per-session message limit
 * (default 20 turns, so ~40 rows).
 *
 * <p>Control-plane on purpose, same chicken-and-egg reasoning as
 * {@link DemoGeneratedImage}: the transcript must be readable from a demo
 * token alone, without binding a tenant schema first.
 */
@Entity
@Table(name = "demo_chat_message", schema = "control_plane")
public class DemoChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    /** "IN" (visitor) or "OUT" (agent reply, including labelled fallbacks). */
    @Column(name = "direction", nullable = false, length = 3, updatable = false)
    private String direction;

    /**
     * True when an OUT row was a canned {@code simulated} fallback rather than
     * model output - the read-only replay keeps the same honest "sample
     * response" label the live chat showed. Always false for IN rows.
     */
    @Column(name = "simulated", nullable = false, updatable = false)
    private boolean simulated;

    @Column(name = "content", nullable = false, updatable = false)
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DemoChatMessage() {
        // JPA
    }

    public DemoChatMessage(UUID sessionId, String direction, boolean simulated, String content, Instant createdAt) {
        this.sessionId = sessionId;
        this.direction = direction;
        this.simulated = simulated;
        this.content = content;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public String getDirection() {
        return direction;
    }

    public boolean isSimulated() {
        return simulated;
    }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
