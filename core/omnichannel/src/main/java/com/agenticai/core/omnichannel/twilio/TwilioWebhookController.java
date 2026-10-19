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
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.tenancy.TenantSchemaResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * Inbound WhatsApp.
 * Outbound send ({@link WhatsAppSendController}) was already built; a real reply from Twilio
 * reaching an agent's chat session (and the agent's answer reaching the user back on WhatsApp) was
 * the missing half.
 *
 * <p>Authenticated by {@link TwilioSignatureValidator}, not a Keycloak JWT - see {@code
 * SecurityConfig}'s {@code /webhook/**} {@code permitAll()} rule and its javadoc for why: Twilio's
 * servers can't present a bearer token, so this endpoint proves the caller's identity by verifying
 * they hold the Twilio account's own auth token (HMAC over the request), the same trust boundary
 * Twilio's own webhook security model is built around.
 *
 * <p><b>Multi-tenant resolution, added as Phase 0 Stage 4 follow-up:</b> because this endpoint
 * never goes through {@code TenantContextFilter} (no JWT), {@link TenantContext} would otherwise
 * never be populated here - every schema-scoped lookup (the Twilio credential itself, the channel
 * session, the agent) would silently fall back to the {@code public} schema regardless of which
 * tenant's Twilio account actually sent the message. Fixed by resolving the tenant FIRST, from the
 * inbound payload's {@code AccountSid} via {@link TenantResolutionService}, and manually setting
 * {@link TenantContext} for the duration of the request (mirroring {@code TenantContextFilter}'s
 * own try/finally shape) before touching anything schema-scoped - including the signature
 * validation step itself, since that needs the correct tenant's Twilio auth token, not whichever
 * tenant happened to be in the {@code public} schema. This is safe despite trusting an unverified
 * {@code AccountSid} at this point: the signature check right after only succeeds if the caller
 * actually holds that specific account's real auth token, so a forged {@code AccountSid} claiming
 * to be a different tenant simply fails signature validation using that tenant's real secret.
 *
 * <p><b>The agent's reply is deliberately NOT returned as inline TwiML.</b> It's generated via
 * {@link AgentChatService}, then routed through {@link HumanApprovalGateService#evaluate} using the
 * exact same {@link TwilioWhatsAppChannelAdapter#ACTION_TYPE} executor a manual send from {@link
 * WhatsAppSendController} uses. An inline TwiML reply would bypass the approval gate entirely -
 * exactly the kind of side channel Stage 1's {@code HumanApprovalGateService} exists to prevent.
 * Practical consequence, stated honestly: if {@code channel.whatsapp.send} is configured to require
 * human approval (the Stage 1 default), an inbound WhatsApp message will NOT get an immediate
 * auto-reply - it sits as a pending {@code ApprovalRequest} until a human approves it, same as any
 * other gated send.
 *
 * <p><b>Async since #617 ("takes too much time to reply back... improving the overall
 * performance").</b> Generating the reply ({@link AgentChatService#chat}, a real LLM round trip
 * that can include several tool-calling round trips) and sending it back out both used to run
 * synchronously on THIS webhook request thread, before Twilio ever got its empty-TwiML 200 - the
 * same blocking shape {@link WorkflowTriggerPort} already avoided for channel-triggered workflows
 * (see its {@code triggerAsync} javadoc). The webhook now acks immediately once the message is
 * durably queued for a paired sender with no workflow trigger bound; the actual chat call and
 * approval-gated send happen on a background task, mirroring {@code
 * WorkflowTriggerPortImpl.triggerAsync}'s exact capture- then-restore {@link TenantContext} pattern
 * (this request thread's context is captured before the async boundary and re-applied inside the
 * background task, since a plain {@link ThreadLocal} never crosses threads on its own - the same
 * bug class {@code LlmMetricsAdvisor} and {@code TenantContextPropagatingToolCallback} both had to
 * fix the same way). Trade-off, stated honestly: Twilio now gets its 200 before the reply is
 * confirmed sent, so a failure during the background task (agent error, approval-gate/dispatch
 * failure) no longer surfaces as a failed webhook response - it's still fully visible via the
 * existing {@link AuditLogService} trail and the LLM call log, just not via Twilio's own
 * webhook-delivery retry/failure signal.
 *
 * <p><b>Untrusted-sender pairing (Autonomy/Guardrails Strategy Stage A):</b> {@link
 * ChannelSessionService#resolveOrCreate} now creates a brand-new session unpaired (see {@link
 * ChannelSession}'s javadoc). This controller checks {@link ChannelSession#isPaired()} before ever
 * calling {@link AgentChatService} - an unpaired sender gets a one-time pairing-code notice (still
 * routed through the same approval gate as any other outbound send) instead of live agent access,
 * closing the gap where any stranger who discovered a tenant's WhatsApp number previously got an
 * immediate, unvetted agent conversation.
 *
 * <p><b>Interactive Workflow Designer PRD, Phase 0:</b> a paired sender's message also publishes a
 * {@code channel.whatsapp.message.received} workflow event via {@link WorkflowEventPublisher}
 * before the existing synchronous agent-chat handling below runs. This is additive and inert by
 * default (see that class's javadoc) - it exists to prove the event backbone works end to end, not
 * to change anything about how a message is actually answered today.
 *
 * <p><b>Channel-based trigger selection (#422):</b> if a tenant has bound a workflow to the
 * WHATSAPP channel ({@code PUT /api/workflows/{id}/trigger-channel}), an inbound message from a
 * PAIRED sender runs that workflow instead of going to {@link AgentChatService} - this channel is
 * now "an automation trigger", not a chat surface, once that binding exists. Checked right after
 * pairing (an unpaired sender still only ever gets the pairing notice, workflow-bound or not - the
 * guardrail gate always comes first) and BEFORE the synchronous agent-chat call. Fire-and-forget
 * via {@link WorkflowTriggerPort#triggerAsync} - Twilio's webhook just needs a fast empty-TwiML
 * 200, nothing here waits on the workflow's result the way {@code WorkflowWebhookController} does
 * for a direct webhook trigger; any reply back to WhatsApp is the workflow's own
 * Output(channel=whatsapp) node's job (#155), not this controller's.
 */
@RestController
@RequestMapping("/webhook/twilio")
public class TwilioWebhookController {

    private static final Logger log = LoggerFactory.getLogger(TwilioWebhookController.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final String REQUESTED_BY_PREFIX = "system:whatsapp-inbound:";
    private static final String AUDIT_ACTOR = "system:whatsapp-inbound";
    private static final String EMPTY_TWIML = "<Response></Response>";

    private final TwilioSignatureValidator signatureValidator;
    private final TwilioCredentialService credentialService;
    private final ChannelSessionService channelSessionService;
    private final AgentChatService agentChatService;
    private final HumanApprovalGateService gateService;
    private final TenantResolutionService tenantResolutionService;
    private final WorkflowEventPublisher workflowEventPublisher;
    private final WorkflowTriggerNodeService workflowTriggerNodeService;
    private final WorkflowTriggerPort workflowTriggerPort;
    private final AuditLogService auditLogService;
    private final InboundMessageDedupService dedupService;
    private final InboundChannelExecutor inboundExecutor;
    private EdgeRelayHandoff edgeRelay;

    /** Edge relay (2026-10-14), optional so every existing construction site stays unchanged. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setEdgeRelay(EdgeRelayHandoff edgeRelay) {
        this.edgeRelay = edgeRelay;
    }

    public TwilioWebhookController(
            TwilioSignatureValidator signatureValidator,
            TwilioCredentialService credentialService,
            ChannelSessionService channelSessionService,
            AgentChatService agentChatService,
            HumanApprovalGateService gateService,
            TenantResolutionService tenantResolutionService,
            WorkflowEventPublisher workflowEventPublisher,
            WorkflowTriggerNodeService workflowTriggerNodeService,
            WorkflowTriggerPort workflowTriggerPort,
            AuditLogService auditLogService,
            InboundMessageDedupService dedupService,
            InboundChannelExecutor inboundExecutor) {
        this.signatureValidator = signatureValidator;
        this.credentialService = credentialService;
        this.channelSessionService = channelSessionService;
        this.agentChatService = agentChatService;
        this.gateService = gateService;
        this.tenantResolutionService = tenantResolutionService;
        this.workflowEventPublisher = workflowEventPublisher;
        this.workflowTriggerNodeService = workflowTriggerNodeService;
        this.workflowTriggerPort = workflowTriggerPort;
        this.auditLogService = auditLogService;
        this.dedupService = dedupService;
        this.inboundExecutor = inboundExecutor;
    }

    @PostMapping(value = "/whatsapp", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> inboundWhatsApp(
            @RequestParam Map<String, String> formParams, HttpServletRequest servletRequest) {
        String accountSid = formParams.getOrDefault("AccountSid", "");
        String tenantSchema =
                tenantResolutionService
                        .resolveTenantSchema(ExternalAccountProvider.TWILIO, accountSid)
                        .orElseGet(
                                () -> {
                                    log.warn(
                                            "No tenant link found for Twilio AccountSid ***{} -"
                                                + " falling back to the {} schema. This is expected"
                                                + " for a single-tenant setup where the credential"
                                                + " was activated before this link existed;"
                                                + " otherwise, this AccountSid was never activated"
                                                + " via POST /api/channels/twilio/credential.",
                                            lastFour(accountSid),
                                            TenantSchemaResolver.DEFAULT_SCHEMA);
                                    return TenantSchemaResolver.DEFAULT_SCHEMA;
                                });

        // Manually populated (and cleared, in the finally block below) -
        // TenantContextFilter never runs on /webhook/** since there is no
        // JWT here, so this request has to establish its own tenant
        // context, one level below where JWT-driven requests do it. The
        // tenant identifier set here is already a resolved schema name
        // (not a raw claim), which TenantSchemaResolver's sanitizer passes
        // through unchanged since it's already a valid schema identifier.
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
        // resolveActive() throws TwilioNotConfiguredException (mapped to 400)
        // if nobody has activated Twilio credentials yet for this tenant -
        // a webhook can't possibly be legitimate before that, since Twilio
        // wasn't told this URL until the WhatsApp Sandbox/number was
        // configured with it.
        TwilioCredentialService.DecryptedCredential credential = credentialService.resolveActive();
        signatureValidator.validateOrThrow(
                servletRequest,
                formParams,
                servletRequest.getHeader("X-Twilio-Signature"),
                credential.authToken());

        // Master-Roadmap-2026.md item #1, gap (b)#3: Twilio retries a
        // webhook delivery on anything but a fast 200 (including this
        // controller's own #617-era async processing, where a slow
        // downstream hiccup after the ack has already gone out doesn't even
        // apply here, but a slow *signature-validation* path or a transient
        // 5xx before the ack would). Claimed on MessageSid - Twilio's own
        // per-message id - BEFORE any processing (pairing notice, workflow
        // trigger, agent reply) so a retried delivery is a true no-op, not a
        // second reply. Same InboundMessageDedupService used by Telegram's
        // update_id; see that class's javadoc.
        String messageSid = formParams.get("MessageSid");
        if (!dedupService.tryClaim(ChannelType.WHATSAPP, messageSid)) {
            log.info("Ignoring duplicate Twilio WhatsApp MessageSid {}", messageSid);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(EMPTY_TWIML);
        }

        // No up-front admission: Twilio does NOT redeliver after a 5xx, so this channel must never
        // shed.
        // Cheap paths (pairing notice, workflow trigger) run inline here and take no LLM slot; only
        // the
        // blocking reply step below waits for one (bounded queue, then bounded per-tenant overflow,
        // then
        // the request thread - never a 503). See InboundChannelExecutor#acquireNeverDrop.
        return routeInbound(messageSid, formParams);
    }

    private ResponseEntity<String> routeInbound(String messageSid, Map<String, String> formParams) {
        String from = stripWhatsAppPrefix(formParams.getOrDefault("From", ""));
        String body = formParams.getOrDefault("Body", "");
        log.info("Inbound WhatsApp message from {}", maskRecipient(from));

        ChannelSession session =
                channelSessionService.resolveOrCreate(ChannelType.WHATSAPP, from, body);

        if (!session.isPaired()) {
            handleUnpairedSender(session, from);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(EMPTY_TWIML);
        }

        // Interactive Workflow Designer PRD, Phase 0 - the first real call
        // site for the event backbone. A no-op unless app.workflow-events
        // .enabled is true (see WorkflowEventPublisher's javadoc). Deliberately
        // does not carry the message body itself (only its length) - Phase 0's
        // only consumer is a logging-only proof-of-pipe (WorkflowEventLogger),
        // and there is no reason for a message's actual content to sit in a
        // Kafka topic or the outbox table before something real is built to
        // justify that.
        //
        // Agent-event-trigger fix (post-blackout audit): this now fires
        // unconditionally, BEFORE the workflow-binding check below, rather
        // than only on the no-workflow-bound path - a workflow binding
        // existing for WHATSAPP must not silently suppress this channel's
        // agent-event trigger. See AgentEventTriggerDispatcher's javadoc,
        // "three honesty items" #1.
        workflowEventPublisher.publishNewRun(
                WorkflowEventTypes.CHANNEL_WHATSAPP_MESSAGE_RECEIVED,
                Map.of(
                        "channel",
                        "whatsapp",
                        "fromMasked",
                        maskRecipient(from),
                        "bodyLength",
                        body.length()));

        // Channel-based trigger selection (#422) - a workflow bound to
        // WHATSAPP takes over from here entirely; this channel is now an
        // automation trigger, not a chat surface, for as long as that
        // binding exists. Fire-and-forget (see WorkflowTriggerPort's
        // javadoc) so Twilio still gets its fast empty-TwiML 200.
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
                        "Inbound WhatsApp message from {} routed to workflow {} (channel trigger)",
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
                        "Inbound WhatsApp message from {} skipped - workflow trigger rate limit"
                                + " exceeded",
                        maskRecipient(from));
            }
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(EMPTY_TWIML);
        }

        // #617 - moved off this request thread. See the class javadoc's
        // "Async since #617" section for the full rationale; TenantContext
        // is captured HERE (the one thread guaranteed to still have it set -
        // inboundWhatsApp's try/finally clears it the moment this method
        // returns) and re-applied inside the background task, exactly as
        // WorkflowTriggerPortImpl.triggerAsync already does for channel-
        // triggered workflows.
        InboundChannelExecutor.Permit permit = inboundExecutor.acquireNeverDrop("twilio");
        if (!permit.run(
                com.agenticai.core.security.concurrent.ContextPropagatingExecutor.propagating(
                        () -> {
                            try {
                                generateAndSendReply(session, from, body);
                            } catch (RuntimeException e) {
                                log.warn(
                                        "Async WhatsApp (Twilio) reply processing failed for"
                                                + " session {}: {}",
                                        session.getId(),
                                        e.getMessage(),
                                        e);
                            }
                        }))) {
            // Unreachable for a never-drop permit (a rejecting delegate falls back to the caller
            // thread);
            // kept as a defensive guard so a refused hand-off is visible as a failure, not a silent
            // ack.
            log.warn(
                    "Inbound executor rejected twilio reply task for session {} (claim released,"
                            + " 503)",
                    session.getId());
            dedupService.release(ChannelType.WHATSAPP, messageSid);
            return ResponseEntity.status(503)
                    .contentType(MediaType.APPLICATION_XML)
                    .body(EMPTY_TWIML);
        }

        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(EMPTY_TWIML);
    }

    /**
     * The actual LLM round trip (a real network call, possibly several tool- calling round trips
     * inside it) plus the approval-gated outbound send - runs on the background task {@link
     * #handleInbound} kicks off, never on the webhook request thread. {@code .block()} here is
     * fine: this method only ever runs on a dedicated background thread that has nothing else
     * waiting on it, unlike the request thread it used to block.
     *
     * <p>session.getExternalId() (the WhatsApp phone number) is passed as the long-term-memory
     * subject key, not session.getSessionId() - the phone number is stable across every future
     * conversation this same contact starts, where the Redis session id is not (see
     * AgentChatService.chat's javadoc). This is what makes memory actually cross-session for
     * WhatsApp contacts specifically.
     */
    private void generateAndSendReply(ChannelSession session, String from, String body) {
        String reply;
        try {
            // Stage 4 (Chat file upload) added trailing attachmentKey/attachmentMimeType
            // params to AgentChatService.chat() - null/null here, same reasoning as
            // every other omnichannel inbound path (see DiscordWebhookController's comment).
            // Edge relay (2026-10-14): a browser relay online for WhatsApp answers with its
            // local model; otherwise (or on timeout) the hosted path answers as before.
            Optional<String> relayed =
                    edgeRelay == null
                            ? Optional.empty()
                            : edgeRelay.answer(ChannelType.WHATSAPP, session, body);
            ChatTurnRequest chatTurn =
                    ChatTurnRequest.builder(
                                    session.getAgentId(),
                                    session.getSessionId(),
                                    session.getExternalId(),
                                    body)
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

    /**
     * #592 follow-up ("wrong agent for telegram message sending" -> traced to a {@link
     * ChannelSession} frozen on a since-deleted agent, same gap on every inbound-channel controller
     * that reads {@code session.getAgentId()}). Previously this fell through to {@link
     * #handleInbound}'s caller, which never even catches it - see the gap this closes: the sender
     * got nothing back and nothing was recorded anywhere. Now the sender gets a clear notice AND it
     * lands in the audit trail the console's Audit Log / Live Audit Tail screens already read from,
     * so a tenant-admin can notice and fix it via {@code ChannelPairingController#rebindAgent}.
     */
    private void handleStaleAgentBinding(ChannelSession session, String from) {
        log.warn(
                "WhatsApp (Twilio) session {} is bound to agent {} which no longer exists",
                session.getId(),
                session.getAgentId());
        auditLogService.record(
                AUDIT_ACTOR,
                "channel.whatsapp.agent_not_found",
                "channel_session",
                session.getId().toString(),
                AuditOutcome.FAILURE,
                "WhatsApp sender "
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

    /**
     * Autonomy/Guardrails Strategy Stage A. Sends the one-time pairing notice and stops -
     * deliberately never reaches {@link AgentChatService} for an unpaired sender. Subsequent
     * messages from the same still- unpaired sender are silently dropped (see {@link
     * ChannelSession}'s javadoc for why a repeat notice per message isn't sent).
     */
    private void handleUnpairedSender(ChannelSession session, String from) {
        if (session.isPairingNoticeSent()) {
            log.info(
                    "Ignoring message from still-unpaired WhatsApp sender {} (already notified)",
                    maskRecipient(from));
            return;
        }
        log.warn(
                "First contact from an unpaired WhatsApp sender {} - sending pairing instructions,"
                        + " awaiting tenant-admin approval",
                maskRecipient(from));
        String notice =
                "Thanks for reaching out. For security, an admin needs to approve this conversation"
                        + " before I can respond. Your pairing code is "
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
                            new TwilioWhatsAppChannelAdapter.SendPayload(to, replyText));
        } catch (Exception e) {
            log.error("Failed to serialize auto-reply payload for {}", maskRecipient(to), e);
            return;
        }
        gateService.evaluate(
                TwilioWhatsAppChannelAdapter.ACTION_TYPE,
                "Auto-reply WhatsApp message to " + maskRecipient(to),
                payloadJson,
                REQUESTED_BY_PREFIX + to);
    }

    private static String stripWhatsAppPrefix(String value) {
        return value.startsWith("whatsapp:") ? value.substring("whatsapp:".length()) : value;
    }

    // Same masking convention as TwilioWhatsAppChannelAdapter - never log a
    // full phone number.
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
