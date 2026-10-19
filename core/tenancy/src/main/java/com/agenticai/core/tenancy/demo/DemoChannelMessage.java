package com.agenticai.core.tenancy.demo;

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
 * A bounded display buffer of what was said over a demo channel - migration
 * V128 - so a turn the visitor typed on their PHONE shows up on the
 * {@code /try} tab they left open on their laptop.
 *
 * <p><b>This is not the conversation of record.</b> The real transcript lives
 * inside the demo tenant's own schema, written by the same
 * {@code AgentChatService} path every registered tenant uses. These rows exist
 * purely so the browser has something to poll, and
 * {@link DemoChannelLinkService} prunes them to the most recent handful per
 * session on every append.
 */
@Entity
@Table(name = "demo_channel_message", schema = "control_plane")
public class DemoChannelMessage {

    /** Who said it. Named from the visitor's point of view, matching V128's CHECK constraint. */
    public enum Direction {
        /** Visitor &rarr; agent: typed in the messenger. */
        IN,
        /** Agent &rarr; visitor: the reply the bot actually sent. */
        OUT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "demo_session_id", nullable = false, updatable = false)
    private UUID demoSessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 24, updatable = false)
    private DemoChannelType channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 8, updatable = false)
    private Direction direction;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String body;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DemoChannelMessage() {
        // JPA
    }

    DemoChannelMessage(UUID demoSessionId, DemoChannelType channel, Direction direction, String body, Instant createdAt) {
        this.demoSessionId = demoSessionId;
        this.channel = channel;
        this.direction = direction;
        this.body = body;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDemoSessionId() {
        return demoSessionId;
    }

    public DemoChannelType getChannel() {
        return channel;
    }

    public Direction getDirection() {
        return direction;
    }

    public String getBody() {
        return body;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
