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
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.omnichannel.outbound.ChannelTextSends;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.InboundChannelExecutor;
import com.agenticai.core.omnichannel.session.InboundMessageDedupService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNodeService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerPort;
import com.agenticai.core.omnichannel.twilio.TwilioWhatsAppTextSend;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Inbound-dedup wiring for the Meta Cloud API webhook - the gap this closes: {@link
 * InboundMessageDedupService} was wired into the Twilio and Telegram webhook controllers but never
 * into this one, even though Meta retries a webhook delivery on anything but a fast 2xx, so a
 * retried delivery double-processed the same wamid (second agent reply, second workflow trigger,
 * second pairing notice).
 *
 * <p>Same manual {@code mock(X.class)} + constructor-injection shape as {@link
 * com.agenticai.core.omnichannel.session.InboundMessageDedupServiceTest} - no Spring context, no
 * MockMvc. The processed path is pinned at {@link ChannelSessionService#resolveOrCreate}, the first
 * side effect past the dedup gate, and the stubbed session is unpaired-with-notice-already-sent so
 * the flow ends synchronously there - deliberately never reaching the async {@code
 * CompletableFuture.runAsync} agent-chat leg, which would make these assertions racy.
 */
class MetaWhatsAppWebhookControllerDedupTest {

    private static final String PHONE_NUMBER_ID = "1234567890";
    private static final String RAW_BODY =
            """
{"entry":[{"changes":[{"value":{
  "metadata":{"phone_number_id":"1234567890"},
  "messages":[{"type":"text","id":"wamid.TEST-1","from":"15550001111","text":{"body":"hello"}}]
}}]}]}
""";

    private ChannelSessionService channelSessionService;
    private TenantResolutionService tenantResolutionService;
    private MetaWhatsAppCredentialService credentialService;
    private InboundMessageDedupService dedupService;
    private MetaWhatsAppWebhookController controller;

    @BeforeEach
    void setUp() {
        MetaWhatsAppSignatureValidator signatureValidator =
                mock(MetaWhatsAppSignatureValidator.class);
        credentialService = mock(MetaWhatsAppCredentialService.class);
        channelSessionService = mock(ChannelSessionService.class);
        tenantResolutionService = mock(TenantResolutionService.class);
        dedupService = mock(InboundMessageDedupService.class);
        controller =
                new MetaWhatsAppWebhookController(
                        "verify-token",
                        signatureValidator,
                        credentialService,
                        channelSessionService,
                        mock(AgentChatService.class),
                        mock(HumanApprovalGateService.class),
                        new ChannelTextSends(List.of(new TwilioWhatsAppTextSend())),
                        tenantResolutionService,
                        mock(WorkflowEventPublisher.class),
                        mock(WorkflowTriggerNodeService.class),
                        mock(WorkflowTriggerPort.class),
                        mock(AuditLogService.class),
                        dedupService,
                        InboundChannelExecutor.synchronous());

        when(tenantResolutionService.resolveTenantSchema(
                        ExternalAccountProvider.META_WHATSAPP, PHONE_NUMBER_ID))
                .thenReturn(Optional.of("tenant_test"));
        when(credentialService.resolveActive())
                .thenReturn(
                        new MetaWhatsAppCredentialService.DecryptedCredential(
                                PHONE_NUMBER_ID, "access-token", "app-secret", "waba-1"));

        // Unpaired sender whose pairing notice already went out - the
        // earliest fully-synchronous exit past the dedup gate, so "was this
        // message processed" reduces to "was resolveOrCreate called".
        ChannelSession session = mock(ChannelSession.class);
        when(session.isPaired()).thenReturn(false);
        when(session.isPairingNoticeSent()).thenReturn(true);
        when(channelSessionService.resolveOrCreate(
                        eq(ChannelType.WHATSAPP), anyString(), anyString()))
                .thenReturn(session);
    }

    @AfterEach
    void tearDown() {
        // The controller clears its own TenantContext in a finally block;
        // this is belt-and-braces against a failed assertion mid-request.
        TenantContext.clear();
    }

    @Test
    void duplicateDeliveryOfTheSameWamidIsProcessedExactlyOnce() {
        when(dedupService.tryClaim(ChannelType.WHATSAPP, "wamid.TEST-1")).thenReturn(true, false);

        controller.inbound(RAW_BODY, "sha256=sig");
        controller.inbound(RAW_BODY, "sha256=sig");

        verify(channelSessionService, times(1))
                .resolveOrCreate(ChannelType.WHATSAPP, "15550001111", "hello");
    }

    @Test
    void duplicateDeliveryStillAcksMetaWith200WithoutTouchingTheSessionLayer() {
        when(dedupService.tryClaim(ChannelType.WHATSAPP, "wamid.TEST-1")).thenReturn(false);

        ResponseEntity<Void> response = controller.inbound(RAW_BODY, "sha256=sig");

        // Meta disables the webhook subscription after repeated non-2xx -
        // a duplicate must ack exactly like a success.
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(channelSessionService, never()).resolveOrCreate(any(), anyString(), anyString());
    }

    @Test
    void firstDeliveryClaimsTheWamidBeforeProcessing() {
        when(dedupService.tryClaim(ChannelType.WHATSAPP, "wamid.TEST-1")).thenReturn(true);

        ResponseEntity<Void> response = controller.inbound(RAW_BODY, "sha256=sig");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(dedupService).tryClaim(ChannelType.WHATSAPP, "wamid.TEST-1");
        verify(channelSessionService).resolveOrCreate(ChannelType.WHATSAPP, "15550001111", "hello");
    }
}
