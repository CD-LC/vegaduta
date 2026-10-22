package com.agenticai.core.omnichannel.signal;

import com.agenticai.core.agents.core.AgentNotFoundException;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.omnichannel.contact.ChannelContactService;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.ConversationHeldByHumanException;
import com.agenticai.core.omnichannel.session.InboundChannelExecutor;
import com.agenticai.core.omnichannel.session.InboundMessageDedupService;
import com.agenticai.core.omnichannel.session.UnknownSenderPolicy;
import com.agenticai.core.security.TenantContext;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What happens to a Signal message once {@link SignalReceiveService} has pulled it from signal-cli:
 * filtering (group policy, unknown senders), dedup, session and pairing, the agent turn and the
 * approval-gated reply. The Signal counterpart to {@code TelegramUpdateService}.
 *
 * <p><b>Default deny.</b> Nothing reaches the agent from a sender who is not a paired session. A
 * first-time sender gets ONE reply with a pairing code (unless the tenant chose {@link
 * UnknownSenderPolicy#IGNORE}, which says nothing at all), and the agent only starts answering
 * after the owner approves that session in the console. Groups are ignored unless the tenant opts
 * in, and an opted-in group is itself a session the owner must approve.
 *
 * <p>The caller has set the {@code TenantContext}.
 */
@Service
public class SignalInboundService {

    private static final Logger log = LoggerFactory.getLogger(SignalInboundService.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final String REQUESTED_BY_PREFIX = "system:signal-inbound:";
    private static final String AUDIT_ACTOR = "system:signal-inbound";

    private final ChannelSessionService channelSessionService;
    private final ChannelContactService contactService;
    private final AgentChatService agentChatService;
    private final HumanApprovalGateService gateService;
    private final AuditLogService auditLogService;
    private final InboundMessageDedupService dedupService;
    private final SignalApiClient apiClient;
    private final InboundChannelExecutor inboundExecutor;

    public SignalInboundService(
            ChannelSessionService channelSessionService,
            ChannelContactService contactService,
            AgentChatService agentChatService,
            HumanApprovalGateService gateService,
            AuditLogService auditLogService,
            InboundMessageDedupService dedupService,
            SignalApiClient apiClient,
            InboundChannelExecutor inboundExecutor) {
        this.channelSessionService = channelSessionService;
        this.contactService = contactService;
        this.agentChatService = agentChatService;
        this.gateService = gateService;
        this.auditLogService = auditLogService;
        this.dedupService = dedupService;
        this.apiClient = apiClient;
        this.inboundExecutor = inboundExecutor;
    }

    /**
     * @param message one element of signal-cli's receive output (see {@link SignalEnvelope#parse})
     * @return false only if the LLM step was shed because the inbound executor was at capacity; the
     *     dedup claim is released so the caller can offer the same message again.
     */
    public boolean accept(SignalConnection connection, JsonNode message) {
        Optional<SignalEnvelope> parsed = SignalEnvelope.parse(message, connection.accountNumber());
        if (parsed.isEmpty()) {
            return true; // receipt, typing, reaction, own echo, empty: nothing to route
        }
        SignalEnvelope envelope = parsed.get();
        if (envelope.isGroup() && !groupAllowed(connection, envelope)) {
            return true;
        }
        if (connection.unknownSenders() == UnknownSenderPolicy.IGNORE && !known(envelope)) {
            log.debug(
                    "Ignoring Signal message from unknown {}",
                    SignalAddress.mask(envelope.conversationId()));
            return true;
        }
        if (!dedupService.tryClaim(ChannelType.SIGNAL, envelope.dedupKey())) {
            log.info(
                    "Ignoring duplicate Signal message from {}",
                    SignalAddress.mask(envelope.conversationId()));
            return true;
        }
        boolean handled = false;
        try {
            handled = handle(connection, envelope);
        } finally {
            if (!handled) {
                // Shed or failed before any side effect: let the same message be offered again.
                dedupService.release(ChannelType.SIGNAL, envelope.dedupKey());
            }
        }
        return handled;
    }

    private static boolean groupAllowed(SignalConnection connection, SignalEnvelope envelope) {
        return switch (connection.groupMode()) {
            case IGNORE -> false;
            case MENTION -> envelope.addressedToAccount();
            case ALL -> true;
        };
    }

    /**
     * A conversation the platform already knows: a session, a contact, or a channel set to
     * auto-approve.
     */
    private boolean known(SignalEnvelope envelope) {
        String conversation = envelope.conversationId();
        return channelSessionService.peek(ChannelType.SIGNAL, conversation).isPresent()
                || channelSessionService.isAutoApproveNewSenders(ChannelType.SIGNAL)
                // Contacts are people; a group id is never matched against their numbers.
                || (!envelope.isGroup()
                        && contactService
                                .findForInbound(
                                        ChannelType.SIGNAL, conversation, envelope.senderNumber())
                                .isPresent());
    }

    /**
     * @return false only when no inbound slot could be had.
     */
    private boolean handle(SignalConnection connection, SignalEnvelope envelope) {
        String conversation = envelope.conversationId();
        // Admission BEFORE any side effect (session creation writes a message-log row). A message
        // that is knowably cheap (an existing unpaired conversation) takes an inline permit so
        // LLM saturation never sheds it.
        boolean cheap =
                channelSessionService
                        .peek(ChannelType.SIGNAL, conversation)
                        .map(s -> !s.isPaired())
                        .orElse(false);
        InboundChannelExecutor.Permit permit =
                cheap ? inboundExecutor.inline() : inboundExecutor.acquire("signal");
        if (permit == null) {
            return false;
        }
        try {
            route(permit, connection, envelope);
            return true;
        } finally {
            permit.release(); // no-op once the LLM step took the slot
        }
    }

    private void route(
            InboundChannelExecutor.Permit permit,
            SignalConnection connection,
            SignalEnvelope envelope) {
        String conversation = envelope.conversationId();
        log.info(
                "Inbound Signal message in {} from {}",
                SignalAddress.mask(conversation),
                SignalAddress.mask(envelope.senderId()));
        ChannelSession session;
        try {
            session =
                    channelSessionService.resolveOrCreate(
                            ChannelType.SIGNAL,
                            conversation,
                            envelope.text(),
                            envelope.isGroup() ? null : envelope.senderNumber());
        } catch (ConversationHeldByHumanException e) {
            log.info(
                    "Signal conversation {} is held by a human; agent suppressed",
                    SignalAddress.mask(conversation));
            return;
        }
        if (!session.isPaired()) {
            handleUnpaired(session, conversation);
            return;
        }
        // Held slot, caller's thread: keeps per-conversation order. A stray failure is logged and
        // the claim is kept, because releasing it could double-reply when the failure came after
        // the reply was sent.
        permit.runInline(
                () -> {
                    try {
                        chatAndReply(connection, session, envelope);
                    } catch (RuntimeException e) {
                        log.warn("Failed to process Signal inbound message: {}", e.getMessage());
                    }
                });
    }

    private void chatAndReply(
            SignalConnection connection, ChannelSession session, SignalEnvelope envelope) {
        String conversation = envelope.conversationId();
        try {
            apiClient.sendTypingIndicator(connection, SignalAddress.toRecipient(conversation));
        } catch (RuntimeException e) {
            // cosmetic only
        }
        String reply;
        try {
            ChatTurnRequest chatTurn =
                    ChatTurnRequest.builder(
                                    session.getAgentId(),
                                    session.getSessionId(),
                                    session.getExternalId(),
                                    envelope.text())
                            .build();
            TurnContext turnContext =
                    TurnContext.forEntry(TenantContext.current().orElse(null), chatTurn);
            reply =
                    agentChatService
                            .chat(turnContext, chatTurn)
                            .collectList()
                            .map(chunks -> String.join("", chunks))
                            .block();
        } catch (AgentNotFoundException e) {
            handleStaleAgentBinding(session, conversation);
            return;
        }
        if (reply != null && !reply.isBlank()) {
            sendThroughApprovalGate(
                    conversation,
                    channelSessionService.consumeAiDisclosurePrefix(session.getId()) + reply);
        }
    }

    /**
     * The session points at a since-deleted agent: tell the sender and leave an audit trail for the
     * admin.
     */
    private void handleStaleAgentBinding(ChannelSession session, String conversation) {
        log.warn(
                "Signal session {} is bound to agent {} which no longer exists",
                session.getId(),
                session.getAgentId());
        auditLogService.record(
                AUDIT_ACTOR,
                "channel.signal.agent_not_found",
                "channel_session",
                session.getId().toString(),
                AuditOutcome.FAILURE,
                "Signal conversation "
                        + SignalAddress.mask(conversation)
                        + " is bound to agent "
                        + session.getAgentId()
                        + " which no longer exists - rebind this session to a valid agent.");
        sendThroughApprovalGate(
                conversation,
                channelSessionService.consumeAiDisclosurePrefix(session.getId())
                        + "This conversation's assigned agent is no longer available. An admin has been notified"
                        + " - please try again shortly.");
    }

    private void handleUnpaired(ChannelSession session, String conversation) {
        if (session.isPairingNoticeSent()) {
            log.info(
                    "Ignoring message from still-unpaired Signal conversation {}",
                    SignalAddress.mask(conversation));
            return;
        }
        String notice =
                "Thanks for reaching out. For security, an admin needs to approve this conversation"
                        + " before I can respond. Your pairing code is "
                        + session.getPairingCode()
                        + ".";
        sendThroughApprovalGate(
                conversation,
                channelSessionService.consumeAiDisclosurePrefix(session.getId()) + notice);
        channelSessionService.markPairingNoticeSent(session.getId());
    }

    private void sendThroughApprovalGate(String to, String replyText) {
        String payloadJson;
        try {
            payloadJson =
                    MAPPER.writeValueAsString(new SignalChannelAdapter.SendPayload(to, replyText));
        } catch (Exception e) {
            log.error(
                    "Failed to serialize Signal auto-reply payload for {}",
                    SignalAddress.mask(to),
                    e);
            return;
        }
        gateService.evaluate(
                SignalChannelAdapter.ACTION_TYPE,
                "Auto-reply Signal message to " + SignalAddress.mask(to),
                payloadJson,
                REQUESTED_BY_PREFIX + SignalAddress.mask(to));
    }
}
