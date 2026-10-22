package com.agenticai.core.omnichannel.imessage;

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
import com.agenticai.core.omnichannel.session.UnknownSenderPolicy;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * What happens to an iMessage once its origin is trusted: the webhook token matched ({@link
 * ImessageWebhookController}) or {@link ImessagePollingService} fetched it from the tenant's own
 * BlueBubbles server. A service rather than controller code because the jobs and worker app shapes
 * run the poller without any controller.
 *
 * <p>In order: parse (own messages, reactions and non-message events are dropped), the sender
 * policy (default-deny), the per-chat rate limit, the dedup claim, admission to the bounded inbound
 * executor, then the session / pairing gate and the agent turn with an approval-gated reply. The
 * policy runs before anything is written, so a stranger who is not allowed costs no session row, no
 * transcript and no LLM call.
 *
 * <p><b>Sender policy.</b> A direct message from an allow-listed handle is served (and its session
 * is paired on the spot). From anyone else it is default-denied: with PAIR (default) the sender
 * gets a one-time pairing code and an admin approves the conversation in the Channels screen; with
 * IGNORE nothing happens at all. A group chat is served only when the tenant enabled groups AND the
 * speaker is allow-listed; a stranger in a group is ignored, never sent a pairing code, so a group
 * cannot be used to make the bridge message arbitrary people.
 *
 * <p>The caller has set the {@code TenantContext}.
 */
@Service
public class ImessageUpdateService {

