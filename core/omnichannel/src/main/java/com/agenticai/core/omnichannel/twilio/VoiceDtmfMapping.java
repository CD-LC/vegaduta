package com.agenticai.core.omnichannel.twilio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Master Roadmap 2026 item #17 fast-follow (voice DTMF handling) -
 * {@link VoiceRelayWebSocketHandler}'s javadoc flagged this exact gap:
 * a {@code dtmf} Conversation Relay message was received but discarded
 * ("Stage 1 scope"). This table is the tenant-admin-configured menu that
 * closes it: one row per keypad digit ({@code 0}-{@code 9}, {@code *},
 * {@code #}), mapping that digit to the {@link com.agenticai.core.agents.core.Agent}
 * the caller should be routed to for the REST of the current call - the
 * caller's default agent (set via {@link VoiceChannelController#setDefaultAgent})
 * still answers first; pressing a mapped digit mid-call switches only the
 * in-memory {@code voiceRelay.agentId} WebSocket session attribute (see
 * {@link VoiceRelayWebSocketHandler}), never the call's {@code ChannelSession}
 * row - so the switch is scoped to this one call, exactly like a real IVR
 * "press 2 for support" menu, not a change to which agent answers the next
 * inbound call from this number.
 *
 * <p>One row per digit, not a JSON blob column, so a tenant admin can list/
 * edit/delete a single digit's mapping independently (same reasoning
 * {@code FeatureFlag}'s own per-key rows already use over a single settings
 * blob). {@code digit} is the natural key - no separate synthetic lookup
 * needed beyond the UUID primary key JPA conventionally wants.
 */
@Entity
@Table(name = "voice_dtmf_mapping")
public class VoiceDtmfMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 1)
    private String digit;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(length = 255)
    private String label;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected VoiceDtmfMapping() {
        // JPA
    }

    public VoiceDtmfMapping(String digit, UUID agentId, String label) {
        this.digit = digit;
        this.agentId = agentId;
        this.label = label;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(UUID agentId, String label) {
        this.agentId = agentId;
        this.label = label;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getDigit() {
        return digit;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getLabel() {
        return label;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
