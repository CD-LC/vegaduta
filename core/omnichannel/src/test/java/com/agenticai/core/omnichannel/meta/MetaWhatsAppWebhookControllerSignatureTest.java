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
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The Meta WhatsApp webhook with the REAL {@link MetaWhatsAppSignatureValidator} (the dedup test
 * mocks it): a bad, missing or wrong-secret signature must not reach the dedup claim or the session
 * layer, a tenant whose credential is gone processes nothing, and a replay of a correctly signed
 * delivery is processed once.
 */
class MetaWhatsAppWebhookControllerSignatureTest {

    private static final String PHONE_NUMBER_ID = "1234567890";
    private static final String APP_SECRET = "app-secret";
    private static final String RAW_BODY =
            """
{"entry":[{"changes":[{"value":{
  "metadata":{"phone_number_id":"1234567890"},
  "messages":[{"type":"text","id":"wamid.TEST-1","from":"15550001111","text":{"body":"hello"}}]
}}]}]}
""";

    private ChannelSessionService sessions;
    private InboundMessageDedupService dedup;
    private MetaWhatsAppCredentialService credentials;
    private MetaWhatsAppWebhookController controller;

    private static String sign(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256="
                    + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void setUp() {
        credentials = mock(MetaWhatsAppCredentialService.class);
        sessions = mock(ChannelSessionService.class);
        dedup = mock(InboundMessageDedupService.class);
        TenantResolutionService tenants = mock(TenantResolutionService.class);
        controller =
                new MetaWhatsAppWebhookController(
                        "verify-token",
                        new MetaWhatsAppSignatureValidator(),
                        credentials,
                        sessions,
                        mock(AgentChatService.class),
                        mock(HumanApprovalGateService.class),
                        new ChannelTextSends(List.of(new TwilioWhatsAppTextSend())),
                        tenants,
                        mock(WorkflowEventPublisher.class),
                        mock(WorkflowTriggerNodeService.class),
                        mock(WorkflowTriggerPort.class),
                        mock(AuditLogService.class),
                        dedup,
                        InboundChannelExecutor.synchronous());
        when(tenants.resolveTenantSchema(ExternalAccountProvider.META_WHATSAPP, PHONE_NUMBER_ID))
                .thenReturn(Optional.of("tenant_test"));
        when(credentials.resolveActive())
                .thenReturn(
                        new MetaWhatsAppCredentialService.DecryptedCredential(
                                PHONE_NUMBER_ID, "access-token", APP_SECRET, "waba-1"));
        when(dedup.tryClaim(ChannelType.WHATSAPP, "wamid.TEST-1")).thenReturn(true, false);
        ChannelSession session = mock(ChannelSession.class);
        when(session.isPaired()).thenReturn(false);
        when(session.isPairingNoticeSent()).thenReturn(true);
        when(sessions.resolveOrCreate(eq(ChannelType.WHATSAPP), anyString(), anyString()))
                .thenReturn(session);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void assertNothingProcessed() {
        verify(dedup, never()).tryClaim(any(ChannelType.class), any());
        verify(sessions, never()).resolveOrCreate(any(), anyString(), anyString());
    }

    @Test
    void aBadSignatureProcessesNothing() {
        ResponseEntity<Void> response =
                controller.inbound(RAW_BODY, sign("not-the-secret", RAW_BODY));

        assertEquals(
                HttpStatus.OK, response.getStatusCode()); // Meta disables the webhook on non-2xx
        assertNothingProcessed();
    }

    @Test
    void aMissingOrMalformedSignatureProcessesNothing() {
        controller.inbound(RAW_BODY, null);
        controller.inbound(RAW_BODY, "");
        controller.inbound(RAW_BODY, "sha256=");

        assertNothingProcessed();
    }

    @Test
    void aBodyChangedAfterSigningProcessesNothing() {
        controller.inbound(RAW_BODY.replace("hello", "hell0"), sign(APP_SECRET, RAW_BODY));

        assertNothingProcessed();
    }

    @Test
    void aTenantWithoutAnActiveCredentialProcessesNothing() {
        when(credentials.resolveActive()).thenThrow(new MetaWhatsAppNotConfiguredException());

        controller.inbound(RAW_BODY, sign(APP_SECRET, RAW_BODY));

        assertNothingProcessed();
    }

    @Test
    void aReplayOfACorrectlySignedDeliveryIsProcessedOnce() {
        String signature = sign(APP_SECRET, RAW_BODY);

        controller.inbound(RAW_BODY, signature);
        controller.inbound(RAW_BODY, signature);

        verify(sessions, times(1)).resolveOrCreate(ChannelType.WHATSAPP, "15550001111", "hello");
    }
}
