package com.agenticai.core.omnichannel.discord;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.eventbus.WorkflowEventTypes;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.omnichannel.discord.dto.DiscordInboundMessage;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbound endpoint the {@code channels/discord} Node service posts to for every {@code
 * MESSAGE_CREATE} Gateway event it receives, because Discord has no public webhook for ordinary
 * messages (only Interactions/slash-commands are webhook-delivered, separately, and Ed25519-signed
 * rather than shared-secret) - receiving regular messages requires a persistent Gateway WebSocket
 * connection, which lives in the standalone Node service instead of this JVM.
 *
 * <p>Auth is an internal shared secret ({@code X-Internal-Secret} matched against {@code
 * app.omnichannel.discord.shared-secret}) - this is the trusted reverse leg of a link this platform
 * does not itself initiate outbound (for messages, Java never calls the Node service; the Node
 * service calls Java here, and Java calls Discord's REST API directly for replies via {@link
 * DiscordApiClient}/{@link DiscordChannelAdapter} - see that adapter's javadoc for why outbound
 * doesn't need the Node hop at all).
 *
 * <p>Tenant resolution is keyed by the bot's own {@code applicationId} (the only stable identifier
 * a self-hosted, single-bot-per-tenant Discord connection has), via {@link
 * ExternalAccountProvider#DISCORD}.
 */
@RestController
@ConditionalOnEditionFeature(EditionFeature.SOCIAL_CHANNELS)
@RequestMapping("/webhook/discord")
public class DiscordWebhookController {

    private static final Logger log = LoggerFactory.getLogger(DiscordWebhookController.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final String REQUESTED_BY_PREFIX = "system:discord-inbound:";

    private final String sharedSecret;
    private final TenantResolutionService tenantResolutionService;
    private final ChannelSessionService channelSessionService;
    private final AgentChatService agentChatService;
    private final HumanApprovalGateService gateService;
    private final WorkflowEventPublisher workflowEventPublisher;
    private final WorkflowTriggerNodeService workflowTriggerNodeService;
    private final WorkflowTriggerPort workflowTriggerPort;
    private final InboundMessageDedupService dedupService;
    private final AsyncInboundReply asyncReplies;

    public DiscordWebhookController(
            @Value("${app.omnichannel.discord.shared-secret:}") String sharedSecret,
            TenantResolutionService tenantResolutionService,
            ChannelSessionService channelSessionService,
            AgentChatService agentChatService,
            HumanApprovalGateService gateService,
            WorkflowEventPublisher workflowEventPublisher,
            WorkflowTriggerNodeService workflowTriggerNodeService,
            WorkflowTriggerPort workflowTriggerPort,
            InboundMessageDedupService dedupService,
            AsyncInboundReply asyncReplies) {
        this.sharedSecret = sharedSecret;
        this.tenantResolutionService = tenantResolutionService;
        this.channelSessionService = channelSessionService;
        this.agentChatService = agentChatService;
        this.gateService = gateService;
        this.workflowEventPublisher = workflowEventPublisher;
        this.workflowTriggerNodeService = workflowTriggerNodeService;
        this.workflowTriggerPort = workflowTriggerPort;
        this.dedupService = dedupService;
        this.asyncReplies = asyncReplies;
    }

    @PostMapping
    public ResponseEntity<Void> inbound(
            @RequestBody DiscordInboundMessage message,
            @RequestHeader(value = "X-Internal-Secret", required = false) String secretHeader) {
        if (sharedSecret.isBlank()
                || secretHeader == null
                || !constantTimeEquals(secretHeader, sharedSecret)) {
            log.warn(
                    "Rejected discord inbound call with missing/invalid X-Internal-Secret for"
                            + " application ***{}",
                    lastFour(message.applicationId()));
            return ResponseEntity.status(403).build();
        }

        Optional<String> tenantSchema =
                tenantResolutionService.resolveTenantSchema(
                        ExternalAccountProvider.DISCORD, message.applicationId());
        if (tenantSchema.isEmpty()) {
            log.warn(
                    "No tenant link found for Discord application ***{} - ignoring.",
                    lastFour(message.applicationId()));
            return ResponseEntity.ok().build();
        }

        TenantContext.set(
                TenantContext.of(
                        tenantSchema.get(),
                        REQUESTED_BY_PREFIX + message.applicationId(),
                        Set.of()));
        try {
            // Master-Roadmap-2026.md item #1, gap (b)#3 - the same fix every
            // other inbound controller here carries: the Node Gateway service
            // retries nothing itself today, but discord.js can replay a
            // MESSAGE_CREATE after a Gateway session RESUME, and the claim
            // makes this leg safe against any future Node-side retry loop.
            // Claimed after the shared-secret check (an unauthenticated
            // caller must not be able to burn a real message's dedup key)
            // and inside the tenant scope, since the Redis key is
            // tenant-scoped. Null today - channels/discord's server.js does
            // not forward message.id yet (cross-scope, see
            // DiscordInboundMessage's javadoc) - which makes this a
            // documented no-op per tryClaim's null contract until wired.
            if (!dedupService.tryClaim(ChannelType.DISCORD, message.messageId())) {
                log.info(
                        "Ignoring duplicate Discord message id {} in {}",
                        message.messageId(),
                        maskRecipient(message.channelId()));
                return ResponseEntity.ok().build();
            }
            handleTextMessage(message.channelId(), message.authorId(), message.content());
        } catch (RuntimeException e) {
            log.warn("Failed to process discord inbound message: {}", e.getMessage());
        } finally {
            TenantContext.clear();
        }
        return ResponseEntity.ok().build();
    }

    private void handleTextMessage(String channelId, String authorId, String body) {
        log.info(
                "Inbound Discord message in {} from {}",
                maskRecipient(channelId),
                maskRecipient(authorId));
        // Sessions are keyed by the CHANNEL id, same reasoning as Slack - a
        // reply always goes back to the same channel/DM the message arrived
        // on, regardless of who specifically sent it.
        ChannelSession session =
                channelSessionService.resolveOrCreate(ChannelType.DISCORD, channelId, body);

        if (!session.isPaired()) {
            handleUnpairedSender(session, channelId);
            return;
        }

        // Agent-event-trigger fix (post-blackout audit): fires unconditionally
        // now, independent of a workflow binding on this channel - see
        // AgentEventTriggerDispatcher's javadoc, "three honesty items" #1.
        workflowEventPublisher.publishNewRun(
                WorkflowEventTypes.CHANNEL_DISCORD_MESSAGE_RECEIVED,
                Map.of(
                        "channel",
                        "discord",
                        "fromMasked",
                        maskRecipient(authorId),
                        "bodyLength",
                        body == null ? 0 : body.length()));

        // Channel-based trigger selection (#422), extended to DISCORD - same
        // "a bound workflow takes over from the channel's own default
        // agent-chat behavior" precedence TelegramWebhookController/
        // TwilioWebhookController already established. Rate-limit slot is
        // keyed by channelId (sessions are keyed by channel, not author - see
        // comment above), same as the reply-to context.
        Optional<WorkflowTriggerNodeService.TriggerBinding> triggeredBinding =
                workflowTriggerNodeService.bindingFor(WorkflowTriggerChannel.DISCORD);
        if (triggeredBinding.isPresent()) {
            if (workflowTriggerNodeService.tryAcquireTriggerSlot(
                    WorkflowTriggerChannel.DISCORD, channelId)) {
                log.info(
                        "Inbound Discord message in {} routed to workflow {} (channel trigger)",
                        maskRecipient(channelId),
                        triggeredBinding.get().workflowId());
                workflowTriggerPort.triggerAsync(
                        triggeredBinding.get().workflowId(),
                        triggeredBinding.get().nodeId(),
                        body,
                        REQUESTED_BY_PREFIX + maskRecipient(channelId),
                        new InboundTriggerContext(WorkflowTriggerChannel.DISCORD, channelId));
            } else {
                log.info(
                        "Inbound Discord message in {} skipped - workflow trigger rate limit"
                                + " exceeded",
                        maskRecipient(channelId));
            }
            return;
        }

        // Off the request thread (see AsyncInboundReply).
        asyncReplies.run(
                "Discord", session.getId(), () -> generateAndSendReply(session, channelId, body));
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
        // Stage 4 (Chat file upload) added trailing attachmentKey/attachmentMimeType
        // params to AgentChatService.chat() - null/null here since inbound Discord
        // messages never carry an uploaded chat-attachment reference (that feature
        // is Playground-only for now, not wired into any omnichannel inbound path).
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
                    "Ignoring message from still-unpaired Discord channel {} (already notified)",
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
                    MAPPER.writeValueAsString(new DiscordChannelAdapter.SendPayload(to, replyText));
        } catch (Exception e) {
            log.error(
                    "Failed to serialize Discord auto-reply payload for {}", maskRecipient(to), e);
            return;
        }
        gateService.evaluate(
                DiscordChannelAdapter.ACTION_TYPE,
                "Auto-reply Discord message to " + maskRecipient(to),
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

    // Constant-time comparison - a shared-secret check is exactly the kind of
    // thing a naive String.equals() timing side-channel could leak information
    // from, however marginal. Same idiom as MetaWhatsAppSignatureValidator.
    // A null on either side is a mismatch, never an NPE.
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
