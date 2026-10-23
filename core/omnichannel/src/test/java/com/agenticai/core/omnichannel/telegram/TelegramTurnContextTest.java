package com.agenticai.core.omnichannel.telegram;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.InboundChannelExecutor;
import com.agenticai.core.omnichannel.session.InboundMessageDedupService;
import com.agenticai.core.omnichannel.session.TurnContextProbe;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNodeService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerPort;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ADR-0022 stage 4: both Telegram delivery modes end in {@code
 * TelegramUpdateService#acceptVerifiedUpdate}, and the {@code TurnContext} of the chat turn it
 * starts carries the tenant of the bot - set by the webhook from the bot id, or by the long-polling
 * thread for its owning tenant (d3454bc25).
 */
class TelegramTurnContextTest {

    private static final String BOT_USER_ID = "999888777";
    private static final String SECRET = "webhook-secret";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String UPDATE =
            """
{"update_id":12345,"message":{"chat":{"id":"chat-1"},"from":{"id":"user-1"},"text":"hello"}}
""";

    private TurnContextProbe probe;
    private AgentChatService chat;
    private TenantResolutionService tenants;
    private TelegramCredentialRepository credentials;
    private EncryptionService encryption;
    private TelegramApiClient api;
    private TelegramUpdateService updateService;
    private TelegramWebhookController controller;
    private TelegramPollingService polling;

    @BeforeEach
    void setUp() {
        tenants = mock(TenantResolutionService.class);
        credentials = mock(TelegramCredentialRepository.class);
        encryption = mock(EncryptionService.class);
        api = mock(TelegramApiClient.class);
        ChannelSessionService sessions = mock(ChannelSessionService.class);
        InboundMessageDedupService dedup = mock(InboundMessageDedupService.class);
        chat = mock(AgentChatService.class);
        probe = TurnContextProbe.on(chat);
        InboundChannelExecutor executor = InboundChannelExecutor.synchronous();
        updateService =
                new TelegramUpdateService(
                        sessions,
                        chat,
                        mock(HumanApprovalGateService.class),
                        mock(WorkflowEventPublisher.class),
                        mock(AuditLogService.class),
                        dedup,
                        api,
                        mock(WorkflowTriggerNodeService.class),
                        mock(WorkflowTriggerPort.class),
                        executor);
        controller =
                new TelegramWebhookController(
                        tenants, credentials, encryption, updateService, executor);
        when(tenants.resolveTenantSchema(ExternalAccountProvider.TELEGRAM, BOT_USER_ID))
                .thenReturn(Optional.of("tenant_telegram"));
        TelegramCredential credential =
                new TelegramCredential(
                        BOT_USER_ID, "bot", "enc", TelegramWebhookSecret.hash(SECRET));
        when(credentials.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(credential));
        when(encryption.decrypt("enc")).thenReturn("bot-token");
        when(dedup.tryClaim(eq(ChannelType.TELEGRAM), any())).thenReturn(true);
        ChannelSession session = mock(ChannelSession.class);
        when(session.isPaired()).thenReturn(true);
        when(session.getId()).thenReturn(UUID.randomUUID());
        when(session.getAgentId()).thenReturn(UUID.randomUUID());
        when(session.getSessionId()).thenReturn("session-1");
        when(session.getExternalId()).thenReturn("chat-1");
        when(sessions.resolveOrCreate(eq(ChannelType.TELEGRAM), anyString(), anyString()))
                .thenReturn(session);
    }

    @AfterEach
    void tearDown() {
        if (polling != null) {
            polling.shutdown();
        }
        TenantContext.clear();
    }

    @Test
    void webhookTurnContextCarriesTheTenantResolvedFromTheBotOnAThreadWithNoTenant()
            throws Exception {
        controller.inbound(BOT_USER_ID, MAPPER.readTree(UPDATE), SECRET);

        probe.assertTenant("tenant_telegram", "system:telegram-inbound:" + BOT_USER_ID);
        TurnContextProbe.assertThreadClean();
    }

    @Test
    void webhookIgnoresATenantAlreadyOnTheRequestThread() throws Exception {
        TenantContext.set(TenantContext.of("tenant_other", "someone", Set.of()));

        controller.inbound(BOT_USER_ID, MAPPER.readTree(UPDATE), SECRET);

        probe.assertTenant("tenant_telegram", "system:telegram-inbound:" + BOT_USER_ID);
    }

    @Test
    void pollingTurnContextCarriesThePollersTenant() throws Exception {
        JsonNode updates = MAPPER.readTree("[" + UPDATE + "]");
        JsonNode none = MAPPER.readTree("[]");
        when(api.getUpdates(anyString(), anyLong(), anyInt()))
                .thenReturn(updates)
                .thenAnswer(
                        inv -> {
                            Thread.sleep(50);
                            return none;
                        });
        polling =
                new TelegramPollingService(
                        tenants, credentials, encryption, api, updateService, "");

        polling.ensurePolling(BOT_USER_ID, "tenant_poll");

        verify(chat, timeout(5000)).chat(any(TurnContext.class), any(ChatTurnRequest.class));
        probe.assertTenant("tenant_poll", "system:telegram-polling:" + BOT_USER_ID);
    }
}
