package com.agenticai.core.omnichannel.email;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.InboundMessageDedupService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNodeService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerPort;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import jakarta.mail.Message;
import jakarta.mail.internet.InternetAddress;
import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Inbound-dedup wiring for the IMAP polling job - closing the coordination
 * log's 2026-08-26 finding that the two polling channels never called {@link
 * InboundMessageDedupService} at all. The mark-as-{@code \Seen} guard only
 * holds if the flag write actually lands: a connection drop after {@code
 * handleMessage} but before {@code setFlag} (or a crash mid-batch) leaves the
 * message UNSEEN and the next tick would re-reply to it. The dedup key is the
 * RFC 5322 {@code Message-ID} header - provider-native and stable across
 * re-fetches, unlike IMAP UIDs or a content hash.
 *
 * <p>Exercises the private {@code handleMessage} directly via reflection,
 * same reasoning as {@link EmailInboundPollingJobTriggerAndAgentEventBothFireTest}:
 * the surrounding {@code pollMailbox} does a real IMAP {@code Store.connect}.
 * Same unpaired-notice-already-sent session shape as
 * {@link com.agenticai.core.omnichannel.discord.DiscordWebhookControllerDedupTest},
 * so the flow ends synchronously at {@link ChannelSessionService#resolveOrCreate}
 * - the first side effect past the dedup gate.
 */
class EmailInboundPollingJobDedupTest {

    private static final String MESSAGE_ID = "<CAF12345.67890@mail.example.com>";

    private ChannelSessionService channelSessionService;
    private InboundMessageDedupService dedupService;
    private EmailInboundPollingJob job;

    @BeforeEach
    void setUp() {
        channelSessionService = mock(ChannelSessionService.class);
        dedupService = mock(InboundMessageDedupService.class);

        job = new EmailInboundPollingJob(
            mock(PgAdvisoryJobLock.class),
            mock(TenantSchemaIterator.class),
            mock(EmailCredentialRepository.class),
            mock(EncryptionService.class),
            channelSessionService,
            mock(AgentChatService.class),
            mock(HumanApprovalGateService.class),
            mock(WorkflowEventPublisher.class),
            mock(WorkflowTriggerNodeService.class),
            mock(WorkflowTriggerPort.class),
            dedupService
        );

        // Unpaired sender whose pairing notice already went out - the
        // earliest fully-synchronous exit past the dedup gate, so "was this
        // message processed" reduces to "was resolveOrCreate called".
        ChannelSession session = mock(ChannelSession.class);
        when(session.isPaired()).thenReturn(false);
        when(session.isPairingNoticeSent()).thenReturn(true);
        when(channelSessionService.resolveOrCreate(eq(ChannelType.EMAIL), anyString(), anyString()))
            .thenReturn(session);
    }

    private Message mailMessage(String[] messageIdHeader) throws Exception {
        Message message = mock(Message.class);
        when(message.getFrom()).thenReturn(new InternetAddress[] {new InternetAddress("user@example.com", "User")});
        when(message.getSubject()).thenReturn("Hello");
        when(message.isMimeType("text/plain")).thenReturn(true);
        when(message.getContent()).thenReturn("hello");
        when(message.getHeader("Message-ID")).thenReturn(messageIdHeader);
        return message;
    }

    private void invokeHandleMessage(Message message) throws Exception {
        Method handleMessage = EmailInboundPollingJob.class.getDeclaredMethod("handleMessage", Message.class);
        handleMessage.setAccessible(true);
        handleMessage.invoke(job, message);
    }

    @Test
    void reFetchOfTheSameMessageIdAcrossTicksIsProcessedExactlyOnce() throws Exception {
        when(dedupService.tryClaim(ChannelType.EMAIL, MESSAGE_ID)).thenReturn(true, false);
        Message message = mailMessage(new String[] {MESSAGE_ID});

        invokeHandleMessage(message);
        invokeHandleMessage(message);

        verify(channelSessionService, times(1)).resolveOrCreate(ChannelType.EMAIL, "user@example.com", "Hello");
    }

    @Test
    void duplicateDeliveryNeverTouchesTheSessionLayer() throws Exception {
        when(dedupService.tryClaim(ChannelType.EMAIL, MESSAGE_ID)).thenReturn(false);

        invokeHandleMessage(mailMessage(new String[] {MESSAGE_ID}));

        verify(channelSessionService, never()).resolveOrCreate(any(), anyString(), anyString());
    }

    @Test
    void missingMessageIdHeaderIsProcessedNormallyPerTheDedupServicesNullContract() throws Exception {
        // A message with no Message-ID (a broken or hostile MTA) must never
        // be dropped for lacking one - the real InboundMessageDedupService
        // returns true for a null id.
        when(dedupService.tryClaim(eq(ChannelType.EMAIL), isNull())).thenReturn(true);

        invokeHandleMessage(mailMessage(null));

        verify(channelSessionService).resolveOrCreate(ChannelType.EMAIL, "user@example.com", "Hello");
    }
}
