package com.agenticai.core.omnichannel.discord;

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
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * Post-blackout-audit fix verification (see {@code AgentEventTriggerDispatcher}'s javadoc). A
 * workflow bound to DISCORD must NOT silently suppress this channel's agent-event-trigger publish,
 * and the publish must not suppress the workflow trigger either - both must fire for the same
 * paired-sender inbound message when a workflow binding exists for the channel.
 */
class DiscordWebhookControllerTriggerAndAgentEventBothFireTest {

    private static final String SHARED_SECRET = "test-secret";
    private static final String APPLICATION_ID = "app-12345678";

    private WorkflowEventPublisher workflowEventPublisher;
    private WorkflowTriggerPort workflowTriggerPort;
    private AgentChatService agentChatService;
    private DiscordWebhookController controller;

    @BeforeEach
    void setUp() {
        TenantResolutionService tenantResolutionService = mock(TenantResolutionService.class);
        ChannelSessionService channelSessionService = mock(ChannelSessionService.class);
        InboundMessageDedupService dedupService = mock(InboundMessageDedupService.class);
        WorkflowTriggerNodeService workflowTriggerNodeService =
                mock(WorkflowTriggerNodeService.class);
        workflowEventPublisher = mock(WorkflowEventPublisher.class);
        workflowTriggerPort = mock(WorkflowTriggerPort.class);
        agentChatService = mock(AgentChatService.class);

        controller =
                new DiscordWebhookController(
                        SHARED_SECRET,
                        tenantResolutionService,
                        channelSessionService,
                        agentChatService,
                        mock(HumanApprovalGateService.class),
                        workflowEventPublisher,
                        workflowTriggerNodeService,
                        workflowTriggerPort,
                        dedupService,
                        new AsyncInboundReply(Runnable::run));

        when(tenantResolutionService.resolveTenantSchema(
                        ExternalAccountProvider.DISCORD, APPLICATION_ID))
                .thenReturn(Optional.of("tenant_test"));
        when(dedupService.tryClaim(eq(ChannelType.DISCORD), any())).thenReturn(true);

        ChannelSession session = mock(ChannelSession.class);
        when(session.isPaired()).thenReturn(true);
        when(session.getAgentId()).thenReturn(UUID.randomUUID());
        when(session.getSessionId()).thenReturn("session-1");
        when(session.getExternalId()).thenReturn("chan-1234");
        when(channelSessionService.resolveOrCreate(
                        eq(ChannelType.DISCORD), anyString(), anyString()))
                .thenReturn(session);

        when(workflowTriggerNodeService.bindingFor(WorkflowTriggerChannel.DISCORD))
                .thenReturn(
                        Optional.of(
                                new WorkflowTriggerNodeService.TriggerBinding(
                                        UUID.randomUUID(), "node-1")));
        when(workflowTriggerNodeService.tryAcquireTriggerSlot(
                        eq(WorkflowTriggerChannel.DISCORD), anyString()))
                .thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void bothTheWorkflowTriggerAndTheAgentEventPublishFireForTheSameMessage() {
        DiscordInboundMessage message =
                new DiscordInboundMessage(
                        APPLICATION_ID, "chan-1234", "author-1", "hello", "msg-1");

        ResponseEntity<Void> response = controller.inbound(message, SHARED_SECRET);

        assertEquals(org.springframework.http.HttpStatus.OK, response.getStatusCode());
        verify(workflowEventPublisher, times(1))
                .publishNewRun(
                        eq(WorkflowEventTypes.CHANNEL_DISCORD_MESSAGE_RECEIVED), any(Map.class));
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
