package com.agenticai.core.omnichannel.twilio;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.InboundChannelExecutor;
import com.agenticai.core.omnichannel.session.InboundMessageDedupService;
import com.agenticai.core.omnichannel.session.TurnContextProbe;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNodeService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerPort;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.tenancy.TenantSchemaResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ADR-0022 stage 4: the Twilio WhatsApp and SMS webhooks resolve the tenant from the AccountSid,
 * falling back to the default schema for a single-tenant install with no link. The reply leg runs
 * on a propagated task, and the {@code TurnContext} it hands to the chat turn must carry that
 * tenant (the ThreadLocal instance on the task thread).
 */
class TwilioWebhookControllersTurnContextTest {

    private static final String ACCOUNT_SID = "AC12345678901234567890123456789012";

    private TurnContextProbe whatsAppProbe;
    private TurnContextProbe smsProbe;
    private TenantResolutionService tenants;
    private TwilioWebhookController whatsApp;
    private TwilioSmsWebhookController sms;
    private HttpServletRequest servletRequest;

    @BeforeEach
    void setUp() {
        tenants = mock(TenantResolutionService.class);
        TwilioCredentialService credentials = mock(TwilioCredentialService.class);
        when(credentials.resolveActive())
                .thenReturn(
                        new TwilioCredentialService.DecryptedCredential(
                                ACCOUNT_SID, "auth-token", null, null, "+15550009999"));
        InboundMessageDedupService dedup = mock(InboundMessageDedupService.class);
        when(dedup.tryClaim(any(ChannelType.class), any())).thenReturn(true);

        ChannelSession session = mock(ChannelSession.class);
        when(session.isPaired()).thenReturn(true);
        when(session.getId()).thenReturn(UUID.randomUUID());
        when(session.getAgentId()).thenReturn(UUID.randomUUID());
        when(session.getSessionId()).thenReturn("session-1");
        when(session.getExternalId()).thenReturn("+15550001111");
        ChannelSessionService sessions = mock(ChannelSessionService.class);
        when(sessions.resolveOrCreate(any(ChannelType.class), anyString(), anyString()))
                .thenReturn(session);

        AgentChatService waChat = mock(AgentChatService.class);
        whatsAppProbe = TurnContextProbe.on(waChat);
        whatsApp =
                new TwilioWebhookController(
                        mock(TwilioSignatureValidator.class),
                        credentials,
                        sessions,
                        waChat,
                        mock(HumanApprovalGateService.class),
                        tenants,
                        mock(WorkflowEventPublisher.class),
                        mock(WorkflowTriggerNodeService.class),
                        mock(WorkflowTriggerPort.class),
                        mock(AuditLogService.class),
                        dedup,
                        InboundChannelExecutor.synchronous());

        AgentChatService smsChat = mock(AgentChatService.class);
        smsProbe = TurnContextProbe.on(smsChat);
        sms =
                new TwilioSmsWebhookController(
                        mock(TwilioSignatureValidator.class),
                        credentials,
                        tenants,
                        mock(WorkflowTriggerNodeService.class),
                        mock(WorkflowTriggerPort.class),
                        dedup,
                        sessions,
                        smsChat,
                        mock(HumanApprovalGateService.class),
                        mock(WorkflowEventPublisher.class),
                        mock(AuditLogService.class),
                        InboundChannelExecutor.synchronous());

        servletRequest = mock(HttpServletRequest.class);
        when(servletRequest.getHeader("X-Twilio-Signature")).thenReturn("sig");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Map<String, String> form(String fromPrefix) {
        return Map.of(
                "AccountSid",
                ACCOUNT_SID,
                "MessageSid",
                "SM12345678901234567890123456789012",
                "From",
                fromPrefix + "+15550001111",
                "Body",
                "hello");
    }

    private void linkTenant(String schema) {
        when(tenants.resolveTenantSchema(eq(ExternalAccountProvider.TWILIO), eq(ACCOUNT_SID)))
                .thenReturn(Optional.of(schema));
    }

    @Test
    void whatsAppTurnContextCarriesTheTenantResolvedFromTheAccountOnAThreadWithNoTenant() {
        linkTenant("tenant_wa");

        whatsApp.inboundWhatsApp(form("whatsapp:"), servletRequest);

        whatsAppProbe.assertTenant("tenant_wa", "system:whatsapp-inbound:" + ACCOUNT_SID);
        TurnContextProbe.assertThreadClean();
    }

    @Test
    void whatsAppWithNoTenantLinkRunsUnderTheDefaultSchemaNotAnAmbientTenant() {
        TenantContext.set(TenantContext.of("tenant_other", "someone", Set.of()));

        whatsApp.inboundWhatsApp(form("whatsapp:"), servletRequest);

        whatsAppProbe.assertTenant(
                TenantSchemaResolver.DEFAULT_SCHEMA, "system:whatsapp-inbound:" + ACCOUNT_SID);
    }

    @Test
    void smsTurnContextCarriesTheTenantResolvedFromTheAccountOnAThreadWithNoTenant() {
        linkTenant("tenant_sms");

        sms.inboundSms(form(""), servletRequest);

        smsProbe.assertTenant("tenant_sms", "system:sms-inbound:" + ACCOUNT_SID);
        TurnContextProbe.assertThreadClean();
    }

    @Test
    void smsWithNoTenantLinkRunsUnderTheDefaultSchemaNotAnAmbientTenant() {
        TenantContext.set(TenantContext.of("tenant_other", "someone", Set.of()));

        sms.inboundSms(form(""), servletRequest);

        smsProbe.assertTenant(
                TenantSchemaResolver.DEFAULT_SCHEMA, "system:sms-inbound:" + ACCOUNT_SID);
    }
}
