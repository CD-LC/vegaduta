package com.agenticai.core.omnichannel.meta;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import com.agenticai.core.omnichannel.session.TurnContextProbe;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNodeService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerPort;
import com.agenticai.core.omnichannel.twilio.TwilioWhatsAppTextSend;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ADR-0022 stage 4: the direct-Meta WhatsApp webhook resolves the tenant from the phone-number id
 * and re-applies it on the reply task (see {@code MetaWhatsAppReplyFlow}), so the {@code
 * TurnContext} the chat turn receives carries that tenant (the ThreadLocal instance).
 */
class MetaWhatsAppWebhookControllerTurnContextTest {

    private static final String PHONE_NUMBER_ID = "1234567890";
    private static final String RAW_BODY =
            """
{"entry":[{"changes":[{"value":{
  "metadata":{"phone_number_id":"1234567890"},
  "messages":[{"type":"text","id":"wamid.TEST-3","from":"15550001111","text":{"body":"hello"}}]
}}]}]}
""";

    private TurnContextProbe probe;
    private MetaWhatsAppWebhookController controller;

    @BeforeEach
    void setUp() {
        MetaWhatsAppCredentialService credentials = mock(MetaWhatsAppCredentialService.class);
        ChannelSessionService sessions = mock(ChannelSessionService.class);
        TenantResolutionService tenants = mock(TenantResolutionService.class);
        InboundMessageDedupService dedup = mock(InboundMessageDedupService.class);
        AgentChatService chat = mock(AgentChatService.class);
        probe = TurnContextProbe.on(chat);
        controller =
                new MetaWhatsAppWebhookController(
                        "verify-token",
                        mock(MetaWhatsAppSignatureValidator.class),
                        credentials,
                        sessions,
                        chat,
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
                .thenReturn(Optional.of("tenant_wa"));
        when(credentials.resolveActive())
                .thenReturn(
                        new MetaWhatsAppCredentialService.DecryptedCredential(
                                PHONE_NUMBER_ID, "access-token", "app-secret", "waba-1"));
        when(dedup.tryClaim(ChannelType.WHATSAPP, "wamid.TEST-3")).thenReturn(true);
        ChannelSession session = mock(ChannelSession.class);
        when(session.isPaired()).thenReturn(true);
        when(session.getId()).thenReturn(UUID.randomUUID());
        when(session.getAgentId()).thenReturn(UUID.randomUUID());
        when(session.getSessionId()).thenReturn("session-1");
        when(session.getExternalId()).thenReturn("15550001111");
        when(sessions.resolveOrCreate(eq(ChannelType.WHATSAPP), anyString(), anyString()))
                .thenReturn(session);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void theTurnContextCarriesTheTenantResolvedFromThePhoneNumberOnAThreadWithNoTenant() {
        controller.inbound(RAW_BODY, "sha256=sig");

        probe.assertTenant("tenant_wa", "system:whatsapp-inbound:" + PHONE_NUMBER_ID);
        TurnContextProbe.assertThreadClean();
    }

    @Test
    void aTenantAlreadyOnTheRequestThreadIsNotWhatTheTurnRunsUnder() {
        TenantContext.set(TenantContext.of("tenant_other", "someone", Set.of()));

        controller.inbound(RAW_BODY, "sha256=sig");

        probe.assertTenant("tenant_wa", "system:whatsapp-inbound:" + PHONE_NUMBER_ID);
    }
}
