package com.agenticai.core.omnichannel.slack;

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

/**
 * Post-blackout-audit fix verification (see {@code AgentEventTriggerDispatcher}'s javadoc, "honesty
 * items" - the "channel events do not fire when a workflow binding exists" item is now resolved). A
 * workflow bound to SLACK must NOT silently suppress this channel's agent-event-trigger publish,
 * and the publish must not suppress the workflow trigger either - both {@link
 * WorkflowEventPublisher#publishNewRun} and {@link WorkflowTriggerPort#triggerAsync} must fire for
 * the same paired-sender inbound message when a workflow binding exists for the channel.
 */
class SlackWebhookControllerTriggerAndAgentEventBothFireTest {

    private static final String TEAM_ID = "T12345678";
    private static final String EVENT_BODY =
            """
            {"team_id":"T12345678","type":"event_callback","event_id":"Ev99999999",
             "event":{"type":"message","user":"U11111111","channel":"C22222222","text":"hello"}}
            """;

    private WorkflowEventPublisher workflowEventPublisher;
    private WorkflowTriggerPort workflowTriggerPort;
    private AgentChatService agentChatService;
    private SlackWebhookController controller;

    @BeforeEach
    void setUp() {
        SlackSignatureValidator signatureValidator = mock(SlackSignatureValidator.class);
        SlackCredentialService credentialService = mock(SlackCredentialService.class);
        TenantResolutionService tenantResolutionService = mock(TenantResolutionService.class);
        ChannelSessionService channelSessionService = mock(ChannelSessionService.class);
        InboundMessageDedupService dedupService = mock(InboundMessageDedupService.class);
        WorkflowTriggerNodeService workflowTriggerNodeService =
                mock(WorkflowTriggerNodeService.class);
        workflowEventPublisher = mock(WorkflowEventPublisher.class);
        workflowTriggerPort = mock(WorkflowTriggerPort.class);
        agentChatService = mock(AgentChatService.class);

        controller =
                new SlackWebhookController(
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

        when(tenantResolutionService.resolveTenantSchema(ExternalAccountProvider.SLACK, TEAM_ID))
                .thenReturn(Optional.of("tenant_test"));
        when(credentialService.resolveActive())
                .thenReturn(
                        new SlackCredentialService.DecryptedCredential(
                                TEAM_ID, "UBOT00000", "xoxb-token", "signing-secret"));
        when(dedupService.tryClaim(ChannelType.SLACK, "Ev99999999")).thenReturn(true);

        ChannelSession session = mock(ChannelSession.class);
        when(session.isPaired()).thenReturn(true);
        when(session.getAgentId()).thenReturn(UUID.randomUUID());
        when(session.getSessionId()).thenReturn("session-1");
        when(session.getExternalId()).thenReturn("C22222222");
        when(channelSessionService.resolveOrCreate(eq(ChannelType.SLACK), anyString(), anyString()))
                .thenReturn(session);

        // A workflow bound to SLACK - this used to short-circuit the publish
        // below via an early return, so the agent-event trigger silently
        // never fired whenever a workflow binding existed for the channel.
        UUID workflowId = UUID.randomUUID();
        when(workflowTriggerNodeService.bindingFor(WorkflowTriggerChannel.SLACK))
                .thenReturn(
                        Optional.of(
                                new WorkflowTriggerNodeService.TriggerBinding(
                                        workflowId, "node-1")));
        when(workflowTriggerNodeService.tryAcquireTriggerSlot(
                        eq(WorkflowTriggerChannel.SLACK), anyString()))
                .thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void bothTheWorkflowTriggerAndTheAgentEventPublishFireForTheSameMessage() {
        controller.inbound(EVENT_BODY, "1717171717", "v0=sig");

        // The agent-event trigger must fire even though a workflow is bound.
        verify(workflowEventPublisher, times(1))
                .publishNewRun(
                        eq(WorkflowEventTypes.CHANNEL_SLACK_MESSAGE_RECEIVED), any(Map.class));

        // The workflow trigger must still fire too - the two are no longer
        // mutually exclusive.
        verify(workflowTriggerPort, times(1))
                .triggerAsync(
                        any(UUID.class),
                        eq("node-1"),
                        eq("hello"),
                        anyString(),
                        any(InboundTriggerContext.class));

        // The workflow binding still takes over from the default agent-chat
        // reply path - that precedence is unchanged by this fix.
        verify(agentChatService, never()).chat(any(TurnContext.class), any(ChatTurnRequest.class));
    }

    @Test
    void publishFiresExactlyOnceNotTwice() {
        controller.inbound(EVENT_BODY, "1717171717", "v0=sig");

        // Reordering the publish ahead of the workflow-binding branch must
        // not turn it into a double-fire down either path.
        verify(workflowEventPublisher, times(1)).publishNewRun(anyString(), any(Map.class));
    }
}
