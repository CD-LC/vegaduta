package com.agenticai.core.omnichannel.telegram;

import com.agenticai.core.agents.core.AgentNotFoundException;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.eventbus.WorkflowEventTypes;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.InboundChannelExecutor;
import com.agenticai.core.omnichannel.session.InboundMessageDedupService;
import com.agenticai.core.omnichannel.trigger.InboundTriggerContext;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerChannel;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNodeService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerPort;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * What happens to a Telegram update once its origin is trusted: the webhook secret matched ({@link
 * TelegramWebhookController}), or {@link TelegramPollingService} fetched it from Telegram itself
 * with the bot token. Dedup claim, then routing (pairing notice, workflow trigger, agent turn,
 * approval-gated reply). It is a service, not part of the controller, because the jobs and worker
 * app shapes load no controllers yet still run the poller.
 *
 * <p>The caller has set the {@code TenantContext}.
 */
@Service
public class TelegramUpdateService {

    private static final Logger log = LoggerFactory.getLogger(TelegramUpdateService.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final String REQUESTED_BY_PREFIX = "system:telegram-inbound:";
    private static final String AUDIT_ACTOR = "system:telegram-inbound";

    private final ChannelSessionService channelSessionService;
    private final AgentChatService agentChatService;
    private final HumanApprovalGateService gateService;
    private final WorkflowEventPublisher workflowEventPublisher;
    private final AuditLogService auditLogService;
    private final InboundMessageDedupService dedupService;
    private final TelegramApiClient apiClient;
    private final WorkflowTriggerNodeService workflowTriggerNodeService;
    private final WorkflowTriggerPort workflowTriggerPort;
    private final InboundChannelExecutor inboundExecutor;

    public TelegramUpdateService(
            ChannelSessionService channelSessionService,
            AgentChatService agentChatService,
            HumanApprovalGateService gateService,
            WorkflowEventPublisher workflowEventPublisher,
            AuditLogService auditLogService,
            InboundMessageDedupService dedupService,
            TelegramApiClient apiClient,
            WorkflowTriggerNodeService workflowTriggerNodeService,
            WorkflowTriggerPort workflowTriggerPort,
            InboundChannelExecutor inboundExecutor) {
        this.channelSessionService = channelSessionService;
        this.agentChatService = agentChatService;
        this.gateService = gateService;
        this.workflowEventPublisher = workflowEventPublisher;
        this.auditLogService = auditLogService;
        this.dedupService = dedupService;
        this.apiClient = apiClient;
        this.workflowTriggerNodeService = workflowTriggerNodeService;
        this.workflowTriggerPort = workflowTriggerPort;
        this.inboundExecutor = inboundExecutor;
    }

    /**
     * Everything that happens to an update once its origin is trusted (the webhook secret matched,
     * or {@link TelegramPollingService} fetched it from Telegram itself with the bot token): dedup
     * claim, then routing. The caller has set the {@code TenantContext}.
     *
     * @return false only if the LLM step was shed because the inbound executor was at capacity; the
     *     dedup claim is released so a redelivery (or the next poll) is processed.
     */
    public boolean acceptVerifiedUpdate(String botUserId, JsonNode update, String botToken) {
        // Master-Roadmap-2026.md item #1, gap (b)#3: Telegram replays
        // pending updates after any webhook downtime/reset, and a
        // reachable-but-slow backend can see the same update_id twice.
        // Claimed BEFORE any processing (pairing notice, workflow
        // trigger, agent reply) so a duplicate delivery is a true no-op,
        // not just a duplicate reply.
        String updateId = update.path("update_id").asText(null);
        if (!dedupService.tryClaim(ChannelType.TELEGRAM, updateId)) {
            log.info(
                    "Ignoring duplicate Telegram update_id {} for bot ***{}",
                    updateId,
                    lastFour(botUserId));
            return true;
        }
        // Cheap steps (pairing notice, workflow trigger) run inline on this request thread and
        // take no LLM slot. Only the blocking LLM turn holds a slot of the bounded inbound
        // executor, and it runs ON this request thread, exactly as before the executor existed:
        // this keeps the per-chat ordering a caller saw (the next update for the chat is not
        // acked
        // before this turn ends) and keeps a failed turn visible as before. If no slot can be
        // had
        // even after the bounded FIFO wait, nothing has been written: the claim is released and
        // we answer 503 so Telegram redelivers the update (shed-mode=ack answers 200 and drops
        // it
        // instead). Redelivery relies on the dedup claim; see InboundChannelExecutor for the
        // Redis fail-open caveat.
        if (!handleUpdate(update, botToken)) {
            dedupService.release(ChannelType.TELEGRAM, updateId);
            return false;
        }
        return true;
    }

    /**
     * @return false only if the LLM step was shed because the inbound executor was at capacity.
     */
    private boolean handleUpdate(JsonNode update, String botToken) {
        JsonNode message = update.path("message");
        if (message.isMissingNode()) {
            // Non-message updates (edited_message, callback_query, etc.) - nothing to route yet.
            return true;
        }
        String chatId = message.path("chat").path("id").asText(null);
        String fromId = message.path("from").path("id").asText(null);
        String text = message.path("text").asText(null);
        if (chatId == null || text == null) {
            return true;
        }

        // Admission BEFORE any side effect (session create writes a message-log row): if no slot
        // can
        // be had even after the bounded wait, nothing has been written, so a redelivery repeats
        // nothing. A message that is knowably cheap without a side effect (existing unpaired
        // sender,
        // or a workflow bound to Telegram) takes an inline permit instead, so LLM saturation never
        // sheds it.
        boolean cheap =
                workflowTriggerNodeService.bindingFor(WorkflowTriggerChannel.TELEGRAM).isPresent()
                        || channelSessionService
                                .peek(ChannelType.TELEGRAM, chatId)
                                .map(s -> !s.isPaired())
                                .orElse(false);
        InboundChannelExecutor.Permit permit =
                cheap ? inboundExecutor.inline() : inboundExecutor.acquire("telegram");
        if (permit == null) {
            return false;
        }
        try {
            return routeMessage(permit, chatId, fromId, text, botToken);
        } finally {
            permit.release(); // no-op once the LLM step took the slot
        }
    }

    private boolean routeMessage(
            InboundChannelExecutor.Permit permit,
            String chatId,
            String fromId,
            String text,
            String botToken) {
        log.info(
                "Inbound Telegram message in chat {} from {}",
                maskRecipient(chatId),
                maskRecipient(fromId));
        ChannelSession session =
                channelSessionService.resolveOrCreate(ChannelType.TELEGRAM, chatId, text);

        if (!session.isPaired()) {
            handleUnpairedSender(session, chatId);
            return true;
        }

        // Channel-based trigger selection (#422), mirroring the WhatsApp
        // controllers: a workflow bound to TELEGRAM takes over from here - this
        // chat becomes an automation trigger, not a chat surface, for as long as
        // that binding exists. The chat id rides along as the reply-to context
        // so a workflow Output(channel=telegram) node with no fixed chat id
        // replies to this same chat. Fire-and-forget (see WorkflowTriggerPort's
        // javadoc); requestedBy stays the masked audit label.
        // Agent-event-trigger fix (post-blackout audit): fires unconditionally
        // now, independent of a workflow binding on this channel - see
        // AgentEventTriggerDispatcher's javadoc, "three honesty items" #1.
        workflowEventPublisher.publishNewRun(
                WorkflowEventTypes.CHANNEL_TELEGRAM_MESSAGE_RECEIVED,
                Map.of(
                        "channel",
                        "telegram",
                        "fromMasked",
                        maskRecipient(fromId),
                        "bodyLength",
                        text.length()));

        Optional<WorkflowTriggerNodeService.TriggerBinding> triggeredBinding =
                workflowTriggerNodeService.bindingFor(WorkflowTriggerChannel.TELEGRAM);
        if (triggeredBinding.isPresent()) {
            if (workflowTriggerNodeService.tryAcquireTriggerSlot(
                    WorkflowTriggerChannel.TELEGRAM, chatId)) {
                log.info(
                        "Inbound Telegram message in chat {} routed to workflow {} (channel"
                                + " trigger)",
                        maskRecipient(chatId),
                        triggeredBinding.get().workflowId());
                workflowTriggerPort.triggerAsync(
                        triggeredBinding.get().workflowId(),
                        triggeredBinding.get().nodeId(),
                        text,
                        REQUESTED_BY_PREFIX + maskRecipient(chatId),
                        new InboundTriggerContext(WorkflowTriggerChannel.TELEGRAM, chatId));
            } else {
                log.info(
                        "Inbound Telegram message in chat {} skipped - workflow trigger rate limit"
                                + " exceeded",
                        maskRecipient(chatId));
            }
            return true;
        }

        // Master-Roadmap-2026.md item #1, gap (b)#1 - fire this BEFORE the
        // (possibly several-second) agentChatService.chat() call below, not
        // after, so the sender sees "typing..." while the real reply is
        // still being generated. See TelegramApiClient#sendTypingIndicator's
        // javadoc for why this is Telegram-only (no equivalent Twilio/
        // WhatsApp API exists) and best-effort.
        // Held slot, caller's thread (see TelegramWebhookController#inbound). A stray failure is
        // logged; the claim is kept because releasing it could double-reply when the failure came
        // after the reply was sent.
        permit.runInline(
                () -> {
                    try {
                        chatAndReply(session, chatId, text, botToken);
                    } catch (RuntimeException e) {
                        log.warn("Failed to process Telegram inbound update: {}", e.getMessage());
                    }
                });
        return true;
    }

    /**
     * The slow part of an update: typing indicator, full LLM round trip, approval-gated send. Runs
     * on the request thread while holding an inbound-executor slot.
     */
    private void chatAndReply(ChannelSession session, String chatId, String text, String botToken) {
        apiClient.sendTypingIndicator(botToken, chatId);
        generateAndSendReply(session, chatId, text);
    }

    /** The LLM round trip plus the approval-gated send. */
    private void generateAndSendReply(ChannelSession session, String chatId, String text) {
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
            // Stage 4 (Chat file upload) added trailing attachmentKey/attachmentMimeType
            // params to AgentChatService.chat() - null/null here, same reasoning as
            // every other omnichannel inbound path (see DiscordWebhookController's comment).
            reply =
                    agentChatService
                            .chat(turnContext, chatTurn)
                            .collectList()
                            .map(chunks -> String.join("", chunks))
                            .block();
        } catch (AgentNotFoundException e) {
            handleStaleAgentBinding(session, chatId);
            return;
        }

        if (reply != null && !reply.isBlank()) {
            sendReplyThroughApprovalGate(
                    chatId,
                    channelSessionService.consumeAiDisclosurePrefix(session.getId()) + reply);
        }
    }

