package com.agenticai.core.omnichannel.email;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.eventbus.WorkflowEventTypes;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.InboundMessageDedupService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerChannel;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNodeService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerPort;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import jakarta.mail.Message;
import jakarta.mail.internet.InternetAddress;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Post-blackout-audit fix verification (see {@code AgentEventTriggerDispatcher}'s javadoc). A
 * workflow bound to EMAIL must NOT silently suppress this channel's agent-event-trigger publish,
 * and vice versa - both must fire for the same paired-sender inbound email when a workflow binding
 * exists.
 *
 * <p>Exercises the private {@code handleMessage} directly via reflection - the surrounding {@code
 * pollMailbox} does a real IMAP {@code Store.connect}, which is out of scope for a unit test of the
 * fix in {@code handleMessage} itself.
 */
class EmailInboundPollingJobTriggerAndAgentEventBothFireTest {

    private WorkflowEventPublisher workflowEventPublisher;
    private WorkflowTriggerPort workflowTriggerPort;
    private AgentChatService agentChatService;
    private EmailInboundPollingJob job;

    @BeforeEach
    void setUp() {
        PgAdvisoryJobLock jobLock = mock(PgAdvisoryJobLock.class);
        TenantSchemaIterator schemaIterator = mock(TenantSchemaIterator.class);
        EmailCredentialRepository credentialRepository = mock(EmailCredentialRepository.class);
        EncryptionService encryptionService = mock(EncryptionService.class);
        ChannelSessionService channelSessionService = mock(ChannelSessionService.class);
        WorkflowTriggerNodeService workflowTriggerNodeService =
                mock(WorkflowTriggerNodeService.class);
        workflowEventPublisher = mock(WorkflowEventPublisher.class);
        workflowTriggerPort = mock(WorkflowTriggerPort.class);
        agentChatService = mock(AgentChatService.class);
        InboundMessageDedupService dedupService = mock(InboundMessageDedupService.class);
        // The mocked Message carries no Message-ID header, so the claim is
        // made with a null id - which must pass, per the null contract.
        when(dedupService.tryClaim(eq(ChannelType.EMAIL), nullable(String.class))).thenReturn(true);

        job =
                new EmailInboundPollingJob(
                        jobLock,
                        schemaIterator,
                        credentialRepository,
                        encryptionService,
                        channelSessionService,
                        agentChatService,
                        mock(HumanApprovalGateService.class),
                        workflowEventPublisher,
                        workflowTriggerNodeService,
                        workflowTriggerPort,
                        dedupService);

        ChannelSession session = mock(ChannelSession.class);
        when(session.isPaired()).thenReturn(true);
        when(session.getAgentId()).thenReturn(UUID.randomUUID());
        when(session.getSessionId()).thenReturn("session-1");
        when(session.getExternalId()).thenReturn("user@example.com");
        when(channelSessionService.resolveOrCreate(eq(ChannelType.EMAIL), anyString(), anyString()))
                .thenReturn(session);

        when(workflowTriggerNodeService.bindingFor(WorkflowTriggerChannel.EMAIL))
                .thenReturn(
                        Optional.of(
                                new WorkflowTriggerNodeService.TriggerBinding(
                                        UUID.randomUUID(), "node-1")));
        when(workflowTriggerNodeService.tryAcquireTriggerSlot(
                        eq(WorkflowTriggerChannel.EMAIL), anyString()))
                .thenReturn(true);
    }

    @Test
    void bothTheWorkflowTriggerAndTheAgentEventPublishFireForTheSameMessage() throws Exception {
        Message message = mock(Message.class);
        when(message.getFrom())
                .thenReturn(
                        new InternetAddress[] {new InternetAddress("user@example.com", "User")});
        when(message.getSubject()).thenReturn("Hello");
        when(message.isMimeType("text/plain")).thenReturn(true);
        when(message.getContent()).thenReturn("hello");

        Method handleMessage =
                EmailInboundPollingJob.class.getDeclaredMethod("handleMessage", Message.class);
        handleMessage.setAccessible(true);
        handleMessage.invoke(job, message);

        verify(workflowEventPublisher, times(1))
                .publishNewRun(
                        eq(WorkflowEventTypes.CHANNEL_EMAIL_MESSAGE_RECEIVED), any(Map.class));
        verify(workflowTriggerPort, times(1))
                .triggerAsync(any(UUID.class), eq("node-1"), eq("hello"), anyString());
        verify(agentChatService, never()).chat(any(TurnContext.class), any(ChatTurnRequest.class));
    }
}
