package com.agenticai.core.omnichannel.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps one external channel identity (a WhatsApp phone number today, a
 * Telegram chat id once Stage 3 lands) to the {@code sessionId}
 * {@link com.agenticai.core.agents.memory.SessionMemoryService} already uses
 * for Redis-backed conversation memory, and to which agent handles it - PRD
 * Section 3.2's "each user channel must map to a unique session_id" rule.
 *
 * <p>{@code agentId} is a plain UUID column, not a JPA {@code @ManyToOne} -
 * resolving a session only needs to know which agent to route to, not to
 * eagerly load the full {@code Agent} entity graph every inbound message.
 *
 * <p><b>Pairing (Autonomy/Guardrails Strategy Stage A, migration V13):</b>
 * a brand-new session (first contact from an external identity never seen
 * before) is created {@code paired = false} with a random 6-digit {@code
 * pairingCode} - OpenClaw's "treat inbound DMs as untrusted input" pattern,
 * adapted to this codebase's tenant model. {@code TwilioWebhookController}
 * (and any future Telegram equivalent) must check {@link #isPaired()}
 * before routing an inbound message to an agent at all; an unpaired sender
 * gets a one-time pairing notice instead (see {@link #isPairingNoticeSent()}
 * - deliberately only sent once per still-unpaired session, not on every
 * retry message, so a determined unknown sender can't turn this into a free
 * outbound-send-quota drain). A tenant-admin approves via
 * {@code ChannelPairingController}, which is the only thing that ever calls
 * {@link #approvePairing()}.
 */
@Entity
@Table(
    name = "channel_session",
    uniqueConstraints = @UniqueConstraint(name = "uq_channel_session_channel_external", columnNames = {"channel", "external_id"})
)
public class ChannelSession {

    // The pairing code gates whether an unknown external sender is ever
    // routed to an agent, so it must not come from a predictable PRNG -
    // same fix DevicePairingService took for its pairing code.
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ChannelType channel;

    @Column(name = "external_id", nullable = false, length = 255)
    private String externalId;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_message_at", nullable = false)
    private Instant lastMessageAt;

    // Direct feedback: "print some information like whats the message"
    // (migration V49) - a short, truncated preview of the most recent
    // inbound message body/subject, so the Channels screen's RECENT
    // INBOUND log can show more than a bare timestamp. Nullable: rows
    // written before V49, and the very first row of a brand-new session
    // (constructor runs before any message body is known to the caller
    // in some flows), legitimately have none yet.
    @Column(name = "last_message_preview", length = 500)
    private String lastMessagePreview;

    // "still extract phone numbers are not visible" follow-up (migration
    // V51) - deliberately NOT part of external_id/the unique constraint.
    // external_id is the STABLE routing key; this column is display-
    // only metadata, populated opportunistically whenever an inbound
    // message happens to carry a resolved real phone number (senderPn),
    // and left null otherwise. See ChannelSessionService#resolveOrCreate.
    @Column(name = "resolved_phone_number", length = 64)
    private String resolvedPhoneNumber;

    @Column(nullable = false)
    private boolean paired;

    @Column(name = "pairing_code", length = 16)
    private String pairingCode;

    @Column(name = "pairing_notice_sent", nullable = false)
    private boolean pairingNoticeSent;

    @Column(name = "ai_disclosure_sent", nullable = false)
    private boolean aiDisclosureSent;

    // Live human handoff (migration V214) - who is handling this
    // conversation right now. See HandoffState's javadoc for the three
    // states and the deliberate "agent keeps replying while AWAITING_HUMAN"
    // asymmetry; see ConversationHeldByHumanException for how HUMAN is
    // enforced fail-closed on the inbound path.
    @Enumerated(EnumType.STRING)
    @Column(name = "handoff_state", nullable = false, length = 16)
    private HandoffState handoffState;

    // Why this conversation was escalated - shown verbatim in the operator
    // inbox, so it is capped at the same 500 chars as last_message_preview
    // and truncated the same way rather than erroring.
    @Column(name = "escalation_reason", length = 500)
    private String escalationReason;

    @Column(name = "escalated_at")
    private Instant escalatedAt;

    // The holding operator's Keycloak subject. Opaque VARCHAR, never an FK -
    // same reasoning as user_permission_grant.keycloak_user_id (V199):
    // control_plane.app_user only has rows for the original signup admin,
    // so an FK would reject every operator an admin later invited.
    @Column(name = "taken_over_by", length = 255)
    private String takenOverBy;

    @Column(name = "taken_over_at")
    private Instant takenOverAt;

    protected ChannelSession() {
        // JPA
    }

    public ChannelSession(ChannelType channel, String externalId, String sessionId, UUID agentId) {
        this(channel, externalId, sessionId, agentId, null);
    }

    public ChannelSession(ChannelType channel, String externalId, String sessionId, UUID agentId, String firstMessagePreview) {
        this(channel, externalId, sessionId, agentId, firstMessagePreview, null);
    }

    public ChannelSession(
        ChannelType channel, String externalId, String sessionId, UUID agentId, String firstMessagePreview, String resolvedPhoneNumber
    ) {
        this.channel = channel;
        this.externalId = externalId;
        this.sessionId = sessionId;
        this.agentId = agentId;
        Instant now = Instant.now();
        this.createdAt = now;
        this.lastMessageAt = now;
        this.lastMessagePreview = truncatePreview(firstMessagePreview);
        this.resolvedPhoneNumber = resolvedPhoneNumber;
        // Every brand-new session starts unpaired - see this class's javadoc.
        this.paired = false;
        this.pairingCode = generatePairingCode();
        this.pairingNoticeSent = false;
        this.aiDisclosureSent = false;
        // Every brand-new session starts agent-handled (V214) - matching the
        // column DEFAULT that backfills all pre-V214 rows.
        this.handoffState = HandoffState.AGENT;
    }

    private static String generatePairingCode() {
        return String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
    }

    public void touch() {
        touch(null);
    }

    /** Same as {@link #touch()}, plus records a preview of this message (see {@code last_message_preview}, migration V49). */
    public void touch(String messagePreview) {
        touch(messagePreview, null);
    }

    /**
     * Same as {@link #touch(String)}, plus opportunistically records a
     * resolved real phone number for display (migration V51). {@code
     * resolvedPhoneNumber} is only ever written when non-null/non-blank -
     * a message that doesn't carry one (WhatsApp didn't attach senderPn
     * this time) must never blank out a value a PREVIOUS message already
     * resolved.
     */
    public void touch(String messagePreview, String resolvedPhoneNumber) {
        this.lastMessageAt = Instant.now();
        if (messagePreview != null) {
            this.lastMessagePreview = truncatePreview(messagePreview);
        }
        if (resolvedPhoneNumber != null && !resolvedPhoneNumber.isBlank()) {
            this.resolvedPhoneNumber = resolvedPhoneNumber;
        }
    }

    /**
     * Drops the stored message preview. Used when the sender has not (yet) consented to their
     * message content being stored (see {@code ConversationConsentGate#mayStoreContent}).
     */
    public void clearMessagePreview() {
        this.lastMessagePreview = null;
    }

    private static String truncatePreview(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > 300 ? trimmed.substring(0, 300) + "…" : trimmed;
    }

    /**
     * Re-points this ALREADY-EXISTING session to a different agent (#590
     * follow-up: "quick task ... selecting wrong agent for telegram message
     * sending"). {@code agentId} is otherwise only ever set once, at
     * construction, from whatever the tenant's default-agent binding
     * happened to be at first contact ({@code
     * ChannelSessionService#resolveOrCreate}) - later changing that default
     * (Channels screen's "ROUTE INBOUND TO" picker) has NO effect on
     * sessions that already exist, which is exactly the gap this method
     * closes: an admin can now fix one specific stuck conversation (see
     * {@code ChannelPairingController#rebindAgent}) without waiting for a
     * brand-new external identity to pick up the corrected default.
     */
    public void rebind(UUID agentId) {
        this.agentId = agentId;
    }

    public void approvePairing() {
        this.paired = true;
        this.pairingCode = null;
    }

    /**
     * Reverse of {@link #approvePairing()} - "unmaps" an already-paired
     * conversation back into the pending-pairing inbox WITHOUT deleting it or
     * its history: clears the paired flag, mints a fresh pairing code, and
     * re-arms the one-time pairing notice so the sender is re-challenged on
     * their next message. An admin action distinct from reject (which deletes
     * an unpaired pending session outright) and rebind (which only swaps the
     * agent) - use it to undo a wrong pairing or force re-verification.
     */
    public void unpair() {
        this.paired = false;
        this.pairingCode = generatePairingCode();
        this.pairingNoticeSent = false;
        // V214: unpairing also releases any live-handoff hold. The two
        // states must not coexist - a held session is exempted from the
        // pairing gate's re-challenge (the agent-suppression gate in
        // ChannelSessionService#resolveOrCreate fires first), so an unpair
        // that left handoff_state=HUMAN behind would strand the sender:
        // never re-challenged, never answered. Unpairing means "this
        // conversation goes back through the front door", and the front
        // door is agent-handled by definition.
        releaseToAgent();
    }

    /**
     * Live human handoff (V214) - flags this conversation as needing a
     * person, WITHOUT suppressing the agent yet (see {@link HandoffState}'s
     * javadoc for why AWAITING_HUMAN deliberately keeps the agent talking).
     * Idempotent-by-refresh: escalating an already-escalated session updates
     * the reason and timestamp rather than erroring - the newest cry for
     * help is the one the operator inbox should show. A session already
     * HELD by a human is left untouched: there is nothing more escalation
     * could achieve, and stomping {@code escalatedAt} would corrupt the
     * "how long did they wait" signal the inbox sorts by.
     */
    public void escalateToHuman(String reason) {
        if (handoffState == HandoffState.HUMAN) {
            return;
        }
        this.handoffState = HandoffState.AWAITING_HUMAN;
        this.escalationReason = truncatePreview(reason);
        this.escalatedAt = Instant.now();
    }

    /**
     * Live human handoff (V214) - a named operator claims this conversation.
     * From this moment {@code ChannelSessionService#resolveOrCreate} throws
     * {@link ConversationHeldByHumanException} for every inbound message, so
     * the agent and the channel workflow trigger both stay silent until
     * {@link #releaseToAgent()}. Guards (paired-only, never VOICE, not held
     * by a DIFFERENT operator) live in {@code OperatorInboxService#takeOver},
     * which is the only caller - kept there rather than here so the error
     * messages can carry HTTP-facing context this entity has no business
     * knowing about.
     */
    public void takeOver(String operatorSubject) {
        this.handoffState = HandoffState.HUMAN;
        this.takenOverBy = operatorSubject;
        this.takenOverAt = Instant.now();
    }

    /**
     * Live human handoff (V214) - hands the conversation back to the agent,
     * clearing every handoff field so the session is indistinguishable from
     * one that was never escalated. Deliberately clears the escalation
     * fields too (not just the takeover ones): a released conversation that
     * still read AWAITING_HUMAN would sit in the operator inbox forever, and
     * the durable record of who held what when already lives in the audit
     * log ({@code OperatorInboxService} records every transition), not on
     * this row.
     */
    public void releaseToAgent() {
        this.handoffState = HandoffState.AGENT;
        this.takenOverBy = null;
        this.takenOverAt = null;
        this.escalationReason = null;
        this.escalatedAt = null;
    }

    /** @return {@code true} when a human operator holds this conversation and the agent must not reply - see {@link HandoffState#HUMAN}. */
    public boolean isHeldByHuman() {
        return handoffState == HandoffState.HUMAN;
    }

    public void markPairingNoticeSent() {
        this.pairingNoticeSent = true;
    }

    /** @return {@code true} if this call is the one that should carry the AI-disclosure notice (i.e. it just flipped from false to true); {@code false} if it was already sent. */
    public boolean markAiDisclosureSentIfNeeded() {
        if (aiDisclosureSent) {
            return false;
        }
        aiDisclosureSent = true;
        return true;
    }

    public UUID getId() {
        return id;
    }

    public ChannelType getChannel() {
        return channel;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastMessageAt() {
        return lastMessageAt;
    }

    public String getLastMessagePreview() {
        return lastMessagePreview;
    }

    public String getResolvedPhoneNumber() {
        return resolvedPhoneNumber;
    }

    public boolean isPaired() {
        return paired;
    }

    public String getPairingCode() {
        return pairingCode;
    }

    public boolean isPairingNoticeSent() {
        return pairingNoticeSent;
    }

    public boolean isAiDisclosureSent() {
        return aiDisclosureSent;
    }

    public HandoffState getHandoffState() {
        return handoffState;
    }

    public String getEscalationReason() {
        return escalationReason;
    }

    public Instant getEscalatedAt() {
        return escalatedAt;
    }

    public String getTakenOverBy() {
        return takenOverBy;
    }

    public Instant getTakenOverAt() {
        return takenOverAt;
    }
}
