package com.agenticai.core.omnichannel.meta;

import com.agenticai.core.agents.core.AgentNotFoundException;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.approval.ApprovalEvaluation;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.eventbus.WorkflowEventTypes;
import com.agenticai.core.omnichannel.outbound.ChannelTextSend;
import com.agenticai.core.omnichannel.outbound.ChannelTextSends;
import com.agenticai.core.omnichannel.relay.EdgeRelayHandoff;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.InboundChannelExecutor;
import com.agenticai.core.omnichannel.trigger.InboundTriggerContext;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerChannel;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNodeService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerPort;
import com.agenticai.core.omnichannel.whatsappvoice.WhatsAppVoiceNoteService;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Everything {@link MetaWhatsAppWebhookController} does after a message has passed signature
 * validation, the dedup claim and executor admission: the workflow event and channel-trigger
 * takeover, the (async) agent reply, and every approval-gated send back to the sender (replies,
 * pairing notices, stale-agent notices). Split out of the controller so the controller keeps the
 * webhook front door (handshake, payload walk, tenant resolution, admission) and this class keeps
 * the conversation. Not a Spring bean: the controller builds it from its own constructor arguments,
 * so its constructor and bean wiring are unchanged; the setter-injected optional collaborators are
 * read through suppliers so a late {@code set...} call is still seen.
 */
final class MetaWhatsAppReplyFlow {

    private static final Logger log = LoggerFactory.getLogger(MetaWhatsAppWebhookController.class);
    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();
    static final String REQUESTED_BY_PREFIX = "system:whatsapp-inbound:";
    private static final String AUDIT_ACTOR = "system:whatsapp-inbound";

    private final ChannelSessionService channelSessionService;
    private final AgentChatService agentChatService;
    private final HumanApprovalGateService gateService;
    // WhatsApp's approval action type and SendPayload (channel.whatsapp.send - routed per tenant
    // to Meta or Twilio by that executor), owned by the channel package (ADR-0011 rule b).
    private final ChannelTextSends channelTextSends;
    private final WorkflowEventPublisher workflowEventPublisher;
    private final WorkflowTriggerNodeService workflowTriggerNodeService;
    private final WorkflowTriggerPort workflowTriggerPort;
    private final AuditLogService auditLogService;
    private final Supplier<EdgeRelayHandoff> edgeRelay;
    private final Supplier<WhatsAppVoiceNoteService> voiceNotes;

    MetaWhatsAppReplyFlow(
            ChannelSessionService channelSessionService,
            AgentChatService agentChatService,
            HumanApprovalGateService gateService,
            ChannelTextSends channelTextSends,
            WorkflowEventPublisher workflowEventPublisher,
            WorkflowTriggerNodeService workflowTriggerNodeService,
            WorkflowTriggerPort workflowTriggerPort,
            AuditLogService auditLogService,
            Supplier<EdgeRelayHandoff> edgeRelay,
            Supplier<WhatsAppVoiceNoteService> voiceNotes) {
        this.channelSessionService = channelSessionService;
        this.agentChatService = agentChatService;
        this.gateService = gateService;
        this.channelTextSends = channelTextSends;
        this.workflowEventPublisher = workflowEventPublisher;
        this.workflowTriggerNodeService = workflowTriggerNodeService;
        this.workflowTriggerPort = workflowTriggerPort;
        this.auditLogService = auditLogService;
        this.edgeRelay = edgeRelay;
        this.voiceNotes = voiceNotes;
    }

