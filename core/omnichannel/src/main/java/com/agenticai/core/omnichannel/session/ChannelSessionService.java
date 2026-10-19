package com.agenticai.core.omnichannel.session;

import com.agenticai.core.agents.consent.ConversationConsentGate;
import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.omnichannel.ChannelConfigurationException;
import com.agenticai.core.omnichannel.contact.ChannelContact;
import com.agenticai.core.omnichannel.contact.ChannelContactService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves (creating on first contact) the {@link ChannelSession} for an inbound message - the
 * piece that gives inbound WhatsApp
 * messages a real, persistent mapping to an agent conversation instead of no session tracking at
 * all.
 *
 * <p>Which agent is "the default" for a channel is itself tenant-scoped (see {@link
 * ChannelDefaultAgent}) - each tenant configures its own via {@code PUT
 * /api/channels/whatsapp/default-agent}, resolved automatically from whichever schema is active
 * (Phase 0 Stage 4), replacing an earlier version of this class that read a single global {@code
 * WHATSAPP_DEFAULT_AGENT_ID} env var - a real gap once more than one tenant exists, caught and
 * fixed as part of Stage 4's follow-up work rather than left as "the next stage's problem."
 */
@Service
public class ChannelSessionService {

    private final ChannelSessionRepository sessionRepository;
    private final ChannelDefaultAgentRepository defaultAgentRepository;
    private final ChannelMessageLogRepository messageLogRepository;
    private final ChannelContactService contactService;
    private final AuditLogService auditLogService;
    private final InboundSafetyScanService inboundSafetyScanService;
    // Optional: AgentMemoryService is @Profile("knowledge")-gated (needs a
    // pgvector-enabled Postgres + running sidecar) - a build without that
    // profile active simply has no long-term agent memory to erase, so this
    // is a graceful-absence ObjectProvider, same pattern as
    // ToolCallVerifierService's optional injection elsewhere in this codebase.
    private final ObjectProvider<AgentMemoryService> agentMemoryService;

    // Optional DPDP-oriented consent step; null = every sender's content may be stored (as before).
    // Setter-injected so the constructor stays as it was.
    private volatile ConversationConsentGate consentGate;

    // Optional per-sender / per-channel turn cap; null = no gate (as before). Setter-injected so the
    // constructor stays as it was.
    private volatile InboundRateLimiter rateLimiter;

    @Autowired
    public void setRateLimiter(ObjectProvider<InboundRateLimiter> provider) {
        this.rateLimiter = provider == null ? null : provider.getIfAvailable();
    }

    @Autowired
    public void setConsentGate(ObjectProvider<ConversationConsentGate> provider) {
        this.consentGate = provider == null ? null : provider.getIfAvailable();
    }

    private boolean mayStoreContent(ChannelSession session, ChannelType channel, String externalId) {
        ConversationConsentGate gate = consentGate;
        return gate == null || gate.mayStoreContent(session.getAgentId(), channel.name(), externalId);
    }

    public ChannelSessionService(
            ChannelSessionRepository sessionRepository,
            ChannelDefaultAgentRepository defaultAgentRepository,
            ChannelMessageLogRepository messageLogRepository,
            ChannelContactService contactService,
            AuditLogService auditLogService,
            InboundSafetyScanService inboundSafetyScanService,
            ObjectProvider<AgentMemoryService> agentMemoryService) {
        this.sessionRepository = sessionRepository;
        this.defaultAgentRepository = defaultAgentRepository;
        this.messageLogRepository = messageLogRepository;
        this.contactService = contactService;
        this.auditLogService = auditLogService;
        this.inboundSafetyScanService = inboundSafetyScanService;
        this.agentMemoryService = agentMemoryService;
    }

    @Transactional(noRollbackFor = ConversationHeldByHumanException.class)
    public ChannelSession resolveOrCreate(ChannelType channel, String externalId) {
        return resolveOrCreate(channel, externalId, null);
    }

