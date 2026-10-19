package com.agenticai.core.omnichannel.contact;

import com.agenticai.core.omnichannel.session.ChannelType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * One known inbound-channel sender - the durable directory entry behind the
 * /admin/contacts screen. Deliberately separate from {@code ChannelSession}:
 * a contact can exist before the person ever messages (CSV/XLSX import), and
 * survives their session row being rejected/deleted.
 *
 * <p>What "known contact" buys the sender: {@code ChannelSessionService
 * #resolveOrCreate} starts their session {@code paired = true} (the import or
 * admin add IS the approval - re-challenging them through the pending-pairing
 * queue would be pointless) and routes them by contact agent override ->
 * group agent -> channel default agent, in that order.
 *
 * <p>{@code identifier} is stored exactly as written (a phone as typed, a
 * Telegram chat id, a WhatsApp LID like {@code "12345@lid"}, an email
 * address); {@code identifierDigits} is the digit-only projection used for
 * phone matching so "+1 (555) 123-4567" still matches an external_id of
 * {@code "15551234567@s.whatsapp.net"}. Nullable - a Telegram chat id or an
 * email has no meaningful digit projection... it does, actually (the chat id
 * itself is digits), which is fine: digits matching only ever ADDS recall on
 * phone-shaped identifiers, never changes exact-match behaviour.
 */
@Entity
@Table(
    name = "channel_contact",
    uniqueConstraints = @UniqueConstraint(name = "uq_channel_contact_channel_identifier", columnNames = {"channel", "identifier"})
)
public class ChannelContact {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ChannelType channel;

    @Column(nullable = false, length = 128)
    private String identifier;

    @Column(name = "identifier_digits", length = 64)
    private String identifierDigits;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "group_id")
    private UUID groupId;

    @Column(name = "agent_id")
    private UUID agentId;

    @Column(nullable = false, length = 16)
    private String source;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    protected ChannelContact() {
        // JPA
    }

    public ChannelContact(ChannelType channel, String identifier, String displayName, UUID groupId, UUID agentId, String source) {
        this.channel = channel;
        this.identifier = identifier;
        this.identifierDigits = digitsOf(identifier);
        this.displayName = displayName;
        this.groupId = groupId;
        this.agentId = agentId;
        this.source = source;
        this.createdAt = Instant.now();
    }

    /** Digit-only projection for phone matching; null when the identifier carries no digits at all (e.g. an email). */
    public static String digitsOf(String value) {
        if (value == null) {
            return null;
        }
        String digits = value.replaceAll("\\D", "");
        return digits.isEmpty() ? null : digits;
    }

    public void update(String displayName, UUID groupId, UUID agentId) {
        this.displayName = displayName;
        this.groupId = groupId;
        this.agentId = agentId;
    }

    /** Re-derive the digits projection - called whenever identifier changes (import upsert). */
    public void refreshIdentifierDigits() {
        this.identifierDigits = digitsOf(this.identifier);
    }

    /** Stamps the live channel_session this contact maps to and records the sighting. */
    public void linkSession(UUID sessionId) {
        this.sessionId = sessionId;
        this.lastSeenAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public ChannelType getChannel() {
        return channel;
    }

    public String getIdentifier() {
        return identifier;
    }

    public String getIdentifierDigits() {
        return identifierDigits;
    }

    public String getDisplayName() {
        return displayName;
    }

    public UUID getGroupId() {
        return groupId;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getSource() {
        return source;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }
}