    /**
     * Everything after the pairing gate for one inbound message that now has text: the workflow
     * event, the channel-trigger takeover, and the async agent reply. Split out of {@code
     * MetaWhatsAppWebhookController#handleTextMessage} unchanged so a transcribed voice note takes
     * the very same path instead of a copy of it; {@code voiceTurn} is null for every text message,
     * which keeps that path as it was.
     *
     * @param permit the slot to run the chat step in; null when already running inside an
     *     inbound-executor slot (the voice worker): the chat step then runs right here in that slot
     *     instead of waiting for another, which at the cap would time out and lose a note whose
     *     paid transcription already happened.
     * @return false only if the executor refused the hand-off of an already-admitted reply
     */
    boolean routeInboundText(
            InboundChannelExecutor.Permit permit,
            ChannelSession session,
            String from,
            String body,
            VoiceTurn voiceTurn) {
        // Channel-based trigger selection (#422 follow-up fix) - a workflow
        // bound to WHATSAPP takes over from here entirely, same as
        // TwilioWebhookController already does for a Twilio-connected
        // number. Fire-and-forget; Meta's webhook just needs a fast 200
        // back (see MetaWhatsAppWebhookController#inbound's comment on why non-2xx is avoided).
        // Agent-event-trigger fix (post-blackout audit): fires unconditionally
        // now, independent of a workflow binding on this channel - see
        // AgentEventTriggerDispatcher's javadoc, "three honesty items" #1.
        workflowEventPublisher.publishNewRun(
                WorkflowEventTypes.CHANNEL_WHATSAPP_MESSAGE_RECEIVED,
                Map.of(
                        "channel",
                        "whatsapp",
                        "provider",
                        "meta",
                        "fromMasked",
                        maskRecipient(from),
                        "bodyLength",
                        body.length()));

        Optional<WorkflowTriggerNodeService.TriggerBinding> triggeredBinding =
                workflowTriggerNodeService.bindingFor(WorkflowTriggerChannel.WHATSAPP);
        if (triggeredBinding.isPresent()) {
            // Direct feedback: "if I approve so many whatsapp message, then
            // how do i control? every message might trigger workflow isnt
            // it?" - yes, until this check existed. See
            // WorkflowTriggerRateLimiter's javadoc for the cooldown.
            if (workflowTriggerNodeService.tryAcquireTriggerSlot(
                    WorkflowTriggerChannel.WHATSAPP, from)) {
                log.info(
                        "Inbound WhatsApp (Meta) message from {} routed to workflow {} (channel"
                                + " trigger)",
                        maskRecipient(from),
                        triggeredBinding.get().workflowId());
                // Pass the real (unmasked) sender number as the reply-to context
                // so a workflow Output(channel=whatsapp) node with no fixed number
                // replies to this sender. requestedBy stays the masked audit label.
                workflowTriggerPort.triggerAsync(
                        triggeredBinding.get().workflowId(),
                        triggeredBinding.get().nodeId(),
                        body,
                        REQUESTED_BY_PREFIX + maskRecipient(from),
                        new InboundTriggerContext(WorkflowTriggerChannel.WHATSAPP, from));
            } else {
                log.info(
                        "Inbound WhatsApp (Meta) message from {} skipped - workflow trigger rate"
                                + " limit exceeded",
                        maskRecipient(from));
            }
            return true;
        }

        // #617 - moved off this request thread; see MetaWhatsAppWebhookController's
        // class javadoc, "Async since #617" section. TenantContext is captured HERE, on
        // the request thread handleChange's try/finally already populated
        // it on, and re-applied inside the background task - identical
        // shape to TwilioWebhookController.handleInbound and
        // WorkflowTriggerPortImpl.triggerAsync.
        Runnable reply =
                com.agenticai.core.security.concurrent.ContextPropagatingExecutor.propagating(
                        () -> {
                            try {
                                generateAndSendReply(session, from, body, voiceTurn);
                            } catch (RuntimeException e) {
                                log.warn(
                                        "Async WhatsApp (Meta) reply processing failed for session"
                                                + " {}: {}",
                                        session.getId(),
                                        e.getMessage(),
                                        e);
                            }
                        });
        if (permit == null) {
            reply.run();
            return true;
        }
        if (!permit.run(reply)) {
            log.warn(
                    "Inbound executor rejected whatsapp_meta reply task for session {} (claim"
                            + " released)",
                    session.getId());
            return false;
        }
        return true;
    }

