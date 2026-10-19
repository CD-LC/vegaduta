package com.agenticai.core.omnichannel.email;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.InboundMessageDedupService;
import com.agenticai.core.omnichannel.session.TurnContextProbe;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNodeService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerPort;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import jakarta.mail.Message;
import jakarta.mail.internet.InternetAddress;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ADR-0022 stage 4: the IMAP polling job runs each message under the tenant the {@link
 * TenantSchemaIterator} put on the polling thread, and the {@code TurnContext} it builds is that
 * same instance. Like the sibling tests, drives the private {@code handleMessage} by reflection
 * because {@code pollMailbox} opens a real IMAP store.
 */
class EmailInboundPollingJobTurnContextTest {

    private TurnContextProbe probe;
    private EmailInboundPollingJob job;
    private TenantContext polledTenant;

    @BeforeEach
    void setUp() {
        ChannelSessionService sessions = mock(ChannelSessionService.class);
        InboundMessageDedupService dedup = mock(InboundMessageDedupService.class);
        when(dedup.tryClaim(eq(ChannelType.EMAIL), nullable(String.class))).thenReturn(true);
        AgentChatService chat = mock(AgentChatService.class);
        probe = TurnContextProbe.on(chat);
        job =
                new EmailInboundPollingJob(
                        mock(PgAdvisoryJobLock.class),
                        mock(TenantSchemaIterator.class),
                        mock(EmailCredentialRepository.class),
                        mock(EncryptionService.class),
                        sessions,
                        chat,
                        mock(HumanApprovalGateService.class),
                        mock(WorkflowEventPublisher.class),
                        mock(WorkflowTriggerNodeService.class),
                        mock(WorkflowTriggerPort.class),
                        dedup);
        ChannelSession session = mock(ChannelSession.class);
        when(session.isPaired()).thenReturn(true);
        when(session.getAgentId()).thenReturn(UUID.randomUUID());
        when(session.getSessionId()).thenReturn("session-1");
        when(session.getExternalId()).thenReturn("user@example.com");
        when(sessions.resolveOrCreate(eq(ChannelType.EMAIL), anyString(), anyString()))
                .thenReturn(session);
        polledTenant = TenantContext.of("tenant_email", "system:email-poll", Set.of());
        TenantContext.set(polledTenant);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void theTurnContextIsTheTenantTheIteratorPutOnThePollingThread() throws Exception {
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

        probe.assertTenant("tenant_email", "system:email-poll");
        assertSame(polledTenant, probe.context().tenantContext().orElse(null));
    }
}
