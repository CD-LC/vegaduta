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
 * One unregistered visitor's binding between their throwaway sandbox and a
 * real messaging surface - migration V128.
 *
 * <p>Lives in {@code control_plane} for the same reason {@link DemoSession}
 * does, and the reason is sharper here: an inbound Telegram update arrives
 * carrying a {@code chat_id} and nothing else. Turning that into a Postgres
 * schema is the precondition for entering a tenant schema at all, so the
 * mapping cannot live inside one.
 *
 * <p><b>One row per (session, channel), for the lifetime of the session.</b>
 * Re-pairing after a disconnect resets this row rather than inserting a
 * second - see {@link DemoChannelLinkState#REVOKED}. That is a deliberate
 * analytics decision as much as a modelling one: it keeps "sandboxes that
 * were offered this channel" an honest denominator instead of one that
 * inflates every time a visitor taps the button twice.
 *
 * <p><b>What this row is NOT allowed to become.</b> It never grants access to
 * anything beyond one message-capped, 5-day, FREE-plan sandbox. It holds no
 * phone number, no email, no external account id beyond an opaque Telegram
 * chat id, and {@link #peerLabel} is display-only - nothing resolves a
 * session from it.
 */
@Entity
@Table(name = "demo_channel_link", schema = "control_plane")
public class DemoChannelLink {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "demo_session_id", nullable = false, updatable = false)
    private UUID demoSessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 24, updatable = false)
    private DemoChannelType channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private DemoChannelLinkState state;

    /**
     * Cleared to null the moment it is redeemed, so a code is single-use and
     * the partial unique index in V128 only ever governs live codes.
     */
    @Column(name = "pairing_code", length = 16)
    private String pairingCode;

    /** Telegram {@code chat_id} once redeemed. Always null for {@link DemoChannelType#WHATSAPP_PREVIEW}. */
    @Column(name = "external_id", length = 128)
    private String externalId;

    @Column(name = "peer_label", length = 64)
    private String peerLabel;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "linked_at")
    private Instant linkedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    @Column(name = "message_count", nullable = false)
    private int messageCount;

    protected DemoChannelLink() {
        // JPA
    }

    DemoChannelLink(UUID demoSessionId, DemoChannelType channel, String pairingCode, Instant createdAt) {
        this.demoSessionId = demoSessionId;
        this.channel = channel;
        this.pairingCode = pairingCode;
        this.state = DemoChannelLinkState.PENDING;
        this.createdAt = createdAt;
        this.messageCount = 0;
    }

    /** Re-arms a revoked (or stale-code) row with a fresh code. Keeps history: {@code createdAt} and counters are untouched. */
    void reissue(String pairingCode) {
        this.pairingCode = pairingCode;
        this.state = DemoChannelLinkState.PENDING;
        this.externalId = null;
        this.peerLabel = null;
        this.revokedAt = null;
    }

    void markLinked(String externalId, String peerLabel, Instant at) {
        this.externalId = externalId;
        this.peerLabel = peerLabel;
        this.state = DemoChannelLinkState.LINKED;
        this.pairingCode = null; // single-use, see the field javadoc
        this.revokedAt = null;
        if (this.linkedAt == null) {
            this.linkedAt = at;
        }
    }

    void markRevoked(Instant at) {
        this.state = DemoChannelLinkState.REVOKED;
        this.revokedAt = at;
        this.externalId = null;
        this.pairingCode = null;
    }

    void recordMessage(Instant at) {
        this.messageCount++;
        this.lastMessageAt = at;
    }

    public boolean isLinked() {
        return state == DemoChannelLinkState.LINKED;
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

    public DemoChannelLinkState getState() {
        return state;
    }

    public String getPairingCode() {
        return pairingCode;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getPeerLabel() {
        return peerLabel;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLinkedAt() {
        return linkedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Instant getLastMessageAt() {
        return lastMessageAt;
    }

    public int getMessageCount() {
        return messageCount;
    }
}
