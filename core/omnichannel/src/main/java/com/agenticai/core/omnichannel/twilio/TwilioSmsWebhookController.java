package com.agenticai.core.omnichannel.twilio;

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
import com.agenticai.core.omnichannel.relay.EdgeRelayHandoff;
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
import com.agenticai.core.security.concurrent.ContextPropagatingExecutor;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.tenancy.TenantSchemaResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbound SMS. Originally (#422) only a workflow <i>trigger</i>: a message to the tenant's Twilio
 * number could start a bound workflow and was otherwise acknowledged and dropped, because plain SMS
 * had no chat, pairing or session model. Since 2026-10-14 it is a conversation surface as well -
 * the one channel a feature phone with no data plan can reach - built on exactly the machinery
 * {@link TwilioWebhookController} already uses for WhatsApp over the same number:
 *
 * <ol>
 *   <li>tenant resolved from {@code AccountSid}, then the Twilio signature checked with that
 *       tenant's auth token (same trust boundary, same ordering, same javadoc rationale as the
 *       WhatsApp controller);
 *   <li>the delivery claimed on {@code MessageSid} ({@link InboundMessageDedupService}) so a Twilio
 *       retry never answers twice;
 *   <li>a {@link ChannelSession} keyed {@code (SMS, phone number)} - a new sender gets the pairing
 *       notice and waits for a tenant admin, exactly like a new WhatsApp sender;
 *   <li>a workflow bound to the SMS trigger channel still takes over entirely (the #422 behaviour,
 *       unchanged in precedence);
 *   <li>otherwise the channel's default agent answers off the request thread ({@link
 *       InboundChannelExecutor#acquireNeverDrop}: Twilio does not redeliver after a 5xx, so this
 *       path never sheds) and the reply goes through the approval gate as {@link
 *       TwilioSmsChannelAdapter#ACTION_TYPE} - never as inline TwiML, which would bypass the gate.
 * </ol>
 *
 * <p><b>What is different for SMS.</b> There is no rich text, no typing indicator and a hard body
 * limit the operator pays for per segment. The agent is told so in a one-line hint appended to the
 * turn (the same single-line, single-turn technique {@code WhatsAppVoiceNoteService} uses for the
 * spoken language), and {@link TwilioSmsChannelAdapter} cuts anything that still overflows. The raw
 * inbound text, not the hinted one, is what the session log and the conversation inbox show.
 */
@RestController
@RequestMapping("/webhook/twilio")
public class TwilioSmsWebhookController {

    private static final Logger log = LoggerFactory.getLogger(TwilioSmsWebhookController.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final String REQUESTED_BY_PREFIX = "system:sms-inbound:";
    private static final String AUDIT_ACTOR = "system:sms-inbound";
    private static final String EMPTY_TWIML = "<Response></Response>";

    /**
     * Appended to the user message of an SMS turn (and only there). One line, one turn: a
     * system-prompt directive would cost every channel on every turn, and the platform has no
     * per-turn channel hint.
     */
    static final String SMS_TURN_HINT =
            "[This message arrived by SMS. Reply in plain text only - no markdown, no lists, no"
                    + " links unless asked - and keep it short, ideally under 300 characters: the"
                    + " reader pays per 160-character segment and sees it on a small screen.]";

    private final TwilioSignatureValidator signatureValidator;
    private final TwilioCredentialService credentialService;
    private final TenantResolutionService tenantResolutionService;
    private final WorkflowTriggerNodeService workflowTriggerNodeService;
    private final WorkflowTriggerPort workflowTriggerPort;
    private final InboundMessageDedupService dedupService;
    private final ChannelSessionService channelSessionService;
    private final AgentChatService agentChatService;
    private final HumanApprovalGateService gateService;
    private final WorkflowEventPublisher workflowEventPublisher;
    private final AuditLogService auditLogService;
    private final InboundChannelExecutor inboundExecutor;
    private EdgeRelayHandoff edgeRelay;

    /** Edge relay (2026-10-14), optional so every existing construction site stays unchanged. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setEdgeRelay(EdgeRelayHandoff edgeRelay) {
        this.edgeRelay = edgeRelay;
    }

    public TwilioSmsWebhookController(
            TwilioSignatureValidator signatureValidator,
            TwilioCredentialService credentialService,
            TenantResolutionService tenantResolutionService,
            WorkflowTriggerNodeService workflowTriggerNodeService,
            WorkflowTriggerPort workflowTriggerPort,
            InboundMessageDedupService dedupService,
            ChannelSessionService channelSessionService,
            AgentChatService agentChatService,
            HumanApprovalGateService gateService,
            WorkflowEventPublisher workflowEventPublisher,
            AuditLogService auditLogService,
            InboundChannelExecutor inboundExecutor) {
        this.signatureValidator = signatureValidator;
        this.credentialService = credentialService;
        this.tenantResolutionService = tenantResolutionService;
        this.workflowTriggerNodeService = workflowTriggerNodeService;
        this.workflowTriggerPort = workflowTriggerPort;
        this.dedupService = dedupService;
        this.channelSessionService = channelSessionService;
        this.agentChatService = agentChatService;
        this.gateService = gateService;
        this.workflowEventPublisher = workflowEventPublisher;
        this.auditLogService = auditLogService;
        this.inboundExecutor = inboundExecutor;
    }

    @PostMapping(value = "/sms", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> inboundSms(
            @RequestParam Map<String, String> formParams, HttpServletRequest servletRequest) {
        String accountSid = formParams.getOrDefault("AccountSid", "");
        String tenantSchema =
                tenantResolutionService
                        .resolveTenantSchema(ExternalAccountProvider.TWILIO, accountSid)
                        .orElseGet(
                                () -> {
                                    log.warn(
                                            "No tenant link found for Twilio AccountSid ***{}"
                                                    + " (inbound SMS) - falling back to the {} schema.",
                                            lastFour(accountSid),
                                            TenantSchemaResolver.DEFAULT_SCHEMA);
                                    return TenantSchemaResolver.DEFAULT_SCHEMA;
                                });

        TenantContext.set(
                TenantContext.of(tenantSchema, REQUESTED_BY_PREFIX + accountSid, Set.of()));
        try {
            return handleInbound(formParams, servletRequest);
        } finally {
            TenantContext.clear();
        }
    }

    private ResponseEntity<String> handleInbound(
            Map<String, String> formParams, HttpServletRequest servletRequest) {
        TwilioCredentialService.DecryptedCredential credential = credentialService.resolveActive();
        signatureValidator.validateOrThrow(
                servletRequest,
                formParams,
                servletRequest.getHeader("X-Twilio-Signature"),
                credential.authToken());

        // Claimed on MessageSid BEFORE any side effect (pairing notice, trigger,
        // reply) so a Twilio retry is a true no-op. Now a real ChannelType scope.
        String messageSid = formParams.get("MessageSid");
        if (!dedupService.tryClaim(ChannelType.SMS, messageSid)) {
            log.info("Ignoring duplicate Twilio SMS MessageSid {}", messageSid);
            return ok();
        }
        return routeInbound(messageSid, formParams);
    }

    private ResponseEntity<String> routeInbound(String messageSid, Map<String, String> formParams) {
        String from = formParams.getOrDefault("From", "");
        String body = formParams.getOrDefault("Body", "");
        log.info("Inbound SMS from {}", maskRecipient(from));

        ChannelSession session = channelSessionService.resolveOrCreate(ChannelType.SMS, from, body);

        if (!session.isPaired()) {
            handleUnpairedSender(session, from);
            return ok();
        }

        // Agent-event trigger, fired before the workflow-binding check so a
        // binding never suppresses it (same ordering as every other channel).
        workflowEventPublisher.publishNewRun(
                WorkflowEventTypes.CHANNEL_SMS_MESSAGE_RECEIVED,
                Map.of(
                        "channel", "sms",
                        "fromMasked", maskRecipient(from),
                        "bodyLength", body.length()));

        // #422 - a workflow bound to the SMS channel takes over; the channel is
        // an automation trigger, not a chat surface, while the binding exists.
        Optional<WorkflowTriggerNodeService.TriggerBinding> triggeredBinding =
                workflowTriggerNodeService.bindingFor(WorkflowTriggerChannel.SMS);
        if (triggeredBinding.isPresent()) {
            if (workflowTriggerNodeService.tryAcquireTriggerSlot(
                    WorkflowTriggerChannel.SMS, from)) {
                log.info(
                        "Inbound SMS from {} routed to workflow {} (channel trigger)",
                        maskRecipient(from),
                        triggeredBinding.get().workflowId());
                workflowTriggerPort.triggerAsync(
                        triggeredBinding.get().workflowId(),
                        triggeredBinding.get().nodeId(),
                        body,
                        REQUESTED_BY_PREFIX + maskRecipient(from),
                        new InboundTriggerContext(WorkflowTriggerChannel.SMS, from));
            } else {
                log.info(
                        "Inbound SMS from {} skipped - workflow trigger rate limit exceeded",
                        maskRecipient(from));
            }
            return ok();
        }

        // The LLM round trip runs off the request thread; TenantContext is
        // captured here, on the one thread that still has it, and re-applied in
        // the task (ContextPropagatingExecutor), as every inbound channel does.
        InboundChannelExecutor.Permit permit = inboundExecutor.acquireNeverDrop("twilio-sms");
        if (!permit.run(
                ContextPropagatingExecutor.propagating(
                        () -> {
                            try {
                                generateAndSendReply(session, from, body);
                            } catch (RuntimeException e) {
                                log.warn(
                                        "Async SMS reply processing failed for session {}: {}",
                                        session.getId(),
                                        e.getMessage(),
                                        e);
                            }
                        }))) {
            log.warn(
                    "Inbound executor rejected SMS reply task for session {} (claim released, 503)",
                    session.getId());
            dedupService.release(ChannelType.SMS, messageSid);
            return ResponseEntity.status(503)
                    .contentType(MediaType.APPLICATION_XML)
                    .body(EMPTY_TWIML);
        }
        return ok();
    }

    /**
     * The phone number is the long-term-memory subject key (stable across conversations), as for
     * WhatsApp and voice. The hint is appended to the text sent to the agent only; the session log
     * already holds the raw text.
     */
    private void generateAndSendReply(ChannelSession session, String from, String body) {
        String reply;
        String chatMessage = body + "\n\n" + SMS_TURN_HINT;
        try {
            // Edge relay: a browser relay online for SMS answers with its local model;
            // otherwise (or on timeout) the hosted path below answers as before.
            Optional<String> relayed =
                    edgeRelay == null
                            ? Optional.empty()
                            : edgeRelay.answer(ChannelType.SMS, session, chatMessage);
            ChatTurnRequest chatTurn =
                    ChatTurnRequest.builder(
                                    session.getAgentId(),
                                    session.getSessionId(),
                                    session.getExternalId(),
                                    chatMessage)
                            .build();
            TurnContext turnContext =
                    TurnContext.forEntry(TenantContext.current().orElse(null), chatTurn);
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
            sendReplyThroughApprovalGate(
                    from, channelSessionService.consumeAiDisclosurePrefix(session.getId()) + reply);
        }
    }

    private void handleStaleAgentBinding(ChannelSession session, String from) {
        log.warn(
                "SMS session {} is bound to agent {} which no longer exists",
                session.getId(),
                session.getAgentId());
        auditLogService.record(
                AUDIT_ACTOR,
                "channel.sms.agent_not_found",
                "channel_session",
                session.getId().toString(),
                AuditOutcome.FAILURE,
                "SMS sender "
                        + maskRecipient(from)
                        + " is bound to agent "
                        + session.getAgentId()
                        + " which no longer exists - rebind this session to a valid agent.");
        sendReplyThroughApprovalGate(
                from,
                channelSessionService.consumeAiDisclosurePrefix(session.getId())
                        + "This number's assistant is unavailable right now. An admin has been"
                        + " notified - please try again later.");
    }

    /** One pairing notice per unpaired sender, then silence until an admin approves. */
    private void handleUnpairedSender(ChannelSession session, String from) {
        if (session.isPairingNoticeSent()) {
            log.info(
                    "Ignoring SMS from still-unpaired sender {} (already notified)",
                    maskRecipient(from));
            return;
        }
        log.warn(
                "First SMS from an unpaired sender {} - sending pairing instructions, awaiting"
                        + " tenant-admin approval",
                maskRecipient(from));
        // Short on purpose: one segment, no markdown.
        String notice =
                "Thanks for your message. An admin must approve this conversation before I can"
                        + " reply. Your pairing code is "
                        + session.getPairingCode()
                        + ".";
        sendReplyThroughApprovalGate(
                from, channelSessionService.consumeAiDisclosurePrefix(session.getId()) + notice);
        channelSessionService.markPairingNoticeSent(session.getId());
    }

    private void sendReplyThroughApprovalGate(String to, String replyText) {
        String payloadJson;
        try {
            payloadJson =
                    MAPPER.writeValueAsString(
                            new TwilioSmsChannelAdapter.SendPayload(to, replyText));
        } catch (Exception e) {
            log.error("Failed to serialize SMS auto-reply payload for {}", maskRecipient(to), e);
            return;
        }
        gateService.evaluate(
                TwilioSmsChannelAdapter.ACTION_TYPE,
                "Auto-reply SMS to " + maskRecipient(to),
                payloadJson,
                REQUESTED_BY_PREFIX + to);
    }

    private static ResponseEntity<String> ok() {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(EMPTY_TWIML);
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