    /**
     * The actual LLM round trip plus approval-gated send - see {@code
     * TwilioWebhookController#generateAndSendReply}'s javadoc, identical shape for the direct-Meta
     * path.
     */
    private void generateAndSendReply(
            ChannelSession session, String from, String body, VoiceTurn voiceTurn) {
        String reply;
        try {
            // A voice-note turn gets ONE extra line naming the spoken language so the agent answers
            // in it;
            // a text turn sends exactly `body`, as before.
            String chatMessage =
                    voiceTurn == null
                            ? body
                            : body
                                    + "\n\n"
                                    + voiceNotes.get().languageHint(voiceTurn.languageCode());
            // Stage 4 (Chat file upload) added trailing attachmentKey/attachmentMimeType
            // params to AgentChatService.chat() - null/null here, same reasoning as
            // every other omnichannel inbound path (see DiscordWebhookController's comment).
            // Edge relay (2026-10-14): a browser relay online for WhatsApp answers with its
            // local model; otherwise (or on timeout) the hosted path answers as before.
            EdgeRelayHandoff relay = edgeRelay.get();
            Optional<String> relayed =
                    relay == null
                            ? Optional.empty()
                            : relay.answer(ChannelType.WHATSAPP, session, chatMessage);
            ChatTurnRequest chatTurn =
                    ChatTurnRequest.builder(
                                    session.getAgentId(),
                                    session.getSessionId(),
                                    session.getExternalId(),
                                    chatMessage)
                            .build();
            TurnContext turnContext =
                    TurnContext.forEntry(TenantContext.current().orElse(null), chatTurn);
            turnContext.taint().mark("inbound:whatsapp");
            reply =
                    relayed.isPresent()
                            ? relayed.get()
                            : agentChatService
                                    .chat(turnContext, chatTurn)
                                    .collectList()
                                    .map(chunks -> String.join("", chunks))
                                    .block();
        } catch (AgentNotFoundException e) {
            handleStaleAgentBinding(session, from);
            return;
        }

        if (reply != null && !reply.isBlank()) {
            boolean textSent =
                    sendReplyThroughApprovalGate(
                            from,
                            channelSessionService.consumeAiDisclosurePrefix(session.getId())
                                    + reply);
            // The spoken copy goes out only when the text was actually released by the approval
            // gate
            // (auto-approved) - a reply waiting for a human must not leak out as audio.
            if (voiceTurn != null && textSent) {
                voiceNotes
                        .get()
                        .sendVoiceReply(
                                reply, voiceTurn.languageCode(), voiceTurn.credentials(), from);
            }
        }
    }

    /**
     * #592 follow-up - see {@code TwilioWebhookController#handleStaleAgentBinding}'s javadoc,
     * identical shape for the direct-Meta path.
     */
    private void handleStaleAgentBinding(ChannelSession session, String from) {
        log.warn(
                "WhatsApp (Meta) session {} is bound to agent {} which no longer exists",
                session.getId(),
                session.getAgentId());
        auditLogService.record(
                AUDIT_ACTOR,
                "channel.whatsapp.agent_not_found",
                "channel_session",
                session.getId().toString(),
                AuditOutcome.FAILURE,
                "WhatsApp (Meta) sender "
                        + maskRecipient(from)
                        + " is bound to agent "
                        + session.getAgentId()
                        + " which no longer exists - rebind this session to a valid agent.");
        sendReplyThroughApprovalGate(
                from,
                channelSessionService.consumeAiDisclosurePrefix(session.getId())
                        + "This conversation's assigned agent is no longer available. An admin has"
                        + " been notified - please try again shortly.");
    }

    void handleUnpairedSender(ChannelSession session, String from) {
        if (session.isPairingNoticeSent()) {
            log.info(
                    "Ignoring message from still-unpaired WhatsApp (Meta) sender {} (already"
                            + " notified)",
                    maskRecipient(from));
            return;
        }
        String notice =
                "Thanks for reaching out. For security, an admin needs to approve this conversation"
                        + " before I can respond. Your pairing code is "
                        + session.getPairingCode()
                        + ".";
        sendReplyThroughApprovalGate(
                from, channelSessionService.consumeAiDisclosurePrefix(session.getId()) + notice);
        channelSessionService.markPairingNoticeSent(session.getId());
    }

    /**
     * @return true only when the gate auto-approved (released) the message; false when it is
     *     pending approval or could not be queued. Text callers ignore it.
     */
    boolean sendReplyThroughApprovalGate(String to, String replyText) {
        ChannelTextSend whatsApp = channelTextSends.forChannel(ChannelType.WHATSAPP).orElse(null);
        if (whatsApp == null) {
            log.error(
                    "No WhatsApp send path is registered - auto-reply to {} dropped",
                    maskRecipient(to));
            return false;
        }
        String payloadJson;
        try {
            payloadJson = MAPPER.writeValueAsString(whatsApp.payload(to, null, replyText));
        } catch (Exception e) {
            log.error("Failed to serialize auto-reply payload for {}", maskRecipient(to), e);
            return false;
        }
        ApprovalEvaluation evaluation =
                gateService.evaluate(
                        whatsApp.actionType(),
                        "Auto-reply WhatsApp message to " + maskRecipient(to),
                        payloadJson,
                        REQUESTED_BY_PREFIX + to);
        return evaluation != null && evaluation.approved();
    }

    /**
     * What a transcribed voice-note turn carries into the shared reply path (null for text
     * messages).
     */
    record VoiceTurn(WhatsAppVoiceNoteService.Credentials credentials, String languageCode) {}

    static String maskRecipient(String to) {
        if (to == null || to.length() < 4) {
            return "***";
        }
        return "***" + to.substring(to.length() - 4);
    }
}
