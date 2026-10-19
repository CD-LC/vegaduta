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
 * Maps one inbound E.164 phone number to the {@link com.agenticai.core.agents.core.Agent}
 * that should answer calls to it - "sales on +1555..., support on +1666...",
 * which the tenant-wide {@link VoiceChannelController#setDefaultAgent}
 * could not express.
 *
 * <p><b>Why this is a per-call override and not a {@code ChannelSession}
 * rebind.</b> {@link com.agenticai.core.omnichannel.session.ChannelSession}
 * is keyed on {@code (channel, externalId)} where {@code externalId} for
 * VOICE is the <i>caller's</i> number, and the agent is stamped onto that row
 * at first contact and never re-read. So the session identifies WHO is
 * calling, not WHAT they dialed - one caller who phones a tenant's sales line
 * and then its support line is the same session. Routing by dialed number
 * therefore cannot go through session creation without either fragmenting a
 * caller's memory across numbers or silently rebinding an existing
 * conversation to a different agent mid-history. Instead {@link
 * VoiceRelayWebSocketHandler} overrides only the in-memory {@code
 * voiceRelay.agentId} WebSocket attribute for the duration of the call -
 * exactly the mechanism {@link VoiceDtmfMapping} already uses for "press 2
 * for support", and for the same reason. The caller's conversation memory
 * stays continuous; only which agent is asked to answer changes.
 *
 * <p><b>Precedence, highest first:</b> a mid-call DTMF digit ({@link
 * VoiceDtmfMapping}) beats this number mapping, which beats the tenant-wide
 * {@code ChannelDefaultAgent} for {@code ChannelType.VOICE}. DTMF is last
 * because it is the caller's own explicit, later choice.
 *
 * <p><b>A tenant-wide default agent is still required</b> even for a
 * numbers-only setup: {@code ChannelSessionService.resolveOrCreate} stamps
 * every brand-new session with {@code defaultAgentIdFor(VOICE)} and throws
 * {@code ChannelConfigurationException} when none is set. That call is shared
 * by all ten inbound channel entry points, so this feature deliberately does
 * not change it. Configure a default first, then map numbers over the top.
 *
 * <p>{@code phone_number} is stored strictly normalized (leading {@code +},
 * digits only - see {@link VoiceNumberAgentService#normalizeNumber}) so that
 * an admin typing {@code "+1 (555) 123-4567"} and Twilio sending
 * {@code "+15551234567"} resolve to the same row.
 */
@Entity
@Table(name = "voice_number_agent")
public class VoiceNumberAgent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "phone_number", nullable = false, unique = true, length = 16)
    private String phoneNumber;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(length = 255)
    private String label;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected VoiceNumberAgent() {
        // JPA
    }

    public VoiceNumberAgent(String phoneNumber, UUID agentId, String label) {
        this.phoneNumber = phoneNumber;
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

    public String getPhoneNumber() {
        return phoneNumber;
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