    /**
     * #592 follow-up ("wrong agent for telegram message sending" -> traced to a {@link
     * ChannelSession} frozen on a since-deleted agent). Before this, {@link AgentNotFoundException}
     * here fell through to the webhook controller's outer {@code catch (RuntimeException e)}, which
     * just logs a warning and drops the message - the sender never learns anything went wrong, and
     * nothing short of reading server logs would reveal why one specific conversation has gone
     * permanently quiet. Now the sender gets a clear notice AND it lands in the same audit trail
     * the console's Audit Log / Live Audit Tail screens already read from, so a tenant-admin can
     * actually notice this and fix it via {@code ChannelPairingController#rebindAgent}.
     */
    private void handleStaleAgentBinding(ChannelSession session, String chatId) {
        log.warn(
                "Telegram session {} is bound to agent {} which no longer exists",
                session.getId(),
                session.getAgentId());
        auditLogService.record(
                AUDIT_ACTOR,
                "channel.telegram.agent_not_found",
                "channel_session",
                session.getId().toString(),
                AuditOutcome.FAILURE,
                "Telegram chat "
                        + maskRecipient(chatId)
                        + " is bound to agent "
                        + session.getAgentId()
                        + " which no longer exists - rebind this session to a valid agent.");
        sendReplyThroughApprovalGate(
                chatId,
                channelSessionService.consumeAiDisclosurePrefix(session.getId())
                        + "This conversation's assigned agent is no longer available. An admin has"
                        + " been notified - please try again shortly.");
    }

