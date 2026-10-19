package com.agenticai.core.omnichannel.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.omnichannel.discord.dto.DiscordInboundMessage;
import com.agenticai.core.omnichannel.session.AsyncInboundReply;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.InboundMessageDedupService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNodeService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerPort;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Inbound-dedup wiring for the Discord Gateway-relay webhook - the Wave-2 continuation of the exact
 * gap the Twilio/Telegram/Meta controllers already closed: discord.js can replay a {@code
 * MESSAGE_CREATE} after a Gateway session RESUME, and nothing here checked the message's own
 * snowflake id before processing.
 *
 * <p>Honest caveat, mirrored from {@link DiscordInboundMessage}'s javadoc: {@code
 * channels/discord}'s Node service does not forward {@code message.id} yet (cross-scope), so
 * production payloads carry a null {@code messageId} today and the claim is a no-op there - which
 * is exactly why this suite also pins the null-id path as "processed normally" (the dedup service's
 * null contract), not just the with-id paths.
 *
 * <p>Manual {@code mock(X.class)} + constructor-injection shape as the other channel webhook dedup
 * tests, including the unpaired-notice-already-sent session so the flow ends synchronously at
 * {@link ChannelSessionService#resolveOrCreate} - the first side effect past the dedup gate - and
 * never reaches the blocking agent-chat leg.
 */
class DiscordWebhookControllerDedupTest {

    private static final String SHARED_SECRET = "test-secret";
    private static final String APPLICATION_ID = "app-12345678";
    private static final String MESSAGE_ID = "1234567890123456789";

    private ChannelSessionService channelSessionService;
    private InboundMessageDedupService dedupService;
    private DiscordWebhookController controller;

    @BeforeEach
    void setUp() {
        TenantResolutionService tenantResolutionService = mock(TenantResolutionService.class);
        channelSessionService = mock(ChannelSessionService.class);
        dedupService = mock(InboundMessageDedupService.class);
        controller =
                new DiscordWebhookController(
                        SHARED_SECRET,
                        tenantResolutionService,
                        channelSessionService,
                        mock(AgentChatService.class),
                        mock(HumanApprovalGateService.class),
                        mock(WorkflowEventPublisher.class),
                        mock(WorkflowTriggerNodeService.class),
                        mock(WorkflowTriggerPort.class),
                        dedupService,
                        new AsyncInboundReply(Runnable::run));

        when(tenantResolutionService.resolveTenantSchema(
                        ExternalAccountProvider.DISCORD, APPLICATION_ID))
                .thenReturn(Optional.of("tenant_test"));

        // Unpaired sender whose pairing notice already went out - the
        // earliest fully-synchronous exit past the dedup gate, so "was this
        // message processed" reduces to "was resolveOrCreate called".
        ChannelSession session = mock(ChannelSession.class);
        when(session.isPaired()).thenReturn(false);
        when(session.isPairingNoticeSent()).thenReturn(true);
        when(channelSessionService.resolveOrCreate(
                        eq(ChannelType.DISCORD), anyString(), anyString()))
                .thenReturn(session);
    }

    @AfterEach
    void tearDown() {
        // The controller clears its own TenantContext in a finally block;
        // this is belt-and-braces against a failed assertion mid-request.
        TenantContext.clear();
    }

    @Test
    void duplicateDeliveryOfTheSameSnowflakeIdIsProcessedExactlyOnce() {
        when(dedupService.tryClaim(ChannelType.DISCORD, MESSAGE_ID)).thenReturn(true, false);
        DiscordInboundMessage message =
                new DiscordInboundMessage(
                        APPLICATION_ID, "chan-1234", "author-1", "hello", MESSAGE_ID);

        controller.inbound(message, SHARED_SECRET);
        controller.inbound(message, SHARED_SECRET);

        verify(channelSessionService, times(1))
                .resolveOrCreate(ChannelType.DISCORD, "chan-1234", "hello");
    }

    @Test
    void duplicateDeliveryStillAcksTheNodeServiceWith200WithoutTouchingTheSessionLayer() {
        when(dedupService.tryClaim(ChannelType.DISCORD, MESSAGE_ID)).thenReturn(false);
        DiscordInboundMessage message =
                new DiscordInboundMessage(
                        APPLICATION_ID, "chan-1234", "author-1", "hello", MESSAGE_ID);

        ResponseEntity<Void> response = controller.inbound(message, SHARED_SECRET);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(channelSessionService, never()).resolveOrCreate(any(), anyString(), anyString());
    }

    @Test
    void nullMessageIdIsProcessedNormallyPerTheDedupServicesNullContract() {
        // Today's production payload shape (channels/discord doesn't forward
        // message.id yet) - the real InboundMessageDedupService returns true
        // for a null id, and the message must never be dropped for lacking one.
        when(dedupService.tryClaim(eq(ChannelType.DISCORD), isNull())).thenReturn(true);
        DiscordInboundMessage message =
                new DiscordInboundMessage(APPLICATION_ID, "chan-1234", "author-1", "hello", null);

        ResponseEntity<Void> response = controller.inbound(message, SHARED_SECRET);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(channelSessionService).resolveOrCreate(ChannelType.DISCORD, "chan-1234", "hello");
    }
}