    /**
     * "print some information like whats the message" - {@code messagePreview} (the raw inbound
     * text/subject, still available to every caller at the point it resolves a session) is threaded
     * through so {@link ChannelSession#getLastMessagePreview()} has something real to show on the
     * Channels screen's RECENT INBOUND log, instead of only a timestamp.
     *
     * <p>#604 follow-up ("read all messages from telegram") - also appends a {@link
     * ChannelMessageLog} row whenever a real message body is present, since {@code
     * lastMessagePreview} above is overwritten on every call and can't answer "read ALL messages,"
     * only "what did it last say."
     */
    /**
     * Side-effect-free lookup (no touch, no message-log row, no creation) used by inbound webhooks
     * to decide, BEFORE claiming an LLM slot, whether a sender is an existing unpaired one (a cheap
     * pairing-notice path).
     */
    @Transactional(readOnly = true)
    public Optional<ChannelSession> peek(ChannelType channel, String externalId) {
        return sessionRepository.findByChannelAndExternalId(channel, externalId);
    }

    @Transactional(noRollbackFor = ConversationHeldByHumanException.class)
    public ChannelSession resolveOrCreate(
            ChannelType channel, String externalId, String messagePreview) {
        return resolveOrCreate(channel, externalId, messagePreview, null);
    }

    /**
     * Same as {@link #resolveOrCreate(ChannelType, String, String)}, plus an optional {@code
     * resolvedPhoneNumber} (migration V51 - "still extract phone numbers are not visible"
     * follow-up). {@code externalId} MUST stay the stable routing identity - never pass a value
     * here that could differ message-to-message for the same real-world contact, or this will
     * silently fragment one conversation into multiple sessions. {@code resolvedPhoneNumber} is
     * separate, display-only, nullable, and safe to vary/omit per call - see {@link
     * ChannelSession#touch(String, String)}.
     *
     * <p><b>Live human handoff enforcement (V214) - this method is the gate.</b> When the resolved
     * session is {@link HandoffState#HUMAN} (a named operator holds the conversation), this method
     * throws {@link ConversationHeldByHumanException} AFTER recording the inbound message, which
     * stops every caller - all 10 inbound entry points call this before their pairing gate,
     * workflow-trigger check and agent dispatch - from letting the agent (or a channel-bound
     * workflow) act. See that exception's javadoc for why enforcement lives here rather than in the
     * (other-scoped) webhook controllers, and why it fails CLOSED where this codebase's optional
     * enhancements fail open.
     *
     * <p>{@code noRollbackFor} is the load-bearing half of that design: the throw must NOT roll
     * back this transaction, because the message-log append and the session {@code touch()} above
     * it are exactly what the holding operator needs to see. Without it, suppressing the agent
     * would also swallow the customer's message from the operator's own transcript - silently,
     * which is the worst version. A retried webhook delivery does not double-log: every webhook
     * path claims its provider-side message id via {@link InboundMessageDedupService} before
     * calling this method, so the retry never reaches it.
     */
    @Transactional(noRollbackFor = ConversationHeldByHumanException.class)
    public ChannelSession resolveOrCreate(
            ChannelType channel,
            String externalId,
            String messagePreview,
            String resolvedPhoneNumber) {
        // First thing, before any query or write: a message over the sender's or the channel's
        // rate cap costs one counter hit and nothing else (no session touch, no transcript row,
        // no agent turn). Throws InboundRateLimitedException, whose message is the friendly text.
        InboundRateLimiter limiter = rateLimiter;
        if (limiter != null) {
            limiter.checkOrThrow(channel, externalId);
        }
        ChannelSession session =
                sessionRepository
                        .findByChannelAndExternalId(channel, externalId)
                        .map(
                                existing -> {
                                    existing.touch(messagePreview, resolvedPhoneNumber);
                                    // Contacts reconcile (V238): a still-pending sender who has
                                    // since been added/imported as a contact gets approved and
                                    // re-pointed at their contact/group agent right here, on
                                    // their very next message - no admin action needed.
                                    if (!existing.isPaired()) {
                                        contactService
                                                .findForInbound(
                                                        channel,
                                                        externalId,
                                                        existing.getResolvedPhoneNumber())
                                                .ifPresent(
                                                        contact ->
                                                                contactService.attachAndApprove(
                                                                        contact, existing));
                                    }
                                    return existing;
                                })
                        .orElseGet(
                                () -> {
                                    // Contact-directory routing (V238): a known contact resolves
                                    // its own agent override, then its group's, then the channel
                                    // default (which may itself be unset and throw, unchanged).
                                    // A matched contact - or a channel the tenant switched to
                                    // auto-approve - starts paired; the contact IS the approval.
                                    ChannelContact contact =
                                            contactService
                                                    .findForInbound(
                                                            channel,
                                                            externalId,
                                                            resolvedPhoneNumber)
                                                    .orElse(null);
                                    UUID contactAgent =
                                            contact != null
                                                    ? contactService.effectiveAgentIdFor(contact)
                                                    : null;
                                    ChannelSession created =
                                            new ChannelSession(
                                                    channel,
                                                    externalId,
                                                    UUID.randomUUID().toString(),
                                                    contactAgent != null
                                                            ? contactAgent
                                                            : defaultAgentIdFor(channel),
                                                    messagePreview,
                                                    resolvedPhoneNumber);
                                    if (contact != null || isAutoApproveNewSenders(channel)) {
                                        created.approvePairing();
                                    }
                                    ChannelSession saved = sessionRepository.save(created);
                                    if (contact != null) {
                                        contactService.attachToSession(contact, saved);
                                    }
                                    return saved;
                                });
        // DPDP-oriented consent step: until the sender has consented (when the agent asks for it)
        // their message text is neither written to the transcript nor kept as the preview.
        boolean storeContent = mayStoreContent(session, channel, externalId);
        if (!storeContent) {
            session.clearMessagePreview();
        }
        String storedPreview = storeContent ? messagePreview : null;
        if (storedPreview != null && !storedPreview.isBlank()) {
            messageLogRepository.save(
                    new ChannelMessageLog(channel, externalId, session.getId(), storedPreview));
        }
        // V242 inbound safety scan - AFTER the transcript row above (the log
        // must record what was flagged) and BEFORE the held-by-human gate
        // below, so a HOLD-mode flag suppresses the agent on THIS message
        // too via the same ConversationHeldByHumanException path a human
        // takeover uses.
        inboundSafetyScanService.apply(session, storedPreview);
        if (session.isHeldByHuman()) {
            // Deliberately the LAST statement: the transcript row and
            // touch() above must be part of the committing transaction -
            // see this method's javadoc and ConversationHeldByHumanException.
            throw new ConversationHeldByHumanException(session);
        }
        return session;
    }

