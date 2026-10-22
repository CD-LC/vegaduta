package com.agenticai.core.omnichannel.x;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.eventbus.WorkflowEventTypes;
import com.agenticai.core.json.JsonMappers;
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
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * X's inbound counterpart to every other channel's webhook controller - except there is no webhook
 * to receive, because X's DM endpoints have no public delivery mechanism at any API tier this
 * platform's BYOK model can reasonably ask a tenant to pay for (the old Account Activity webhook
 * API is enterprise-tier pricing; see the research behind this design). Instead this job polls
 * {@code GET /2/dm_events} on a fixed interval, across every tenant with an active X connection,
 * using {@link TenantSchemaIterator} - same "don't accidentally only ever process the {@code
 * public} schema" reasoning as {@code RetryFailedActionsJob}.
 *
 * <p><b>Poll interval</b>: 60 seconds. X's Basic tier DM rate limits are tight enough that polling
 * much faster risks 429s under multi-tenant load; 60s is a reasonable "near-real-time, not actually
 * real-time" tradeoff for a channel with no webhook option, the same posture {@code mail_worker}-
 * style IMAP polling takes in the original PRD for the same underlying reason (no webhook exists
 * for the thing being watched).
 *
 * <p><b>Sessions are keyed by {@code dm_conversation_id}</b>, not the sender's user id - same
 * reasoning as Slack/Discord being keyed by channel id: a reply always targets the conversation the
 * message arrived on ({@code POST /2/dm_conversations/:id/messages}), and for X's 1:1 DMs the
 * conversation id already uniquely identifies the other participant anyway.
 *
 * <p><b>Multi-replica safe</b> since the {@link PgAdvisoryJobLock} wrap: the poll cursor ({@code
 * lastDmEventId}) is read-then-advanced with no concurrency control, so two replicas polling
 * concurrently would both read the same cursor, both fetch the same new DMs, and both reply - on
 * top of burning double the tight Basic-tier rate limit. With the lock, exactly one replica polls
 * per tick.
 */
@Component
@ConditionalOnEditionFeature(EditionFeature.SOCIAL_CHANNELS)
public class XDmPollingJob {

