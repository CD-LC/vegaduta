package com.agenticai.core.omnichannel.whatsappqr;

import com.agenticai.core.agents.core.AgentNotFoundException;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.ConversationHeldByHumanException;
import com.agenticai.core.omnichannel.session.InboundChannelExecutor;
import com.agenticai.core.omnichannel.session.InboundMessageDedupService;
import com.agenticai.core.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What happens to a WhatsApp message once {@link WhatsAppQrReceiveService} has pulled it from the
 * bridge: dedup, session and pairing, the agent turn and the approval-gated reply. The linked-
 * device counterpart to {@code SignalInboundService}.
 *
 * <p><b>Default deny.</b> Nothing reaches the agent from a sender who is not a paired session. A
 * first-time sender gets ONE reply with a pairing code, and the agent only starts answering after
 * the owner approves that session in the console. Groups are dropped on the bridge itself (v1:
 * direct chats only) - the agent never sees them at all.
 *
 * <p>The caller has set the {@code TenantContext}.
 */
@Service
public class WhatsAppQrInboundService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppQrInboundService.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final String REQUESTED_BY_PREFIX = "system:whatsapp-qr-inbound:";
    private static final String AUDIT_ACTOR = "system:whatsapp-qr-inbound";

    private final ChannelSessionService channelSessionService;
    private final AgentChatService agentChatService;
    private final HumanApprovalGateService gateService;
    private final AuditLogService auditLogService;
    private final InboundMessageDedupService dedupService;
    private final InboundChannelExecutor inboundExecutor;

    public WhatsAppQrInboundService(
            ChannelSessionService channelSessionService,
            AgentChatService agentChatService,
            HumanApprovalGateService gateService,
            AuditLogService auditLogService,
            InboundMessageDedupService dedupService,
            InboundChannelExecutor inboundExecutor) {
        this.channelSessionService = channelSessionService;
        this.agentChatService = agentChatService;
        this.gateService = gateService;
        this.auditLogService = auditLogService;
        this.dedupService = dedupService;
        this.inboundExecutor = inboundExecutor;
    }

    /**
     * @param event one element of the bridge's events array (see {@link WhatsAppQrEnvelope#parse})
     * @return false only if the LLM step was shed because the inbound executor was at capacity; the
     *     dedup claim is released so the caller can offer the same message again.
     */
    public boolean accept(JsonNode event) {
        return WhatsAppQrEnvelope.parse(event)
                .map(this::acceptEnvelope)
                .orElse(true); // not a person-to-agent message - nothing to route
    }

    private boolean acceptEnvelope(WhatsAppQrEnvelope envelope) {
        if (!dedupService.tryClaim(ChannelType.WHATSAPP_QR, envelope.dedupKey())) {
            log.info(
                    "Ignoring duplicate WhatsApp QR message from {}",
                    WhatsAppQrAddress.mask(envelope.externalId()));
            return true;
        }
        boolean handled = false;
        try {
            handled = handle(envelope);
        } finally {
            if (!handled) {
                // Shed or failed before any side effect: let the same message be offered again.
                dedupService.release(ChannelType.WHATSAPP_QR, envelope.dedupKey());
            }
        }
        return handled;
    }

    /**
     * @return false only when no inbound slot could be had.
     */
    private boolean handle(WhatsAppQrEnvelope envelope) {
        // Admission BEFORE any side effect (session creation writes a message-log row). A message
        // that is knowably cheap (an existing unpaired conversation) takes an inline permit so
        // LLM saturation never sheds it.
        boolean cheap =
                channelSessionService
                        .peek(ChannelType.WHATSAPP_QR, envelope.externalId())
                        .map(s -> !s.isPaired())
                        .orElse(false);
        InboundChannelExecutor.Permit permit =
                cheap ? inboundExecutor.inline() : inboundExecutor.acquire("whatsapp-qr");
        if (permit == null) {
            return false;
        }
        try {
            route(permit, envelope);
            return true;
        } finally {
            permit.release(); // no-op once the LLM step took the slot
        }
    }

    private void route(InboundChannelExecutor.Permit permit, WhatsAppQrEnvelope envelope) {
        String conversation = envelope.externalId();
        log.info("Inbound WhatsApp QR message from {}", WhatsAppQrAddress.mask(conversation));
        ChannelSession session;
        try {
            session =
                    channelSessionService.resolveOrCreate(
                            ChannelType.WHATSAPP_QR,
                            conversation,
                            envelope.text(),
                            envelope.senderNumber());
        } catch (ConversationHeldByHumanException e) {
            log.info(
                    "WhatsApp QR conversation {} is held by a human; agent suppressed",
                    WhatsAppQrAddress.mask(conversation));
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
                        chatAndReply(session, envelope);
                    } catch (RuntimeException e) {
                        log.warn(
                                "Failed to process WhatsApp QR inbound message: {}",
                                e.getMessage());
                    }
                });
    }

    private void chatAndReply(ChannelSession session, WhatsAppQrEnvelope envelope) {
        String conversation = envelope.externalId();
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
                "WhatsApp QR session {} is bound to agent {} which no longer exists",
                session.getId(),
                session.getAgentId());
        auditLogService.record(
                AUDIT_ACTOR,
                "channel.whatsappqr.agent_not_found",
                "channel_session",
                session.getId().toString(),
                AuditOutcome.FAILURE,
                "WhatsApp QR conversation "
                        + WhatsAppQrAddress.mask(conversation)
                        + " is bound to agent "
                        + session.getAgentId()
                        + " which no longer exists - rebind this session to a valid agent.");
        sendThroughApprovalGate(
                conversation,
                channelSessionService.consumeAiDisclosurePrefix(session.getId())
                        + "This conversation's assigned agent is no longer available. An admin has been notified"
                        + " - please try again shortly.");
    }

    /**
     * First contact with an unknown sender: a single pairing notice, then silence until approved.
     */
    private void handleUnpaired(ChannelSession session, String conversation) {
        if (session.isPairingNoticeSent()) {
            log.info(
                    "Ignoring message from still-unpaired WhatsApp QR conversation {}",
                    WhatsAppQrAddress.mask(conversation));
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
                    MAPPER.writeValueAsString(
                            new WhatsAppQrChannelAdapter.SendPayload(to, replyText));
        } catch (Exception e) {
            log.error(
                    "Failed to serialize WhatsApp QR auto-reply payload for {}",
                    WhatsAppQrAddress.mask(to),
                    e);
            return;
        }
        gateService.evaluate(
                WhatsAppQrChannelAdapter.ACTION_TYPE,
                "Auto-reply WhatsApp QR message to " + WhatsAppQrAddress.mask(to),
                payloadJson,
                REQUESTED_BY_PREFIX + WhatsAppQrAddress.mask(to));
    }
}