    private static final Logger log = LoggerFactory.getLogger(ImessageUpdateService.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final String REQUESTED_BY_PREFIX = "system:imessage-inbound:";
    private static final String AUDIT_ACTOR = "system:imessage-inbound";

    private final ChannelSessionService channelSessionService;
    private final AgentChatService agentChatService;
    private final HumanApprovalGateService gateService;
    private final AuditLogService auditLogService;
    private final InboundMessageDedupService dedupService;
    private final InboundChannelExecutor inboundExecutor;
    private final ImessageCredentialService credentialService;
    private final ImessageRateLimiter rateLimiter;

    public ImessageUpdateService(
            ChannelSessionService channelSessionService,
            AgentChatService agentChatService,
            HumanApprovalGateService gateService,
            AuditLogService auditLogService,
            InboundMessageDedupService dedupService,
            InboundChannelExecutor inboundExecutor,
            ImessageCredentialService credentialService,
            ImessageRateLimiter rateLimiter) {
        this.channelSessionService = channelSessionService;
        this.agentChatService = agentChatService;
        this.gateService = gateService;
        this.auditLogService = auditLogService;
        this.dedupService = dedupService;
        this.inboundExecutor = inboundExecutor;
        this.credentialService = credentialService;
        this.rateLimiter = rateLimiter;
    }

    /**
     * @param event a BlueBubbles webhook event or a bare message (see {@link ImessageEventParser})
     * @return false only if the LLM step was shed because the inbound executor was at capacity; the
     *     dedup claim is released so a redelivery (or the next poll) is processed.
     */
    public boolean acceptVerified(JsonNode event) {
        Optional<ImessageEventParser.Inbound> parsed = ImessageEventParser.parse(event);
        if (parsed.isEmpty()) {
            return true;
        }
        ImessageEventParser.Inbound in = parsed.get();
        Optional<ImessageCredentialService.InboundSettings> settings =
                credentialService.inboundSettings();
        if (settings.isEmpty()) {
            return true;
        }
        boolean allowed = settings.get().allowedSenders().contains(in.sender());
        if (in.group()) {
            if (!settings.get().allowGroups() || !allowed) {
                log.debug(
                        "Ignoring iMessage group message from {} (groups off or sender not allowed)",
                        ImessageAddress.mask(in.sender()));
                return true;
            }
        } else if (!allowed && settings.get().policy() == UnknownSenderPolicy.IGNORE) {
            log.debug(
                    "Ignoring iMessage from unlisted sender {} (policy IGNORE)",
                    ImessageAddress.mask(in.sender()));
            return true;
        }
        if (!rateLimiter.tryInbound(ImessageChannelAdapter.tenantKey() + "|" + in.chatGuid())) {
            log.warn(
                    "iMessage chat {} is over the inbound rate limit - message dropped",
                    ImessageAddress.mask(in.chatGuid()));
            return true;
        }
        if (!dedupService.tryClaim(ChannelType.IMESSAGE, in.guid())) {
            log.info("Ignoring duplicate iMessage {}", ImessageAddress.mask(in.guid()));
            return true;
        }
        // Admission BEFORE any side effect (the session create writes a message-log row). An
        // existing
        // unpaired, unlisted sender only gets the cheap pairing notice, so it takes an inline
        // permit.
        boolean cheap =
                !allowed
                        && channelSessionService
                                .peek(ChannelType.IMESSAGE, in.chatGuid())
                                .map(s -> !s.isPaired())
                                .orElse(false);
        InboundChannelExecutor.Permit permit =
                cheap ? inboundExecutor.inline() : inboundExecutor.acquire("imessage");
        if (permit == null) {
            dedupService.release(ChannelType.IMESSAGE, in.guid());
            return false;
        }
        try {
            route(permit, in, allowed);
        } finally {
            permit.release(); // no-op once the LLM step took the slot
        }
        return true;
    }

    private void route(
            InboundChannelExecutor.Permit permit, ImessageEventParser.Inbound in, boolean allowed) {
        log.info(
                "Inbound iMessage in chat {} from {}",
                ImessageAddress.mask(in.chatGuid()),
                ImessageAddress.mask(in.sender()));
        ChannelSession session;
        try {
            session =
                    channelSessionService.resolveOrCreate(
                            ChannelType.IMESSAGE, in.chatGuid(), in.text());
        } catch (ConversationHeldByHumanException e) {
            log.info(
                    "iMessage chat {} is held by a human operator - agent not invoked",
                    ImessageAddress.mask(in.chatGuid()));
            return;
        }
        if (allowed && !session.isPaired()) {
            session = channelSessionService.approvePairing(session.getId());
        }
        if (!session.isPaired()) {
            handleUnpairedSender(session, in.chatGuid());
            return;
        }
        ChannelSession paired = session;
        permit.runInline(
                () -> {
                    try {
                        generateAndSendReply(paired, in.chatGuid(), in.text());
                    } catch (RuntimeException e) {
                        // The claim is kept: releasing it could double-reply if the failure came
                        // after the send.
                        log.warn("Failed to process iMessage inbound message: {}", e.getMessage());
                    }
                });
    }

    private void generateAndSendReply(ChannelSession session, String chatGuid, String text) {
        String reply;
        try {
            ChatTurnRequest chatTurn =
                    ChatTurnRequest.builder(
                                    session.getAgentId(),
                                    session.getSessionId(),
                                    session.getExternalId(),
                                    text)
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
            handleStaleAgentBinding(session, chatGuid);
            return;
        }
        if (reply != null && !reply.isBlank()) {
            sendReplyThroughApprovalGate(
                    chatGuid,
                    channelSessionService.consumeAiDisclosurePrefix(session.getId()) + reply);
        }
    }

    /**
     * The session points at an agent that was deleted: tell the sender and leave an audit trail for
     * the admin.
     */
    private void handleStaleAgentBinding(ChannelSession session, String chatGuid) {
        log.warn(
                "iMessage session {} is bound to agent {} which no longer exists",
                session.getId(),
                session.getAgentId());
        auditLogService.record(
                AUDIT_ACTOR,
                "channel.imessage.agent_not_found",
                "channel_session",
                session.getId().toString(),
                AuditOutcome.FAILURE,
                "iMessage chat "
                        + ImessageAddress.mask(chatGuid)
                        + " is bound to agent "
                        + session.getAgentId()
                        + " which no longer exists - rebind this session to a valid agent.");
        sendReplyThroughApprovalGate(
                chatGuid,
                channelSessionService.consumeAiDisclosurePrefix(session.getId())
                        + "This conversation's assigned agent is no longer available. An admin has been notified"
                        + " - please try again shortly.");
    }

    private void handleUnpairedSender(ChannelSession session, String chatGuid) {
        if (session.isPairingNoticeSent()) {
            log.info(
                    "Ignoring message from still-unpaired iMessage chat {} (already notified)",
                    ImessageAddress.mask(chatGuid));
            return;
        }
        String notice =
                "Thanks for reaching out. For security, an admin needs to approve this conversation before I can"
                        + " respond. Your pairing code is "
                        + session.getPairingCode()
                        + ".";
        sendReplyThroughApprovalGate(
                chatGuid,
                channelSessionService.consumeAiDisclosurePrefix(session.getId()) + notice);
        channelSessionService.markPairingNoticeSent(session.getId());
    }

    private void sendReplyThroughApprovalGate(String to, String replyText) {
        String payloadJson;
        try {
            payloadJson =
                    MAPPER.writeValueAsString(
                            new ImessageChannelAdapter.SendPayload(to, replyText));
        } catch (Exception e) {
            log.error(
                    "Failed to serialize iMessage auto-reply payload for {}",
                    ImessageAddress.mask(to),
                    e);
            return;
        }
        gateService.evaluate(
                ImessageChannelAdapter.ACTION_TYPE,
                "Auto-reply iMessage to " + ImessageAddress.mask(to),
                payloadJson,
                REQUESTED_BY_PREFIX + ImessageAddress.mask(to));
    }
}
