package com.agenticai.core.omnichannel.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.InboundMessageDedupService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNodeService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerPort;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * A mailbox saved before the outbound policy existed (or one the operator's allow-list no longer
 * covers) must be skipped by the poller, not dialled: the stored host is checked on every poll.
 */
class EmailInboundPollingJobEndpointTest {

    @Test
    @SuppressWarnings("unchecked")
    void aStoredMailboxPointingAtAnInternalHostIsSkippedWithoutConnecting() throws Exception {
        PgAdvisoryJobLock lock = mock(PgAdvisoryJobLock.class);
        doAnswer(
                        inv -> {
                            ((Runnable) inv.getArgument(1)).run();
                            return true;
                        })
                .when(lock)
                .tryRunExclusive(anyString(), any(Runnable.class));
        TenantSchemaIterator schemas = mock(TenantSchemaIterator.class);
        doAnswer(
                        inv -> {
                            ((Consumer<String>) inv.getArgument(0)).accept("tenant_test");
                            return null;
                        })
                .when(schemas)
                .forEachActiveTenantSchema(any());
        EmailCredentialRepository repository = mock(EmailCredentialRepository.class);
        EncryptionService encryption = mock(EncryptionService.class);
        when(encryption.decrypt(any())).thenReturn("pw");

        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            listener.setSoTimeout(500);
            EmailCredential credential =
                    new EmailCredential("127.0.0.1", listener.getLocalPort(), "user", "enc", false);
            when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(credential));
            EmailInboundPollingJob job =
                    new EmailInboundPollingJob(
                            lock,
                            schemas,
                            repository,
                            encryption,
                            mock(ChannelSessionService.class),
                            mock(AgentChatService.class),
                            mock(HumanApprovalGateService.class),
                            mock(WorkflowEventPublisher.class),
                            mock(WorkflowTriggerNodeService.class),
                            mock(WorkflowTriggerPort.class),
                            mock(InboundMessageDedupService.class));

            Logger jobLog = (Logger) LoggerFactory.getLogger(EmailInboundPollingJob.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            jobLog.addAppender(appender);
            try {
                job.pollAllTenants();
            } finally {
                jobLog.detachAppender(appender);
            }

            assertThrows(
                    SocketTimeoutException.class, listener::accept, "no connection may be opened");
            verify(repository, never()).save(any());
            // The skip is logged with a fixed text: the refusal reason names the tenant-typed
            // host, so neither it nor the exception (and its stack trace) may reach the log.
            assertEquals(1, appender.list.size());
            ILoggingEvent skipped = appender.list.get(0);
            assertTrue(
                    skipped.getFormattedMessage().startsWith("Skipping email poll for mailbox"),
                    skipped.getFormattedMessage());
            assertFalse(
                    skipped.getFormattedMessage().contains("127.0.0.1"),
                    skipped.getFormattedMessage());
            assertTrue(
                    skipped.getFormattedMessage().contains("ChannelConfigurationException"),
                    skipped.getFormattedMessage());
            assertEquals(null, skipped.getThrowableProxy(), "no exception text or stack trace");
        }
    }
}
