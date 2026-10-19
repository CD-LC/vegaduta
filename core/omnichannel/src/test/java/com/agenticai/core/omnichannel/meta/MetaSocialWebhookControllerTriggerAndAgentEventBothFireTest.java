package com.agenticai.core.omnichannel.meta;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.eventbus.WorkflowEventTypes;
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
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Post-blackout-audit fix verification (see {@code AgentEventTriggerDispatcher}'s javadoc). A
 * workflow bound to MESSENGER must NOT silently suppress this channel's agent-event-trigger
 * publish, and vice versa - both must fire for the same paired-sender inbound message when a
 * workflow binding exists.
 */
class MetaSocialWebhookControllerTriggerAndAgentEventBothFireTest {

    private static final String PAGE_ID = "page-1234";
    private static final String IG_ACCOUNT_ID = "ig-5678";
    private static final String MESSENGER_BODY =
            """
            {"object":"page","entry":[{"id":"page-1234","messaging":[
              {"sender":{"id":"user-0001"},"message":{"mid":"m_MID-2","text":"hello"}}
            ]}]}
            """;

    private WorkflowEventPublisher workflowEventPublisher;
    private WorkflowTriggerPort workflowTriggerPort;
    private AgentChatService agentChatService;
    private MetaSocialWebhookController controller;

    @BeforeEach
    void setUp() {
        MetaWhatsAppSignatureValidator signatureValidator =
                mock(MetaWhatsAppSignatureValidator.class);
        MetaSocialCredentialService credentialService = mock(MetaSocialCredentialService.class);
        TenantResolutionService tenantResolutionService = mock(TenantResolutionService.class);
        ChannelSessionService channelSessionService = mock(ChannelSessionService.class);
        InboundMessageDedupService dedupService = mock(InboundMessageDedupService.class);
        WorkflowTriggerNodeService workflowTriggerNodeService =
                mock(WorkflowTriggerNodeService.class);
        workflowEventPublisher = mock(WorkflowEventPublisher.class);
        workflowTriggerPort = mock(WorkflowTriggerPort.class);
        agentChatService = mock(AgentChatService.class);

        controller =
                new MetaSocialWebhookController(
                        "verify-token",
                        signatureValidator,
                        credentialService,
                        channelSessionService,
                        agentChatService,
                        mock(HumanApprovalGateService.class),
                        tenantResolutionService,
                        workflowEventPublisher,
                        workflowTriggerNodeService,
                        workflowTriggerPort,
                        dedupService,
                        new AsyncInboundReply(Runnable::run));

        when(tenantResolutionService.resolveTenantSchema(
                        ExternalAccountProvider.META_MESSENGER, PAGE_ID))
                .thenReturn(Optional.of("tenant_test"));
        when(credentialService.resolveActive())
                .thenReturn(
                        new MetaSocialCredentialService.DecryptedCredential(
                                PAGE_ID, "page-token", "app-secret", IG_ACCOUNT_ID));
        when(dedupService.tryClaim(ChannelType.MESSENGER, "m_MID-2")).thenReturn(true);

        ChannelSession session = mock(ChannelSession.class);
        when(session.isPaired()).thenReturn(true);
        when(session.getAgentId()).thenReturn(UUID.randomUUID());
        when(session.getSessionId()).thenReturn("session-1");
        when(session.getExternalId()).thenReturn("user-0001");
        when(channelSessionService.resolveOrCreate(
                        eq(ChannelType.MESSENGER), anyString(), anyString()))
                .thenReturn(session);

        when(workflowTriggerNodeService.bindingFor(WorkflowTriggerChannel.MESSENGER))
                .thenReturn(
                        Optional.of(
                                new WorkflowTriggerNodeService.TriggerBinding(
                                        UUID.randomUUID(), "node-1")));
        when(workflowTriggerNodeService.tryAcquireTriggerSlot(
                        eq(WorkflowTriggerChannel.MESSENGER), anyString()))
                .thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void bothTheWorkflowTriggerAndTheAgentEventPublishFireForTheSameMessage() {
        ResponseEntity<Void> response = controller.inbound(MESSENGER_BODY, "sha256=sig");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(workflowEventPublisher, times(1))
                .publishNewRun(
                        eq(WorkflowEventTypes.CHANNEL_MESSENGER_MESSAGE_RECEIVED), any(Map.class));
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
