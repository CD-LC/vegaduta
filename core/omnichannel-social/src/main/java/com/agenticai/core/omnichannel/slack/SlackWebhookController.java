package com.agenticai.core.omnichannel.slack;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.eventbus.WorkflowEventTypes;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.omnichannel.session.AsyncInboundReply;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.InboundMessageDedupService;
import com.agenticai.core.omnichannel.trigger.InboundTriggerContext;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerChannel;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNodeService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerPort;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound Slack Events API - structurally different from the Meta/Twilio webhooks in two ways worth
 * calling out:
 *
 * <ul>
 *   <li><b>One-time verification is a POST with a JSON body</b> ({@code {"type":
 *       "url_verification", "challenge": "..."}}, echoed back as plain text) rather than a GET with
 *       query params - Slack's own handshake design, handled inline in {@link #inbound} rather than
 *       a separate endpoint.
 *   <li><b>Self-message filtering is required</b>: Slack's Events API delivers every message in a
 *       conversation the bot is part of, including ones the bot itself just sent - {@link
 *       #handleEvent} drops any event whose {@code user} matches this tenant's own {@code
 *       botUserId} (or that carries a {@code bot_id} at all) before it ever reaches the pairing
 *       gate, or the bot would reply to its own replies forever.
 * </ul>
 *
 * <p>Everything else - resolve tenant from an unverified-but-self-defeating- to-forge {@code
 * team_id}, then verify with that tenant's own Signing Secret via {@link SlackSignatureValidator},
 * then the same pairing- gate/approval-gated-reply/workflow-event shape every other channel here
 * uses - mirrors {@code MetaWhatsAppWebhookController} closely.
 */
@RestController
@ConditionalOnEditionFeature(EditionFeature.SOCIAL_CHANNELS)
@RequestMapping("/webhook/slack")
public class SlackWebhookController {

