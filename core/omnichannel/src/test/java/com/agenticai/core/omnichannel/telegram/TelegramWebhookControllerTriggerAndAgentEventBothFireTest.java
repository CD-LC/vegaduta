package com.agenticai.core.omnichannel.telegram;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.eventbus.WorkflowEventTypes;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Post-blackout-audit fix verification (see {@code AgentEventTriggerDispatcher}'s javadoc). A
 * workflow bound to TELEGRAM must NOT silently suppress this channel's agent-event-trigger publish,
 * and vice versa - both must fire for the same paired-sender inbound message when a workflow
 * binding exists.
 */
class TelegramWebhookControllerTriggerAndAgentEventBothFireTest {

    private static final String BOT_USER_ID = "999888777";
    private static final String SECRET = "webhook-secret";
    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    private WorkflowEventPublisher workflowEventPublisher;
    private WorkflowTriggerPort workflowTriggerPort;
    private AgentChatService agentChatService;
    private TelegramWebhookController controller;

    @BeforeEach
    void setUp() {
        TenantResolutionService tenantResolutionService = mock(TenantResolutionService.class);
        TelegramCredentialRepository credentialRepository =
                mock(TelegramCredentialRepository.class);
        ChannelSessionService channelSessionService = mock(ChannelSessionService.class);
        InboundMessageDedupService dedupService = mock(InboundMessageDedupService.class);
        TelegramApiClient apiClient = mock(TelegramApiClient.class);
        EncryptionService encryptionService = mock(EncryptionService.class);
        WorkflowTriggerNodeService workflowTriggerNodeService =
                mock(WorkflowTriggerNodeService.class);
        workflowEventPublisher = mock(WorkflowEventPublisher.class);
        workflowTriggerPort = mock(WorkflowTriggerPort.class);
        agentChatService = mock(AgentChatService.class);

        controller =
                new TelegramWebhookController(
                        tenantResolutionService,
                        credentialRepository,
                        encryptionService,
                        new TelegramUpdateService(
                                channelSessionService,
                                agentChatService,
                                mock(HumanApprovalGateService.class),
                                workflowEventPublisher,
                                mock(AuditLogService.class),
                                dedupService,
                                apiClient,
                                workflowTriggerNodeService,
                                workflowTriggerPort,
                                InboundChannelExecutor.synchronous()),
                        InboundChannelExecutor.synchronous());

        when(tenantResolutionService.resolveTenantSchema(
                        ExternalAccountProvider.TELEGRAM, BOT_USER_ID))
                .thenReturn(Optional.of("tenant_test"));

        TelegramCredential credential = mock(TelegramCredential.class);
        when(credential.getWebhookSecret()).thenReturn(SECRET);
        when(credential.getEncryptedBotToken()).thenReturn("encrypted-token");
        when(credentialRepository.findFirstByOrderByCreatedAtAsc())
                .thenReturn(Optional.of(credential));
        when(encryptionService.decrypt("encrypted-token")).thenReturn("bot-token");

        when(dedupService.tryClaim(eq(ChannelType.TELEGRAM), any())).thenReturn(true);

        ChannelSession session = mock(ChannelSession.class);
        when(session.isPaired()).thenReturn(true);
        when(session.getAgentId()).thenReturn(UUID.randomUUID());
        when(session.getSessionId()).thenReturn("session-1");
        when(session.getExternalId()).thenReturn("chat-1");
        when(channelSessionService.resolveOrCreate(
                        eq(ChannelType.TELEGRAM), anyString(), anyString()))
                .thenReturn(session);

        when(workflowTriggerNodeService.bindingFor(WorkflowTriggerChannel.TELEGRAM))
                .thenReturn(
                        Optional.of(
                                new WorkflowTriggerNodeService.TriggerBinding(
                                        UUID.randomUUID(), "node-1")));
        when(workflowTriggerNodeService.tryAcquireTriggerSlot(
                        eq(WorkflowTriggerChannel.TELEGRAM), anyString()))
                .thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void bothTheWorkflowTriggerAndTheAgentEventPublishFireForTheSameMessage() throws Exception {
        JsonNode update =
                MAPPER.readTree(
                        """
{"update_id":"12345","message":{"chat":{"id":"chat-1"},"from":{"id":"user-1"},"text":"hello"}}
""");

        controller.inbound(BOT_USER_ID, update, SECRET);

        verify(workflowEventPublisher, times(1))
                .publishNewRun(
                        eq(WorkflowEventTypes.CHANNEL_TELEGRAM_MESSAGE_RECEIVED), any(Map.class));
        verify(workflowTriggerPort, times(1))
                .triggerAsync(
                        any(UUID.class),
                        eq("node-1"),
                        eq("hello"),
                        anyString(),
                        any(InboundTriggerContext.class));
        verify(agentChatService, never()).chat(any(TurnContext.class), any(ChatTurnRequest.class));
    }
}