    private static final long FIXED_DELAY_MS = 60_000L;
    private static final long INITIAL_DELAY_MS = 20_000L;
    private static final Logger log = LoggerFactory.getLogger(XDmPollingJob.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final String REQUESTED_BY_PREFIX = "system:x-dm-poll:";
    private static final String JOB_LOCK_NAME = "x-dm-poll";

    private final PgAdvisoryJobLock jobLock;
    private final TenantSchemaIterator schemaIterator;
    private final XOAuthService oauthService;
    private final XApiClient apiClient;
    private final ChannelSessionService channelSessionService;
    private final AgentChatService agentChatService;
    private final HumanApprovalGateService gateService;
    private final WorkflowEventPublisher workflowEventPublisher;
    private final WorkflowTriggerNodeService workflowTriggerNodeService;
    private final WorkflowTriggerPort workflowTriggerPort;
    private final InboundMessageDedupService dedupService;

    public XDmPollingJob(
            PgAdvisoryJobLock jobLock,
            TenantSchemaIterator schemaIterator,
            XOAuthService oauthService,
            XApiClient apiClient,
            ChannelSessionService channelSessionService,
            AgentChatService agentChatService,
            HumanApprovalGateService gateService,
            WorkflowEventPublisher workflowEventPublisher,
            WorkflowTriggerNodeService workflowTriggerNodeService,
            WorkflowTriggerPort workflowTriggerPort,
            InboundMessageDedupService dedupService) {
        this.jobLock = jobLock;
        this.schemaIterator = schemaIterator;
        this.oauthService = oauthService;
        this.apiClient = apiClient;
        this.channelSessionService = channelSessionService;
        this.agentChatService = agentChatService;
        this.gateService = gateService;
        this.workflowEventPublisher = workflowEventPublisher;
        this.workflowTriggerNodeService = workflowTriggerNodeService;
        this.workflowTriggerPort = workflowTriggerPort;
        this.dedupService = dedupService;
    }

    @Scheduled(fixedDelay = FIXED_DELAY_MS, initialDelay = INITIAL_DELAY_MS)
    public void pollAllTenants() {
        jobLock.tryRunExclusive(JOB_LOCK_NAME, this::pollAllTenantsExclusively);
    }

    private void pollAllTenantsExclusively() {
        schemaIterator.forEachActiveTenantSchema(
                schema -> {
                    Optional<XCredential> credential = oauthService.activeCredential();
                    credential.ifPresent(this::pollTenant);
                });
    }

    private void pollTenant(XCredential credential) {
        String accessToken;
        try {
            accessToken = oauthService.currentAccessToken();
        } catch (RuntimeException e) {
            log.warn("Skipping X DM poll - could not obtain an access token: {}", e.getMessage());
            return;
        }

        String cursor = credential.getLastDmEventId();
        if (cursor == null) {
            // Never seeded (e.g. XOAuthService#connect's best-effort seed failed) - seed now
            // without processing
            // anything, same reasoning as that method's javadoc: we never want to reply to a whole
            // DM history at once.
            String latest = apiClient.fetchLatestEventId(accessToken);
            oauthService.advancePollCursor(latest);
            return;
        }

        XApiClient.PollResult result =
                apiClient.fetchNewMessagesSince(accessToken, cursor, credential.getXUserId());
        for (XApiClient.DmEvent event : result.newEvents()) {
            try {
                handleMessage(event);
            } catch (RuntimeException e) {
                log.warn(
                        "Failed to process X DM event {} in conversation {}: {}",
                        event.id(),
                        maskRecipient(event.dmConversationId()),
                        e.getMessage());
            }
        }
        if (result.newCursor() != null && !result.newCursor().equals(cursor)) {
            oauthService.advancePollCursor(result.newCursor());
        }
    }

    private void handleMessage(XApiClient.DmEvent event) {
        // Same inbound-dedup gate as the webhook channels (Telegram/Discord),
        // claimed BEFORE any processing so a duplicate delivery is a
        // true no-op. Polling has its own replay window the cursor alone
        // doesn't close: advancePollCursor runs AFTER the event loop, so a
        // crash/restart mid-batch (or a failed cursor save) re-fetches and
        // would re-reply to the same DM events on the next tick. The dm_event
        // id is X's own per-event id - provider-native, stable across
        // re-fetches - and TenantSchemaIterator has already set TenantContext
        // here, so the Redis key is correctly tenant-scoped.
        if (!dedupService.tryClaim(ChannelType.X, event.id())) {
            log.info(
                    "Ignoring duplicate X DM event {} in conversation {}",
                    event.id(),
                    maskRecipient(event.dmConversationId()));
            return;
        }
        log.info(
                "Inbound X DM in conversation {} from {}",
                maskRecipient(event.dmConversationId()),
                maskRecipient(event.senderId()));
        ChannelSession session =
                channelSessionService.resolveOrCreate(
                        ChannelType.X, event.dmConversationId(), event.text());

        if (!session.isPaired()) {
            handleUnpairedSender(session, event.dmConversationId());
            return;
        }

        // Agent-event-trigger fix (post-blackout audit): fires unconditionally
        // now, independent of a workflow binding on this channel - see
        // AgentEventTriggerDispatcher's javadoc, "three honesty items" #1.
        workflowEventPublisher.publishNewRun(
                WorkflowEventTypes.CHANNEL_X_MESSAGE_RECEIVED,
                Map.of(
                        "channel",
                        "x",
                        "fromMasked",
                        maskRecipient(event.senderId()),
                        "bodyLength",
                        event.text().length()));

        // Channel-based trigger selection (#422), extended to X - same "a
        // bound workflow takes over from the channel's own default
        // agent-chat behavior" precedence TelegramWebhookController/
        // TwilioWebhookController already established. Rate-limit slot is
        // keyed by dmConversationId (sessions are keyed by conversation, not
        // sender - see class javadoc), same as the reply-to context.
        Optional<WorkflowTriggerNodeService.TriggerBinding> triggeredBinding =
                workflowTriggerNodeService.bindingFor(WorkflowTriggerChannel.X);
        if (triggeredBinding.isPresent()) {
            if (workflowTriggerNodeService.tryAcquireTriggerSlot(
                    WorkflowTriggerChannel.X, event.dmConversationId())) {
                log.info(
                        "Inbound X DM in conversation {} routed to workflow {} (channel trigger)",
                        maskRecipient(event.dmConversationId()),
                        triggeredBinding.get().workflowId());
                workflowTriggerPort.triggerAsync(
                        triggeredBinding.get().workflowId(),
                        triggeredBinding.get().nodeId(),
                        event.text(),
                        REQUESTED_BY_PREFIX + maskRecipient(event.dmConversationId()),
                        new InboundTriggerContext(
                                WorkflowTriggerChannel.X, event.dmConversationId()));
            } else {
                log.info(
                        "Inbound X DM in conversation {} skipped - workflow trigger rate limit"
                                + " exceeded",
                        maskRecipient(event.dmConversationId()));
            }
            return;
        }

        ChatTurnRequest chatTurn =
                ChatTurnRequest.builder(
                                session.getAgentId(),
                                session.getSessionId(),
                                session.getExternalId(),
                                event.text())
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
                    event.dmConversationId(),
                    channelSessionService.consumeAiDisclosurePrefix(session.getId()) + reply);
        }
    }

    private void handleUnpairedSender(ChannelSession session, String dmConversationId) {
        if (session.isPairingNoticeSent()) {
            log.info(
                    "Ignoring message from still-unpaired X conversation {} (already notified)",
                    maskRecipient(dmConversationId));
            return;
        }
        String notice =
                "Thanks for reaching out. For security, an admin needs to approve this conversation"
                        + " before I can respond. Your pairing code is "
                        + session.getPairingCode()
                        + ".";
        sendReplyThroughApprovalGate(
                dmConversationId,
                channelSessionService.consumeAiDisclosurePrefix(session.getId()) + notice);
        channelSessionService.markPairingNoticeSent(session.getId());
    }

    private void sendReplyThroughApprovalGate(String to, String replyText) {
        String payloadJson;
        try {
            payloadJson = MAPPER.writeValueAsString(new XChannelAdapter.SendPayload(to, replyText));
        } catch (Exception e) {
            log.error("Failed to serialize X auto-reply payload for {}", maskRecipient(to), e);
            return;
        }
        gateService.evaluate(
                XChannelAdapter.ACTION_TYPE,
                "Auto-reply X DM to " + maskRecipient(to),
                payloadJson,
                REQUESTED_BY_PREFIX + to);
    }

    private static String maskRecipient(String to) {
        if (to == null || to.length() < 4) {
            return "***";
        }
        return "***" + to.substring(to.length() - 4);
    }
}
