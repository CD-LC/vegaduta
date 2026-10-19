package com.agenticai.core.omnichannel.x;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.InboundMessageDedupService;
import com.agenticai.core.omnichannel.trigger.InboundTriggerContext;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerChannel;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNodeService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerPort;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Post-blackout-audit fix verification (see {@code AgentEventTriggerDispatcher}'s javadoc). A
 * workflow bound to X must NOT silently suppress this channel's agent-event-trigger publish, and
 * vice versa - both must fire for the same paired-sender inbound DM when a workflow binding exists.
 */
class XDmPollingJobTriggerAndAgentEventBothFireTest {

    private static final String SCHEMA = "tenant_test";

    private WorkflowEventPublisher workflowEventPublisher;
    private WorkflowTriggerPort workflowTriggerPort;
    private AgentChatService agentChatService;
    private XDmPollingJob job;

    @BeforeEach
    void setUp() {
        PgAdvisoryJobLock jobLock = mock(PgAdvisoryJobLock.class);
        TenantSchemaIterator schemaIterator = mock(TenantSchemaIterator.class);
        XOAuthService oauthService = mock(XOAuthService.class);
        XApiClient apiClient = mock(XApiClient.class);
        ChannelSessionService channelSessionService = mock(ChannelSessionService.class);
        WorkflowTriggerNodeService workflowTriggerNodeService =
                mock(WorkflowTriggerNodeService.class);
        workflowEventPublisher = mock(WorkflowEventPublisher.class);
        workflowTriggerPort = mock(WorkflowTriggerPort.class);
        agentChatService = mock(AgentChatService.class);
        InboundMessageDedupService dedupService = mock(InboundMessageDedupService.class);
        when(dedupService.tryClaim(eq(ChannelType.X), anyString())).thenReturn(true);

        job =
                new XDmPollingJob(
                        jobLock,
                        schemaIterator,
                        oauthService,
                        apiClient,
                        channelSessionService,
                        agentChatService,
                        mock(HumanApprovalGateService.class),
                        workflowEventPublisher,
                        workflowTriggerNodeService,
                        workflowTriggerPort,
                        dedupService);

        // Run the job lock's/schema iterator's callback synchronously and inline.
        when(jobLock.tryRunExclusive(anyString(), any(Runnable.class)))
                .thenAnswer(
                        invocation -> {
                            invocation.getArgument(1, Runnable.class).run();
                            return true;
                        });
        doAnswer(
                        invocation -> {
                            invocation.getArgument(0, Consumer.class).accept(SCHEMA);
                            return null;
                        })
                .when(schemaIterator)
                .forEachActiveTenantSchema(any());

        XCredential credential = mock(XCredential.class);
        when(credential.getLastDmEventId()).thenReturn("cursor-1");
        when(credential.getXUserId()).thenReturn("own-user-1");
        when(oauthService.activeCredential()).thenReturn(Optional.of(credential));
        when(oauthService.currentAccessToken()).thenReturn("access-token");

        XApiClient.DmEvent event =
                new XApiClient.DmEvent("dm-evt-1", "conv-1", "sender-1", "hello");
        when(apiClient.fetchNewMessagesSince("access-token", "cursor-1", "own-user-1"))
                .thenReturn(new XApiClient.PollResult(List.of(event), "cursor-2"));

        ChannelSession session = mock(ChannelSession.class);
        when(session.isPaired()).thenReturn(true);
        when(session.getAgentId()).thenReturn(UUID.randomUUID());
        when(session.getSessionId()).thenReturn("session-1");
        when(session.getExternalId()).thenReturn("conv-1");
        when(channelSessionService.resolveOrCreate(eq(ChannelType.X), anyString(), anyString()))
                .thenReturn(session);

        when(workflowTriggerNodeService.bindingFor(WorkflowTriggerChannel.X))
                .thenReturn(
                        Optional.of(
                                new WorkflowTriggerNodeService.TriggerBinding(
                                        UUID.randomUUID(), "node-1")));
        when(workflowTriggerNodeService.tryAcquireTriggerSlot(
                        eq(WorkflowTriggerChannel.X), anyString()))
                .thenReturn(true);
    }

    @Test
    void bothTheWorkflowTriggerAndTheAgentEventPublishFireForTheSameDm() {
        job.pollAllTenants();

        verify(workflowEventPublisher, times(1))
                .publishNewRun(eq(WorkflowEventTypes.CHANNEL_X_MESSAGE_RECEIVED), any(Map.class));
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