    @Transactional(readOnly = true)
    public UUID defaultAgentIdFor(ChannelType channel) {
        return defaultAgentRepository
                .findByChannel(channel)
                .map(ChannelDefaultAgent::getAgentId)
                .orElseThrow(
                        () ->
                                new ChannelConfigurationException(
                                        channel.name().toLowerCase(java.util.Locale.ROOT),
                                        "No default agent configured for inbound "
                                                + channel
                                                + " messages for this tenant - "
                                                + "PUT /api/channels/"
                                                + channel.name().toLowerCase(java.util.Locale.ROOT)
                                                + "/default-agent first."));
    }

    @Transactional
    public void setDefaultAgent(ChannelType channel, UUID agentId) {
        ChannelDefaultAgent entry =
                defaultAgentRepository
                        .findByChannel(channel)
                        .orElseGet(() -> new ChannelDefaultAgent(channel, agentId));
        entry.setAgentId(agentId);
        defaultAgentRepository.save(entry);
    }

    /**
     * /admin/contacts (V238) - the per-channel routing row as the contacts screen needs it: default
     * agent + the tenant's "does every new sender need manual approval" switch, read together so
     * one GET drives the row.
     */
    @Transactional(readOnly = true)
    public Optional<ChannelDefaultAgent> routingFor(ChannelType channel) {
        return defaultAgentRepository.findByChannel(channel);
    }

    /**
     * Upserts a channel's routing row. {@code agentId} is required when no row exists yet (the
     * column is NOT NULL and an auto-approve channel with no default agent has nowhere to route
     * anyway); on an existing row a null agentId means "keep the current agent, only flip the
     * mode". {@code inboundSafetyMode} (V242) is likewise optional: null keeps the stored mode.
     */
    @Transactional
    public ChannelDefaultAgent setRouting(
            ChannelType channel, UUID agentId, boolean autoApproveNewSenders) {
        return setRouting(channel, agentId, autoApproveNewSenders, null);
    }

