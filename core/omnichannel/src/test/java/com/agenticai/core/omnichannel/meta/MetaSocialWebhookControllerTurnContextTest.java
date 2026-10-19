package com.agenticai.core.omnichannel.meta;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.omnichannel.session.AsyncInboundReply;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.InboundMessageDedupService;
import com.agenticai.core.omnichannel.session.TurnContextProbe;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNodeService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerPort;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ADR-0022 stage 4: the Messenger/Instagram webhook resolves the tenant from the page or account
 * id, and the {@code TurnContext} it hands to the chat turn carries that tenant (the ThreadLocal
 * instance) for both surfaces.
 */
class MetaSocialWebhookControllerTurnContextTest {

    private static final String PAGE_ID = "page-1234";
    private static final String IG_ACCOUNT_ID = "ig-5678";
    private static final String MESSENGER_BODY =
            """
            {"object":"page","entry":[{"id":"page-1234","messaging":[
              {"sender":{"id":"user-0001"},"message":{"mid":"m_MID-2","text":"hello"}}
            ]}]}
            """;
    private static final String INSTAGRAM_BODY =
            """
            {"object":"instagram","entry":[{"id":"ig-5678","messaging":[
              {"sender":{"id":"user-0001"},"message":{"mid":"m_MID-1","text":"hello"}}
            ]}]}
            """;

    private TurnContextProbe probe;
    private MetaSocialWebhookController controller;

    @BeforeEach
    void setUp() {
        MetaSocialCredentialService credentials = mock(MetaSocialCredentialService.class);
        TenantResolutionService tenants = mock(TenantResolutionService.class);
        ChannelSessionService sessions = mock(ChannelSessionService.class);
        InboundMessageDedupService dedup = mock(InboundMessageDedupService.class);
        AgentChatService chat = mock(AgentChatService.class);
        probe = TurnContextProbe.on(chat);
        controller =
                new MetaSocialWebhookController(
                        "verify-token",
                        mock(MetaWhatsAppSignatureValidator.class),
                        credentials,
                        sessions,
                        chat,
                        mock(HumanApprovalGateService.class),
                        tenants,
                        mock(WorkflowEventPublisher.class),
                        mock(WorkflowTriggerNodeService.class),
                        mock(WorkflowTriggerPort.class),
                        dedup,
                        new AsyncInboundReply(Runnable::run));
        when(tenants.resolveTenantSchema(ExternalAccountProvider.META_MESSENGER, PAGE_ID))
                .thenReturn(Optional.of("tenant_messenger"));
        when(tenants.resolveTenantSchema(ExternalAccountProvider.META_INSTAGRAM, IG_ACCOUNT_ID))
                .thenReturn(Optional.of("tenant_instagram"));
        when(credentials.resolveActive())
                .thenReturn(
                        new MetaSocialCredentialService.DecryptedCredential(
                                PAGE_ID, "page-token", "app-secret", IG_ACCOUNT_ID));
        when(dedup.tryClaim(any(ChannelType.class), anyString())).thenReturn(true);
        ChannelSession session = mock(ChannelSession.class);
        when(session.isPaired()).thenReturn(true);
        when(session.getId()).thenReturn(UUID.randomUUID());
        when(session.getAgentId()).thenReturn(UUID.randomUUID());
        when(session.getSessionId()).thenReturn("session-1");
        when(session.getExternalId()).thenReturn("user-0001");
        when(sessions.resolveOrCreate(any(ChannelType.class), eq("user-0001"), anyString()))
                .thenReturn(session);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void messengerTurnContextCarriesTheTenantResolvedFromThePageOnAThreadWithNoTenant() {
        controller.inbound(MESSENGER_BODY, "sha256=sig");

        probe.assertTenant("tenant_messenger", "system:social-inbound:" + PAGE_ID);
        TurnContextProbe.assertThreadClean();
    }

    @Test
    void instagramTurnContextCarriesTheTenantResolvedFromTheAccount() {
        controller.inbound(INSTAGRAM_BODY, "sha256=sig");

        probe.assertTenant("tenant_instagram", "system:social-inbound:" + IG_ACCOUNT_ID);
    }

    @Test
    void aTenantAlreadyOnTheRequestThreadIsNotWhatTheTurnRunsUnder() {
        TenantContext.set(TenantContext.of("tenant_other", "someone", Set.of()));

        controller.inbound(MESSENGER_BODY, "sha256=sig");

        probe.assertTenant("tenant_messenger", "system:social-inbound:" + PAGE_ID);
    }
}
