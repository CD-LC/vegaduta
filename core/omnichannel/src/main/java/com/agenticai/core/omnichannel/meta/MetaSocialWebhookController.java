package com.agenticai.core.omnichannel.meta;

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
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbound Messenger + Instagram DMs - one endpoint for both, because Meta's Messenger Platform
 * webhook uses the exact same {@code entry[].messaging[]} payload shape for both surfaces,
 * differentiated only by the top-level {@code object} field ({@code "page"} for Messenger, {@code
 * "instagram"} for Instagram). Structurally this is {@code MetaWhatsAppWebhookController} adapted
 * to that different payload shape - same GET verification handshake (shared {@code
 * app.meta.webhook-verify-token}), same {@code X-Hub-Signature-256} verification via the
 * signature-scheme-generic {@link MetaWhatsAppSignatureValidator}, same "resolve tenant from an
 * unverified but self-defeating-to-forge identifier, then verify with that tenant's own secret"
 * reasoning.
 *
 * <p><b>Tenant resolution</b>: {@code entry[].id} is the recipient's own page-scoped ID - the
 * connected Page's ID for a {@code "page"} object, or the Instagram Business Account ID for an
 * {@code "instagram"} object - looked up via {@link ExternalAccountProvider#META_MESSENGER} or
 * {@link ExternalAccountProvider#META_INSTAGRAM} respectively (see {@link
 * MetaSocialCredentialService#activate}, which registers both links from the one credential).
 *
 * <p>Only plain text messages are handled - attachments (images, stickers, quick-reply payloads)
 * are silently skipped, an honest MVP scope matching {@code MetaWhatsAppWebhookController}'s own
 * "text only for now" posture.
 */
@RestController
@RequestMapping("/webhook/meta/social")
public class MetaSocialWebhookController {

    private static final Logger log = LoggerFactory.getLogger(MetaSocialWebhookController.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final String REQUESTED_BY_PREFIX = "system:social-inbound:";

    private final String webhookVerifyToken;
    private final MetaWhatsAppSignatureValidator signatureValidator;
    private final MetaSocialCredentialService credentialService;
    private final ChannelSessionService channelSessionService;
    private final AgentChatService agentChatService;
    private final HumanApprovalGateService gateService;
    private final TenantResolutionService tenantResolutionService;
    private final WorkflowEventPublisher workflowEventPublisher;
    private final WorkflowTriggerNodeService workflowTriggerNodeService;
    private final WorkflowTriggerPort workflowTriggerPort;
    private final InboundMessageDedupService dedupService;
    private final AsyncInboundReply asyncReplies;

    public MetaSocialWebhookController(
            @Value("${app.meta.webhook-verify-token:}") String webhookVerifyToken,
            MetaWhatsAppSignatureValidator signatureValidator,
            MetaSocialCredentialService credentialService,
            ChannelSessionService channelSessionService,
            AgentChatService agentChatService,
            HumanApprovalGateService gateService,
            TenantResolutionService tenantResolutionService,
            WorkflowEventPublisher workflowEventPublisher,
            WorkflowTriggerNodeService workflowTriggerNodeService,
            WorkflowTriggerPort workflowTriggerPort,
            InboundMessageDedupService dedupService,
            AsyncInboundReply asyncReplies) {
        this.webhookVerifyToken = webhookVerifyToken;
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

    @GetMapping
    public ResponseEntity<String> verify(
            @RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String verifyToken,
            @RequestParam(name = "hub.challenge", required = false) String challenge) {
        // SEC-050: constant-time compare (String.equals returns at the first
        // differing char); fails closed when no verify token is configured.
        boolean tokenMatches =
                verifyToken != null
                        && webhookVerifyToken != null
                        && !webhookVerifyToken.isBlank()
                        && java.security.MessageDigest.isEqual(
                                verifyToken.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                webhookVerifyToken.getBytes(
                                        java.nio.charset.StandardCharsets.UTF_8));
        if (!"subscribe".equals(mode) || !tokenMatches) {
            log.warn("Rejected Meta social webhook verification handshake (mode={})", mode);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(challenge);
    }

    @PostMapping
    public ResponseEntity<Void> inbound(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Hub-Signature-256", required = false)
                    String signatureHeader) {
        JsonNode root;
        try {
            root = MAPPER.readTree(rawBody);
        } catch (Exception e) {
            log.warn("Could not parse inbound Meta social webhook body as JSON", e);
            return ResponseEntity.ok()
                    .build(); // 200 regardless - Meta retries/disables the webhook on non-2xx.
        }

        String object = root.path("object").asString("");
        boolean isInstagram = "instagram".equals(object);
        if (!isInstagram && !"page".equals(object)) {
            log.info("Ignoring Meta social webhook with unrecognized object type '{}'", object);
            return ResponseEntity.ok().build();
        }

        for (JsonNode entry : root.path("entry")) {
            String recipientScopedId = entry.path("id").asString(null);
            if (recipientScopedId == null) {
                continue;
            }
            for (JsonNode messagingEvent : entry.path("messaging")) {
                handleMessagingEvent(
                        isInstagram, recipientScopedId, messagingEvent, rawBody, signatureHeader);
            }
        }
        return ResponseEntity.ok().build();
    }

    private void handleMessagingEvent(
            boolean isInstagram,
            String recipientScopedId,
            JsonNode messagingEvent,
            String rawBody,
            String signatureHeader) {
        ExternalAccountProvider provider =
                isInstagram
                        ? ExternalAccountProvider.META_INSTAGRAM
                        : ExternalAccountProvider.META_MESSENGER;
        Optional<String> tenantSchema =
                tenantResolutionService.resolveTenantSchema(provider, recipientScopedId);
        if (tenantSchema.isEmpty()) {
            log.warn(
                    "No tenant link found for {} recipient ***{} - ignoring.",
                    isInstagram ? "Instagram" : "Messenger",
                    lastFour(recipientScopedId));
            return;
        }

        TenantContext.set(
                TenantContext.of(
                        tenantSchema.get(), REQUESTED_BY_PREFIX + recipientScopedId, Set.of()));
        try {
            MetaSocialCredentialService.DecryptedCredential credential =
                    credentialService.resolveActive();
            signatureValidator.validateOrThrow(rawBody, signatureHeader, credential.appSecret());

            String senderId = messagingEvent.path("sender").path("id").asString(null);
            String text = messagingEvent.path("message").path("text").asString(null);
            if (senderId == null || text == null) {
                log.info(
                        "Skipping non-text or malformed {} messaging event",
                        isInstagram ? "Instagram" : "Messenger");
                return;
            }
            // Master-Roadmap-2026.md item #1, gap (b)#3 - the same fix
            // MetaWhatsAppWebhookController already carries for its wamid:
            // Meta's Messenger Platform retries a webhook delivery on
            // anything but a fast 2xx, so the same event can arrive twice.
            // Claimed on message.mid (Meta's stable per-message id, present
            // on every messaging event that carries a message) AFTER
            // signature validation - an unauthenticated caller must not be
            // able to burn a real message's dedup key - and BEFORE any
            // processing, per messaging event rather than per payload since
            // Meta batches entries. The 200 for the whole payload still goes
            // out either way (see #inbound).
            String mid = messagingEvent.path("message").path("mid").asString(null);
            ChannelType channelType = isInstagram ? ChannelType.INSTAGRAM : ChannelType.MESSENGER;
            if (!dedupService.tryClaim(channelType, mid)) {
                log.info(
                        "Ignoring duplicate {} message mid {}",
                        isInstagram ? "Instagram" : "Messenger",
                        mid);
                return;
            }
            handleTextMessage(isInstagram, senderId, text);
        } catch (RuntimeException e) {
            log.warn("Failed to process Meta social webhook event: {}", e.getMessage());
        } finally {
            TenantContext.clear();
        }
    }

    private void handleTextMessage(boolean isInstagram, String senderId, String body) {
        ChannelType channelType = isInstagram ? ChannelType.INSTAGRAM : ChannelType.MESSENGER;
        String channelLabel = isInstagram ? "instagram" : "messenger";
        log.info("Inbound {} message from {}", channelLabel, maskRecipient(senderId));
        ChannelSession session = channelSessionService.resolveOrCreate(channelType, senderId, body);

        if (!session.isPaired()) {
            handleUnpairedSender(channelType, isInstagram, session, senderId);
            return;
        }

        // Channel-based trigger selection (#422), extended to MESSENGER/INSTAGRAM -
        // same "a bound workflow takes over from the channel's own default
        // agent-chat behavior" precedence TelegramWebhookController/
        // TwilioWebhookController already established. Fire-and-forget, with
        // the sender id riding along as the reply-to context (see
        // InboundTriggerContext) even though no Output node case consumes it
        // for these channels yet - see WorkflowTriggerChannel's javadoc.
        // Agent-event-trigger fix (post-blackout audit): fires unconditionally
        // now, independent of a workflow binding on this channel - see
        // AgentEventTriggerDispatcher's javadoc, "three honesty items" #1.
        workflowEventPublisher.publishNewRun(
                isInstagram
                        ? WorkflowEventTypes.CHANNEL_INSTAGRAM_MESSAGE_RECEIVED
                        : WorkflowEventTypes.CHANNEL_MESSENGER_MESSAGE_RECEIVED,
                Map.of(
                        "channel",
                        channelLabel,
                        "fromMasked",
                        maskRecipient(senderId),
                        "bodyLength",
                        body.length()));

        WorkflowTriggerChannel triggerChannel =
                isInstagram ? WorkflowTriggerChannel.INSTAGRAM : WorkflowTriggerChannel.MESSENGER;
        Optional<WorkflowTriggerNodeService.TriggerBinding> triggeredBinding =
                workflowTriggerNodeService.bindingFor(triggerChannel);
        if (triggeredBinding.isPresent()) {
            if (workflowTriggerNodeService.tryAcquireTriggerSlot(triggerChannel, senderId)) {
                log.info(
                        "Inbound {} message from {} routed to workflow {} (channel trigger)",
                        channelLabel,
                        maskRecipient(senderId),
                        triggeredBinding.get().workflowId());
                workflowTriggerPort.triggerAsync(
                        triggeredBinding.get().workflowId(),
                        triggeredBinding.get().nodeId(),
                        body,
                        REQUESTED_BY_PREFIX + maskRecipient(senderId),
                        new InboundTriggerContext(triggerChannel, senderId));
            } else {
                log.info(
                        "Inbound {} message from {} skipped - workflow trigger rate limit exceeded",
                        channelLabel,
                        maskRecipient(senderId));
            }
            return;
        }

        // Off the request thread (see AsyncInboundReply).
        asyncReplies.run(
                channelLabel,
                session.getId(),
                () -> generateAndSendReply(session, isInstagram, senderId, body));
    }

    /** The LLM round trip plus the approval-gated send; runs on the platform executor. */
    private void generateAndSendReply(
            ChannelSession session, boolean isInstagram, String senderId, String body) {
        ChatTurnRequest chatTurn =
                ChatTurnRequest.builder(
                                session.getAgentId(),
                                session.getSessionId(),
                                session.getExternalId(),
                                body)
                        .build();
        TurnContext turnContext =
                TurnContext.forEntry(TenantContext.current().orElse(null), chatTurn);
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
                    isInstagram,
                    senderId,
                    channelSessionService.consumeAiDisclosurePrefix(session.getId()) + reply);
        }
    }

    private void handleUnpairedSender(
            ChannelType channelType, boolean isInstagram, ChannelSession session, String senderId) {
        if (session.isPairingNoticeSent()) {
            log.info(
                    "Ignoring message from still-unpaired {} sender {} (already notified)",
                    channelType,
                    maskRecipient(senderId));
            return;
        }
        String notice =
                "Thanks for reaching out. For security, an admin needs to approve this conversation"
                        + " before I can respond. Your pairing code is "
                        + session.getPairingCode()
                        + ".";
        sendReplyThroughApprovalGate(
                isInstagram,
                senderId,
                channelSessionService.consumeAiDisclosurePrefix(session.getId()) + notice);
        channelSessionService.markPairingNoticeSent(session.getId());
    }

    private void sendReplyThroughApprovalGate(boolean isInstagram, String to, String replyText) {
        String actionType =
                isInstagram
                        ? MetaInstagramChannelAdapter.ACTION_TYPE
                        : MetaMessengerChannelAdapter.ACTION_TYPE;
        String channelLabel = isInstagram ? "Instagram DM" : "Messenger message";
        String payloadJson;
        try {
            payloadJson =
                    isInstagram
                            ? MAPPER.writeValueAsString(
                                    new MetaInstagramChannelAdapter.SendPayload(to, replyText))
                            : MAPPER.writeValueAsString(
                                    new MetaMessengerChannelAdapter.SendPayload(to, replyText));
        } catch (Exception e) {
            log.error("Failed to serialize auto-reply payload for {}", maskRecipient(to), e);
            return;
        }
        gateService.evaluate(
                actionType,
                "Auto-reply " + channelLabel + " to " + maskRecipient(to),
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