    private void handleUnpairedSender(ChannelSession session, String chatId) {
        if (session.isPairingNoticeSent()) {
            log.info(
                    "Ignoring message from still-unpaired Telegram chat {} (already notified)",
                    maskRecipient(chatId));
            return;
        }
        String notice =
                "Thanks for reaching out. For security, an admin needs to approve this conversation"
                        + " before I can respond. Your pairing code is "
                        + session.getPairingCode()
                        + ".";
        sendReplyThroughApprovalGate(
                chatId, channelSessionService.consumeAiDisclosurePrefix(session.getId()) + notice);
        channelSessionService.markPairingNoticeSent(session.getId());
    }

    private void sendReplyThroughApprovalGate(String to, String replyText) {
        String payloadJson;
        try {
            payloadJson =
                    MAPPER.writeValueAsString(
                            new TelegramChannelAdapter.SendPayload(to, replyText));
        } catch (Exception e) {
            log.error(
                    "Failed to serialize Telegram auto-reply payload for {}", maskRecipient(to), e);
            return;
        }
        gateService.evaluate(
                TelegramChannelAdapter.ACTION_TYPE,
                "Auto-reply Telegram message to " + maskRecipient(to),
                payloadJson,
                REQUESTED_BY_PREFIX + to);
    }

    private static String maskRecipient(String to) {
        if (to == null || to.length() < 4) {
            return "***";
        }
        return "***" + to.substring(to.length() - 4);
    }

    private static String lastFour(String value) {
        if (value == null || value.length() < 4) {
            return "***";
        }
        return value.substring(value.length() - 4);
    }
}