    private static final Logger log = LoggerFactory.getLogger(SlackWebhookController.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final String REQUESTED_BY_PREFIX = "system:slack-inbound:";
    private static final int MAX_CHALLENGE_LENGTH = 256;
    private static final Pattern CHALLENGE = Pattern.compile("[A-Za-z0-9._-]+");

    private final SlackSignatureValidator signatureValidator;
    private final SlackCredentialService credentialService;
    private final ChannelSessionService channelSessionService;
    private final AgentChatService agentChatService;
    private final HumanApprovalGateService gateService;
    private final TenantResolutionService tenantResolutionService;
    private final WorkflowEventPublisher workflowEventPublisher;
    private final WorkflowTriggerNodeService workflowTriggerNodeService;
    private final WorkflowTriggerPort workflowTriggerPort;
    private final InboundMessageDedupService dedupService;
    private final AsyncInboundReply asyncReplies;

    public SlackWebhookController(
            SlackSignatureValidator signatureValidator,
            SlackCredentialService credentialService,
            ChannelSessionService channelSessionService,
            AgentChatService agentChatService,
            HumanApprovalGateService gateService,
            TenantResolutionService tenantResolutionService,
            WorkflowEventPublisher workflowEventPublisher,
            WorkflowTriggerNodeService workflowTriggerNodeService,
            WorkflowTriggerPort workflowTriggerPort,
            InboundMessageDedupService dedupService,
            AsyncInboundReply asyncReplies) {
        this.signatureValidator = signatureValidator;
        this.credentialService = credentialService;
        this.channelSessionService = channelSessionService;
        this.agentChatService = agentChatService;
        this.gateService = gateService;
        this.tenantResolutionService = tenantResolutionService;
        this.workflowEventPublisher = workflowEventPublisher;
        this.workflowTriggerNodeService = workflowTriggerNodeService;
        this.workflowTriggerPort = workflowTriggerPort;
        this.dedupService = dedupService;
        this.asyncReplies = asyncReplies;
    }

    @PostMapping
    public ResponseEntity<String> inbound(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Slack-Request-Timestamp", required = false)
                    String timestampHeader,
            @RequestHeader(value = "X-Slack-Signature", required = false) String signatureHeader) {
        JsonNode root;
        try {
            root = MAPPER.readTree(rawBody);
        } catch (Exception e) {
            log.warn("Could not parse inbound Slack webhook body as JSON", e);
            return ResponseEntity.ok().build();
        }

        // Slack's Request URL handshake carries {token, challenge, type} and NO team_id, and it
        // arrives before any tenant has a Signing Secret to check it against, so it cannot be
        // tenant-resolved or signature-verified. It changes no state and only echoes the
        // challenge back (Slack documents it as exactly that); everything else below still
        // resolves the tenant and verifies the signature first.
        if ("url_verification".equals(root.path("type").asText())) {
            return urlVerificationResponse(root.path("challenge"));
        }

        String teamId = root.path("team_id").asText(null);
        if (teamId == null) {
            log.info(
                    "Ignoring Slack webhook payload with no team_id (type={})",
                    root.path("type").asText());
            return ResponseEntity.ok().build();
        }

        Optional<String> tenantSchema =
                tenantResolutionService.resolveTenantSchema(ExternalAccountProvider.SLACK, teamId);
        if (tenantSchema.isEmpty()) {
            log.warn("No tenant link found for Slack team ***{} - ignoring.", lastFour(teamId));
            return ResponseEntity.ok().build();
        }

        TenantContext.set(
                TenantContext.of(tenantSchema.get(), REQUESTED_BY_PREFIX + teamId, Set.of()));
        try {
            SlackCredentialService.DecryptedCredential credential =
                    credentialService.resolveActive();
            signatureValidator.validateOrThrow(
                    rawBody, timestampHeader, signatureHeader, credential.signingSecret());

            if ("event_callback".equals(root.path("type").asText())) {
                // Master-Roadmap-2026.md item #1, gap (b)#3 - the same fix
                // every other inbound controller here carries. Slack retries
                // a delivery (tagged X-Slack-Retry-Num: 1..3) whenever the
                // endpoint answers non-2xx OR takes longer than ~3 seconds -
                // the latter makes Slack the channel MOST likely to actually
                // double-deliver, since a slow downstream (agent chat used to
                // run on this request thread; workflow triggers still can)
                // trips the retry even though processing succeeded. Claimed
                // on the envelope's event_id - Slack's stable per-event id,
                // identical across retries of the same delivery - rather
                // than client_msg_id, which only exists on user-typed
                // message events (bot/system/subtype events lack it) and is
                // absent from other event types a future handler might care
                // about. Claimed AFTER signature validation (an
                // unauthenticated caller must not be able to burn a real
                // event's dedup key) and only for event_callback envelopes -
                // url_verification has no event_id and must always be
                // answered. A duplicate still gets the same fast 200 as a
                // success, which is exactly what stops Slack retrying.
                if (!dedupService.tryClaim(ChannelType.SLACK, root.path("event_id").asText(null))) {
                    log.info(
                            "Ignoring duplicate Slack event id {} for team ***{}",
                            root.path("event_id").asText(),
                            lastFour(teamId));
                    return ResponseEntity.ok().build();
                }
                handleEvent(credential, root.path("event"));
            }
        } catch (RuntimeException e) {
            log.warn("Failed to process Slack webhook payload: {}", e.getMessage());
        } finally {
            TenantContext.clear();
        }
        return ResponseEntity.ok().build();
    }

    /**
     * Echoes Slack's handshake challenge only when it looks like one (a short token), so this
     * unauthenticated endpoint cannot be used to reflect arbitrary content.
     */
    private static ResponseEntity<String> urlVerificationResponse(JsonNode challenge) {
        String value = challenge.isTextual() ? challenge.asText() : "";
        if (value.isEmpty()
                || value.length() > MAX_CHALLENGE_LENGTH
                || !CHALLENGE.matcher(value).matches()) {
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(value);
    }

    private void handleEvent(
            SlackCredentialService.DecryptedCredential credential, JsonNode event) {
        if (!"message".equals(event.path("type").asText())) {
            return;
        }
        // Drop the bot's own messages (see class javadoc) and any message
        // subtype (edits, deletes, join/leave notices) - only plain new
        // messages from a human are worth an agent reply.
        if (event.has("bot_id") || !event.path("subtype").isMissingNode()) {
            return;
        }
        String senderId = event.path("user").asText(null);
        String channelId = event.path("channel").asText(null);
        String text = event.path("text").asText(null);
        if (senderId == null
                || channelId == null
                || text == null
                || senderId.equals(credential.botUserId())) {
            return;
        }
        handleTextMessage(channelId, senderId, text);
    }

    private void handleTextMessage(String channelId, String senderId, String body) {
        log.info(
                "Inbound Slack message in {} from {}",
                maskRecipient(channelId),
                maskRecipient(senderId));
        // Sessions are keyed by the CHANNEL id, not the user id - a Slack
        // reply always goes back to the same channel/DM the message arrived
        // on (chat.postMessage's `channel` param), whereas the user id is
        // only useful for @-mention/attribution purposes this MVP doesn't
        // need yet.
        ChannelSession session =
                channelSessionService.resolveOrCreate(ChannelType.SLACK, channelId, body);

        if (!session.isPaired()) {
            handleUnpairedSender(session, channelId);
            return;
        }

        // Agent-event-trigger fix (post-blackout audit): this publish used to
        // sit AFTER the workflow-binding early return below, which made a
        // channel's agent-event trigger and its workflow binding silently
        // mutually exclusive (see AgentEventTriggerDispatcher's javadoc,
        // "three honesty items" #1). It now fires unconditionally for every
        // paired sender's message, independent of whether a workflow is also
        // bound to SLACK - both consumers get to see the message.
        workflowEventPublisher.publishNewRun(
                WorkflowEventTypes.CHANNEL_SLACK_MESSAGE_RECEIVED,
                Map.of(
                        "channel",
                        "slack",
                        "fromMasked",
                        maskRecipient(senderId),
                        "bodyLength",
                        body.length()));

        // Channel-based trigger selection (#422), extended to SLACK - same
        // "a bound workflow takes over from the channel's own default
        // agent-chat behavior" precedence TelegramWebhookController/
        // TwilioWebhookController already established. Rate-limit slot is
        // keyed by channelId (sessions are keyed by channel, not sender - see
        // comment above), same as the reply-to context.
        Optional<WorkflowTriggerNodeService.TriggerBinding> triggeredBinding =
                workflowTriggerNodeService.bindingFor(WorkflowTriggerChannel.SLACK);
        if (triggeredBinding.isPresent()) {
            if (workflowTriggerNodeService.tryAcquireTriggerSlot(
                    WorkflowTriggerChannel.SLACK, channelId)) {
                log.info(
                        "Inbound Slack message in {} routed to workflow {} (channel trigger)",
                        maskRecipient(channelId),
                        triggeredBinding.get().workflowId());
                workflowTriggerPort.triggerAsync(
                        triggeredBinding.get().workflowId(),
                        triggeredBinding.get().nodeId(),
                        body,
                        REQUESTED_BY_PREFIX + maskRecipient(channelId),
                        new InboundTriggerContext(WorkflowTriggerChannel.SLACK, channelId));
            } else {
                log.info(
                        "Inbound Slack message in {} skipped - workflow trigger rate limit"
                                + " exceeded",
                        maskRecipient(channelId));
            }
            return;
        }

        // Off the request thread: Slack retries after ~3 s, and the agent turn takes longer
        // than that (see AsyncInboundReply).
        asyncReplies.run(
                "Slack", session.getId(), () -> generateAndSendReply(session, channelId, body));
    }

    /** The LLM round trip plus the approval-gated send; runs on the platform executor. */
    private void generateAndSendReply(ChannelSession session, String channelId, String body) {
        ChatTurnRequest chatTurn =
                ChatTurnRequest.builder(
                                session.getAgentId(),
                                session.getSessionId(),
                                session.getExternalId(),
                                body)
                        .build();
        TurnContext turnContext =
                TurnContext.forEntry(TenantContext.current().orElse(null), chatTurn);
        turnContext.taint().mark("inbound:slack");
        // Stage 4 (Chat file upload) added trailing attachmentKey/attachmentMimeType
        // params to AgentChatService.chat() - null/null here, same reasoning as
        // every other omnichannel inbound path (see DiscordWebhookController's comment).
        String reply =
                agentChatService
                        .chat(turnContext, chatTurn)
                        .collectList()
                        .map(chunks -> String.join("", chunks))
                        .block();

        if (reply != null && !reply.isBlank()) {
            sendReplyThroughApprovalGate(
                    channelId,
                    channelSessionService.consumeAiDisclosurePrefix(session.getId()) + reply);
        }
    }

    private void handleUnpairedSender(ChannelSession session, String channelId) {
        if (session.isPairingNoticeSent()) {
            log.info(
                    "Ignoring message from still-unpaired Slack channel {} (already notified)",
                    maskRecipient(channelId));
            return;
        }
        String notice =
                "Thanks for reaching out. For security, an admin needs to approve this conversation"
                        + " before I can respond. Your pairing code is "
                        + session.getPairingCode()
                        + ".";
        sendReplyThroughApprovalGate(
                channelId,
                channelSessionService.consumeAiDisclosurePrefix(session.getId()) + notice);
        channelSessionService.markPairingNoticeSent(session.getId());
    }

    private void sendReplyThroughApprovalGate(String to, String replyText) {
        String payloadJson;
        try {
            payloadJson =
                    MAPPER.writeValueAsString(new SlackChannelAdapter.SendPayload(to, replyText));
        } catch (Exception e) {
            log.error("Failed to serialize Slack auto-reply payload for {}", maskRecipient(to), e);
            return;
        }
        gateService.evaluate(
                SlackChannelAdapter.ACTION_TYPE,
                "Auto-reply Slack message to " + maskRecipient(to),
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
