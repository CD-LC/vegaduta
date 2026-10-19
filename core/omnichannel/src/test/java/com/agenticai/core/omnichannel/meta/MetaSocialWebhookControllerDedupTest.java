package com.agenticai.core.omnichannel.meta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
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
 * Inbound-dedup wiring for the Messenger/Instagram webhook - the Wave-2 continuation of the gap
 * {@link MetaWhatsAppWebhookControllerDedupTest} already pins for the WhatsApp Cloud API surface:
 * Meta's Messenger Platform retries a delivery on anything but a fast 2xx, so a retried delivery
 * double-processed the same {@code message.mid} (second agent reply, second workflow trigger,
 * second pairing notice). The claim is per messaging event, keyed by {@code mid} under the event's
 * own {@link ChannelType} (MESSENGER vs INSTAGRAM - two id spaces, two scopes).
 *
 * <p>Same manual {@code mock(X.class)} + constructor-injection shape as that sibling suite,
 * including the unpaired-notice-already-sent session so the flow ends synchronously at {@link
 * ChannelSessionService#resolveOrCreate}, the first side effect past the dedup gate.
 */
class MetaSocialWebhookControllerDedupTest {

    private static final String PAGE_ID = "page-1234";
    private static final String IG_ACCOUNT_ID = "ig-5678";
    private static final String MESSENGER_BODY =
            """
            {"object":"page","entry":[{"id":"page-1234","messaging":[
              {"sender":{"id":"user-0001"},"message":{"mid":"m_MID-1","text":"hello"}}
            ]}]}
            """;
    private static final String INSTAGRAM_BODY =
            """
            {"object":"instagram","entry":[{"id":"ig-5678","messaging":[
              {"sender":{"id":"user-0001"},"message":{"mid":"m_MID-1","text":"hello"}}
            ]}]}
            """;

    private ChannelSessionService channelSessionService;
    private InboundMessageDedupService dedupService;
    private MetaSocialWebhookController controller;

    @BeforeEach
    void setUp() {
        MetaWhatsAppSignatureValidator signatureValidator =
                mock(MetaWhatsAppSignatureValidator.class);
        MetaSocialCredentialService credentialService = mock(MetaSocialCredentialService.class);
        TenantResolutionService tenantResolutionService = mock(TenantResolutionService.class);
        channelSessionService = mock(ChannelSessionService.class);
        dedupService = mock(InboundMessageDedupService.class);
        controller =
                new MetaSocialWebhookController(
                        "verify-token",
                        signatureValidator,
                        credentialService,
                        channelSessionService,
                        mock(AgentChatService.class),
                        mock(HumanApprovalGateService.class),
                        tenantResolutionService,
                        mock(WorkflowEventPublisher.class),
                        mock(WorkflowTriggerNodeService.class),
                        mock(WorkflowTriggerPort.class),
                        dedupService,
                        new AsyncInboundReply(Runnable::run));

        when(tenantResolutionService.resolveTenantSchema(
                        ExternalAccountProvider.META_MESSENGER, PAGE_ID))
                .thenReturn(Optional.of("tenant_test"));
        when(tenantResolutionService.resolveTenantSchema(
                        ExternalAccountProvider.META_INSTAGRAM, IG_ACCOUNT_ID))
                .thenReturn(Optional.of("tenant_test"));
        when(credentialService.resolveActive())
                .thenReturn(
                        new MetaSocialCredentialService.DecryptedCredential(
                                PAGE_ID, "page-token", "app-secret", IG_ACCOUNT_ID));

        // Unpaired sender whose pairing notice already went out - the
        // earliest fully-synchronous exit past the dedup gate, so "was this
        // message processed" reduces to "was resolveOrCreate called".
        ChannelSession session = mock(ChannelSession.class);
        when(session.isPaired()).thenReturn(false);
        when(session.isPairingNoticeSent()).thenReturn(true);
        when(channelSessionService.resolveOrCreate(
                        any(ChannelType.class), anyString(), anyString()))
                .thenReturn(session);
    }

    @AfterEach
    void tearDown() {
        // The controller clears its own TenantContext in a finally block;
        // this is belt-and-braces against a failed assertion mid-request.
        TenantContext.clear();
    }

    @Test
    void duplicateDeliveryOfTheSameMessengerMidIsProcessedExactlyOnce() {
        when(dedupService.tryClaim(ChannelType.MESSENGER, "m_MID-1")).thenReturn(true, false);

        controller.inbound(MESSENGER_BODY, "sha256=sig");
        controller.inbound(MESSENGER_BODY, "sha256=sig");

        verify(channelSessionService, times(1))
                .resolveOrCreate(ChannelType.MESSENGER, "user-0001", "hello");
    }

    @Test
    void duplicateDeliveryStillAcksMetaWith200WithoutTouchingTheSessionLayer() {
        when(dedupService.tryClaim(ChannelType.MESSENGER, "m_MID-1")).thenReturn(false);

        ResponseEntity<Void> response = controller.inbound(MESSENGER_BODY, "sha256=sig");

        // Meta disables the webhook subscription after repeated non-2xx -
        // a duplicate must ack exactly like a success.
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(channelSessionService, never())
                .resolveOrCreate(any(ChannelType.class), anyString(), anyString());
    }

    @Test
    void instagramEventsClaimUnderTheInstagramScopeNotMessenger() {
        // Messenger and Instagram mids live in separate id spaces even
        // though one controller handles both payload shapes - the claim must
        // be scoped by the event's own ChannelType, or a mid collision
        // across the two surfaces would silently swallow a message.
        when(dedupService.tryClaim(ChannelType.INSTAGRAM, "m_MID-1")).thenReturn(true);

        controller.inbound(INSTAGRAM_BODY, "sha256=sig");

        verify(dedupService).tryClaim(ChannelType.INSTAGRAM, "m_MID-1");
        verify(channelSessionService).resolveOrCreate(ChannelType.INSTAGRAM, "user-0001", "hello");
    }
}