    @Transactional
    public ChannelDefaultAgent setRouting(
            ChannelType channel,
            UUID agentId,
            boolean autoApproveNewSenders,
            String inboundSafetyMode) {
        ChannelDefaultAgent entry =
                defaultAgentRepository
                        .findByChannel(channel)
                        .orElseGet(
                                () -> {
                                    if (agentId == null) {
                                        throw new ChannelConfigurationException(
                                                channel.name().toLowerCase(java.util.Locale.ROOT),
                                                "Pick a default agent first - new senders need an"
                                                        + " agent to be routed to.");
                                    }
                                    return new ChannelDefaultAgent(channel, agentId);
                                });
        if (agentId != null) {
            entry.setAgentId(agentId);
        }
        entry.setAutoApproveNewSenders(autoApproveNewSenders);
        if (inboundSafetyMode != null && !inboundSafetyMode.isBlank()) {
            entry.setInboundSafetyMode(inboundSafetyMode);
        }
        return defaultAgentRepository.save(entry);
    }

    /**
     * Removes a channel's routing row. Used only to undo a routing this call chain just created
     * (the brief apply engine's compensation) - an absent row is a no-op, never an error. It does
     * not touch the channel's credentials or any existing sessions.
     */
    @Transactional
    public void clearRouting(ChannelType channel) {
        defaultAgentRepository.findByChannel(channel).ifPresent(defaultAgentRepository::delete);
    }

    /**
     * "Auto-accept new senders" for {@code resolveOrCreate}'s create path - absent routing row
     * means today's require-approval behaviour.
     */
    @Transactional(readOnly = true)
    public boolean isAutoApproveNewSenders(ChannelType channel) {
        return defaultAgentRepository
                .findByChannel(channel)
                .map(ChannelDefaultAgent::isAutoApproveNewSenders)
                .orElse(false);
    }

    /**
     * #590 follow-up ("it is selecting wrong agent for telegram message sending") - {@link
     * #setDefaultAgent} only ever affects a channel's NEXT brand-new conversation; every {@link
     * ChannelSession} that already exists keeps whichever agent it was stamped with at first
     * contact ({@link ChannelSession#rebind} is otherwise never called). This is the one explicit
     * action that re-points one specific, already-existing conversation - used from the Channels
     * screen's "RECENT INBOUND" log ({@code ChannelPairingController#rebindAgent}) when an admin
     * notices a particular chat has been stuck talking to the wrong agent.
     */
    @Transactional
    public void rebindAgent(UUID sessionId, UUID newAgentId) {
        requireSession(sessionId).rebind(newAgentId);
    }

    /**
     * Autonomy/Guardrails Strategy Stage A - marks the one-time pairing notice sent so a
     * still-unpaired sender's later messages are silently dropped instead of re-notified every time
     * (see ChannelSession's javadoc for why: an unlimited pairing-notice reply per message from an
     * unknown sender would be a free outbound-send-quota drain).
     */
    @Transactional
    public void markPairingNoticeSent(UUID sessionId) {
        sessionRepository.findById(sessionId).ifPresent(ChannelSession::markPairingNoticeSent);
    }

    private static final String AI_DISCLOSURE_PREFIX =
            "_You're chatting with an AI assistant, not a human._\n\n";

    /**
     * Autonomy/Guardrails Strategy - a code-backed answer to a concrete 2026 AI-governance
     * requirement (EU AI Act Article 50's transparency obligation, echoed in Singapore's 2026 Model
     * AI Governance Framework for Agentic AI): a person interacting with an autonomous agent must
     * be told they're talking to AI, not a human, unless that's already obvious from context. Every
     * inbound-channel controller calls this once, right before sending its FIRST reply on a
     * session, and prepends whatever it returns; every call after the first for that same session
     * returns {@code ""} - same "once per session, not once per message" posture as {@link
     * #markPairingNoticeSent}, and for the identical reason: this is a one-time notice, not a
     * running disclaimer.
     */
    @Transactional
    public String consumeAiDisclosurePrefix(UUID sessionId) {
        return sessionRepository
                .findById(sessionId)
                .filter(ChannelSession::markAiDisclosureSentIfNeeded)
                .map(s -> AI_DISCLOSURE_PREFIX)
                .orElse("");
    }

