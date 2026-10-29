package com.agenticai.core.omnichannel.teams;

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
import com.agenticai.core.security.logging.LogSafe;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Microsoft Teams inbound webhook (migration V240, 2026-09-23) - the Bot Framework counterpart to
 * {@code TelegramWebhookController}. A tenant's Azure Bot registration is configured with the
 * messaging endpoint {@code https://<api-host>/webhook/teams/<appId>}; Microsoft's Bot Framework
 * relays every Teams message addressed to the bot as an Activity JSON POST with a {@code Bearer}
 * JWT issued by {@code api.botframework.com}.
 *
 * <p><b>Auth model:</b> the appId in the path resolves the tenant (same "self-defeating to forge"
 * lookup Slack/Telegram use), then {@link TeamsBotFrameworkJwtValidator} proves the call genuinely
 * came from Bot Framework and was addressed to THIS bot (aud == appId, RS256 signature against
 * Microsoft's published keys, {@code api.botframework.com} issuer, unexpired). There is no shared
 * webhook secret to echo - the JWT IS the credential, and verifying it inside the resolved tenant
 * context also confirms the stored credential row's appId matches the URL.
 *
 * <p>Bot Framework activity types other than {@code "message"} (conversationUpdate, invoke, typing)
 * are acknowledged and dropped - the protocol requires a 200 within ~15s or the framework retries,
 * so this endpoint does its full synchronous agent turn and still answers in time (Teams UX
 * tolerates multi-second replies; no typing-indicator API exists for proactive sends that wouldn't
 * risk an extra connector round-trip, and Bot Framework's own "typing" activity IS supported - see
 * {@link #handleMessage}).
 */
@RestController
@ConditionalOnEditionFeature(EditionFeature.SOCIAL_CHANNELS)
@RequestMapping("/webhook/teams")
public class TeamsWebhookController {

    private static final Logger log = LoggerFactory.getLogger(TeamsWebhookController.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final String REQUESTED_BY_PREFIX = "system:teams-inbound:";
    private static final String AUDIT_ACTOR = "system:teams-inbound";

    private final TenantResolutionService tenantResolutionService;
    private final TeamsCredentialRepository credentialRepository;
    private final TeamsCredentialService credentialService;
    private final TeamsBotFrameworkJwtValidator jwtValidator;
    private final ChannelSessionService channelSessionService;
    private final AgentChatService agentChatService;
    private final HumanApprovalGateService gateService;
    private final WorkflowEventPublisher workflowEventPublisher;
    private final AuditLogService auditLogService;
    private final InboundMessageDedupService dedupService;
    private final AsyncInboundReply asyncReplies;
    private final WorkflowTriggerNodeService workflowTriggerNodeService;
    private final WorkflowTriggerPort workflowTriggerPort;

    public TeamsWebhookController(
            TenantResolutionService tenantResolutionService,
            TeamsCredentialRepository credentialRepository,
            TeamsCredentialService credentialService,
            TeamsBotFrameworkJwtValidator jwtValidator,
            ChannelSessionService channelSessionService,
            AgentChatService agentChatService,
            HumanApprovalGateService gateService,
            WorkflowEventPublisher workflowEventPublisher,
            AuditLogService auditLogService,
            InboundMessageDedupService dedupService,
            WorkflowTriggerNodeService workflowTriggerNodeService,
            WorkflowTriggerPort workflowTriggerPort,
            AsyncInboundReply asyncReplies) {
        this.tenantResolutionService = tenantResolutionService;
        this.credentialRepository = credentialRepository;
        this.credentialService = credentialService;
        this.jwtValidator = jwtValidator;
        this.channelSessionService = channelSessionService;
        this.agentChatService = agentChatService;
        this.gateService = gateService;
        this.workflowEventPublisher = workflowEventPublisher;
        this.auditLogService = auditLogService;
        this.dedupService = dedupService;
        this.asyncReplies = asyncReplies;
        this.workflowTriggerNodeService = workflowTriggerNodeService;
        this.workflowTriggerPort = workflowTriggerPort;
    }

    @PostMapping("/{appId}")
    public ResponseEntity<Void> inbound(
            @PathVariable String appId,
            @RequestBody JsonNode activity,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Optional<String> tenantSchema =
                tenantResolutionService.resolveTenantSchema(ExternalAccountProvider.TEAMS, appId);
        if (tenantSchema.isEmpty()) {
            log.warn(
                    "No tenant link found for Teams bot appId ***{} - ignoring.",
                    LogSafe.clean(lastFour(appId)));
            // 200, not 401: Bot Framework retries hard on non-2xx and an
            // unlinked appId can never become valid by retrying.
            return ResponseEntity.ok().build();
        }

        TenantContext.set(
                TenantContext.of(tenantSchema.get(), REQUESTED_BY_PREFIX + appId, Set.of()));
        try {
            Optional<TeamsCredential> credential =
                    credentialRepository.findFirstByOrderByCreatedAtAsc();
            if (credential.isEmpty() || !credential.get().getAppId().equals(appId)) {
                log.warn(
                        "Rejected Teams inbound: appId ***{} does not match the connected"
                                + " credential",
                        LogSafe.clean(lastFour(appId)));
                return ResponseEntity.status(403).build();
            }
            String bearer = bearerToken(authorization);
            // SEC-047: the token's serviceurl claim is bound to the activity's
            // serviceUrl (and that host allowlisted) before the serviceUrl is
            // stored as a route or used to send the bot's AAD token anywhere.
            if (!jwtValidator.isValid(bearer, appId, activity.path("serviceUrl").asText(null))) {
                log.warn(
                        "Rejected Teams inbound with invalid Bot Framework JWT for appId ***{}",
                        LogSafe.clean(lastFour(appId)));
                return ResponseEntity.status(403).build();
            }
            // Bot Framework redelivers on timeout; activity.id is its own
            // stable per-delivery identifier (same dedup discipline as
            // Telegram's update_id).
            String activityId = activity.path("id").asText(null);
            if (!dedupService.tryClaim(ChannelType.TEAMS, activityId)) {
                log.info(
                        "Ignoring duplicate Teams activity id {} for bot ***{}",
                        activityId,
                        LogSafe.clean(lastFour(appId)));
                return ResponseEntity.ok().build();
            }
            handleActivity(activity);
        } catch (RuntimeException e) {
            log.warn("Failed to process Teams inbound activity: {}", e.getMessage());
        } finally {
            TenantContext.clear();
        }
        return ResponseEntity.ok().build();
    }

    private void handleActivity(JsonNode activity) {
        if (!"message".equals(activity.path("type").asText(null))) {
            return;
        }
        String conversationId = activity.path("conversation").path("id").asText(null);
        String serviceUrl = activity.path("serviceUrl").asText(null);
        String fromId = activity.path("from").path("id").asText(null);
        String text = stripMention(activity.path("text").asText(null));
        if (conversationId == null || serviceUrl == null || text == null || text.isBlank()) {
            return;
        }

        credentialService.rememberRoute(conversationId, serviceUrl);

        log.info(
                "Inbound Teams message in conversation {} from {}",
                maskRecipient(conversationId),
                maskRecipient(fromId));
        ChannelSession session =
                channelSessionService.resolveOrCreate(ChannelType.TEAMS, conversationId, text);

        if (!session.isPaired()) {
            handleUnpairedSender(session, serviceUrl, conversationId);
            return;
        }

        workflowEventPublisher.publishNewRun(
                WorkflowEventTypes.CHANNEL_TEAMS_MESSAGE_RECEIVED,
                Map.of(
                        "channel",
                        "teams",
                        "fromMasked",
                        maskRecipient(fromId),
                        "bodyLength",
                        text.length()));

        Optional<WorkflowTriggerNodeService.TriggerBinding> triggeredBinding =
                workflowTriggerNodeService.bindingFor(WorkflowTriggerChannel.TEAMS);
        if (triggeredBinding.isPresent()) {
            if (workflowTriggerNodeService.tryAcquireTriggerSlot(
                    WorkflowTriggerChannel.TEAMS, conversationId)) {
                log.info(
                        "Inbound Teams message in conversation {} routed to workflow {} (channel"
                                + " trigger)",
                        maskRecipient(conversationId),
                        triggeredBinding.get().workflowId());
                workflowTriggerPort.triggerAsync(
                        triggeredBinding.get().workflowId(),
                        triggeredBinding.get().nodeId(),
                        text,
                        REQUESTED_BY_PREFIX + maskRecipient(conversationId),
                        // The reply needs BOTH coordinates (see WorkflowTriggerChannel.TEAMS'
                        // note) - "|" cannot appear in either part, so the packed
                        // form is unambiguous.
                        new InboundTriggerContext(
                                WorkflowTriggerChannel.TEAMS, serviceUrl + "|" + conversationId));
            } else {
                log.info(
                        "Inbound Teams message in conversation {} skipped - workflow trigger rate"
                                + " limit exceeded",
                        maskRecipient(conversationId));
            }
            return;
        }

        // Off the request thread (see AsyncInboundReply). Bot Framework expects the
        // activity POST to be acknowledged promptly; the reply is sent separately anyway.
        String activityId = activity.path("id").asText(null);
        asyncReplies.run(
                "Teams",
                session.getId(),
                () -> generateAndSendReply(session, serviceUrl, conversationId, activityId, text));
    }

    /** The LLM round trip plus the approval-gated send; runs on the platform executor. */
    private void generateAndSendReply(
            ChannelSession session,
            String serviceUrl,
            String conversationId,
            String activityId,
            String text) {
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
            turnContext.taint().mark("inbound:teams");
            reply =
                    agentChatService
                            .chat(turnContext, chatTurn)
                            .collectList()
                            .map(chunks -> String.join("", chunks))
                            .block();
        } catch (AgentNotFoundException e) {
            handleStaleAgentBinding(session, serviceUrl, conversationId);
            return;
        }

        if (reply != null && !reply.isBlank()) {
            sendReplyThroughApprovalGate(
                    serviceUrl,
                    conversationId,
                    activityId,
                    channelSessionService.consumeAiDisclosurePrefix(session.getId()) + reply);
        }
    }

    private void handleStaleAgentBinding(
            ChannelSession session, String serviceUrl, String conversationId) {
        log.warn(
                "Teams session {} is bound to agent {} which no longer exists",
                session.getId(),
                session.getAgentId());
        auditLogService.record(
                AUDIT_ACTOR,
                "channel.teams.agent_not_found",
                "channel_session",
                session.getId().toString(),
                AuditOutcome.FAILURE,
                "Teams conversation "
                        + maskRecipient(conversationId)
                        + " is bound to agent "
                        + session.getAgentId()
                        + " which no longer exists - rebind this session to a valid agent.");
        sendReplyThroughApprovalGate(
                serviceUrl,
                conversationId,
                null,
                channelSessionService.consumeAiDisclosurePrefix(session.getId())
                        + "This conversation's assigned agent is no longer available. An admin has"
                        + " been notified - please try again shortly.");
    }

    private void handleUnpairedSender(
            ChannelSession session, String serviceUrl, String conversationId) {
        if (session.isPairingNoticeSent()) {
            log.info(
                    "Ignoring message from still-unpaired Teams conversation {} (already notified)",
                    maskRecipient(conversationId));
            return;
        }
        String notice =
                "Thanks for reaching out. For security, an admin needs to approve this conversation"
                        + " before I can respond. Your pairing code is "
                        + session.getPairingCode()
                        + ".";
        sendReplyThroughApprovalGate(
                serviceUrl,
                conversationId,
                null,
                channelSessionService.consumeAiDisclosurePrefix(session.getId()) + notice);
        channelSessionService.markPairingNoticeSent(session.getId());
    }

    private void sendReplyThroughApprovalGate(
            String serviceUrl, String conversationId, String replyToId, String replyText) {
        String payloadJson;
        try {
            payloadJson =
                    MAPPER.writeValueAsString(
                            new TeamsChannelAdapter.SendPayload(
                                    serviceUrl, conversationId, replyText, replyToId));
        } catch (Exception e) {
            log.error(
                    "Failed to serialize Teams auto-reply payload for {}",
                    maskRecipient(conversationId),
                    e);
            return;
        }
        gateService.evaluate(
                TeamsChannelAdapter.ACTION_TYPE,
                "Auto-reply Teams message to " + maskRecipient(conversationId),
                payloadJson,
                REQUESTED_BY_PREFIX + conversationId);
    }

    private static String bearerToken(String authorization) {
        if (authorization == null) {
            return null;
        }
        String trimmed = authorization.trim();
        return trimmed.regionMatches(true, 0, "Bearer ", 0, 7) ? trimmed.substring(7).trim() : null;
    }

    /**
     * Teams group/channel messages arrive with the bot mention inline ({@code <at>Bot Name</at>
     * actual text}); 1:1 chats carry no mention. Stripping gives the agent the same clean text
     * every other channel passes through.
     */
    private static String stripMention(String text) {
        if (text == null) {
            return null;
        }
        return text.replaceAll("(?i)<at>.*?</at>", "").trim();
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