    @Transactional(readOnly = true)
    public List<ChannelSession> listPendingPairings() {
        return sessionRepository.findByPairedFalseOrderByCreatedAtDesc();
    }

    @Transactional
    public ChannelSession approvePairing(UUID sessionId) {
        ChannelSession session = requireSession(sessionId);
        session.approvePairing();
        // V238 - an approved sender is by definition a known contact; record
        // them in the directory (no-op if already there).
        contactService.recordApprovedSession(session);
        return session;
    }

    @Transactional
    public void rejectPairing(UUID sessionId) {
        sessionRepository.delete(requireSession(sessionId));
    }

    /**
     * "Unmap" an already-mapped (paired) conversation - moves it back to the pending-pairing inbox
     * with a fresh pairing code, keeping the session row and its history (unlike {@link
     * #rejectPairing}, which deletes). See {@link ChannelSession#unpair()}. Returns the session so
     * the caller can surface the new pairing code.
     */
    @Transactional
    public ChannelSession unpairSession(UUID sessionId) {
        ChannelSession session = requireSession(sessionId);
        session.unpair();
        return session;
    }

    private ChannelSession requireSession(UUID sessionId) {
        return sessionRepository
                .findById(sessionId)
                .orElseThrow(() -> new ChannelSessionNotFoundException(sessionId));
    }

    /**
     * #815 Governance follow-up (PII handling review) - GDPR Article 17 "right to erasure" for a
     * single data subject WITHIN a tenant's schema, as opposed to the tenant-level erasure
     * schema-per-tenant already gives you for free (a {@code DROP SCHEMA}). Deletes, in order:
     * every {@link ChannelMessageLog} row for this {@code (channel, externalId)}, every long-term
     * agent memory keyed to this subject across EVERY agent (see {@link
     * AgentMemoryService#forgetAllForSubject}'s javadoc for why "every agent" and not just the
     * currently-bound one), then the {@link ChannelSession} row itself. Records one masked
     * audit-log entry afterward - deliberately AFTER the deletes, not before, so the audit trail
     * reflects what actually happened rather than what was merely requested.
     *
     * <p>Does NOT touch: {@code GraphMemoryService}-extracted entities (a separate, not-yet-covered
     * store - see this method's follow-up note in {@code AI-GOVERNANCE-ASSESSMENT.md}), or any
     * {@code LlmCallLog}/ {@code ToolCallLog} rows that happen to quote this subject's messages in
     * a prompt/response (those are operational logs with their own legal-basis/retention story, not
     * this method's scope).
     *
     * @return {@code true} if a session was actually found and erased, {@code false} if this
     *     (channel, externalId) had no session (nothing to do - not an error, someone who never
     *     contacted this tenant has nothing to erase).
     */
    @Transactional
    public boolean eraseDataSubject(ChannelType channel, String externalId) {
        return sessionRepository
                .findByChannelAndExternalId(channel, externalId)
                .map(
                        session -> {
                            long messagesDeleted =
                                    messageLogRepository.deleteByChannelAndExternalId(
                                            channel, externalId);
                            AgentMemoryService memoryService = agentMemoryService.getIfAvailable();
                            int memoriesDeleted =
                                    memoryService == null
                                            ? 0
                                            : memoryService.forgetAllForSubject(externalId);
                            sessionRepository.delete(session);
                            auditLogService.record(
                                    "system:data-subject-erasure",
                                    "channel_session.erase_data_subject",
                                    "channel_session",
                                    session.getId().toString(),
                                    AuditOutcome.SUCCESS,
                                    "Erased 1 session, "
                                            + messagesDeleted
                                            + " message log row(s), "
                                            + memoriesDeleted
                                            + " agent-memory row(s) for "
                                            + channel
                                            + " subject ***"
                                            + (externalId.length() >= 4
                                                    ? externalId.substring(externalId.length() - 4)
                                                    : "***"));
                            return true;
                        })
                .orElse(false);
    }
}
