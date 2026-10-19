package com.agenticai.core.omnichannel.discord;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
 * ADR-0022 stage 4: the Discord inbound resolves the tenant from the application id, so the {@code
 * TurnContext} handed to the chat turn carries that tenant (the ThreadLocal instance), not the
 * request thread's previous one.
 */
class DiscordWebhookControllerTurnContextTest {

    private static final String SECRET = "test-secret";
    private static final String APPLICATION_ID = "app-12345678";

    private TurnContextProbe probe;
    private DiscordWebhookController controller;

    @BeforeEach
    void setUp() {
        TenantResolutionService tenants = mock(TenantResolutionService.class);
        ChannelSessionService sessions = mock(ChannelSessionService.class);
        InboundMessageDedupService dedup = mock(InboundMessageDedupService.class);
        AgentChatService chat = mock(AgentChatService.class);
        probe = TurnContextProbe.on(chat);
        controller =
                new DiscordWebhookController(
                        SECRET,
                        tenants,
                        sessions,
                        chat,
                        mock(HumanApprovalGateService.class),
                        mock(WorkflowEventPublisher.class),
                        mock(WorkflowTriggerNodeService.class),
                        mock(WorkflowTriggerPort.class),
                        dedup,
                        new AsyncInboundReply(Runnable::run));
        when(tenants.resolveTenantSchema(ExternalAccountProvider.DISCORD, APPLICATION_ID))
                .thenReturn(Optional.of("tenant_discord"));
        when(dedup.tryClaim(eq(ChannelType.DISCORD), any())).thenReturn(true);
        ChannelSession session = mock(ChannelSession.class);
        when(session.isPaired()).thenReturn(true);
        when(session.getId()).thenReturn(UUID.randomUUID());
        when(session.getAgentId()).thenReturn(UUID.randomUUID());
        when(session.getSessionId()).thenReturn("session-1");
        when(session.getExternalId()).thenReturn("chan-1234");
        when(sessions.resolveOrCreate(eq(ChannelType.DISCORD), anyString(), anyString()))
                .thenReturn(session);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private DiscordInboundMessage message() {
        return new DiscordInboundMessage(APPLICATION_ID, "chan-1234", "author-1", "hello", "msg-1");
    }

    @Test
    void theTurnContextCarriesTheTenantResolvedFromTheApplicationOnAThreadWithNoTenant() {
        controller.inbound(message(), SECRET);

        probe.assertTenant("tenant_discord", "system:discord-inbound:" + APPLICATION_ID);
        TurnContextProbe.assertThreadClean();
    }

    @Test
    void aTenantAlreadyOnTheRequestThreadIsNotWhatTheTurnRunsUnder() {
        TenantContext.set(TenantContext.of("tenant_other", "someone", Set.of()));

        controller.inbound(message(), SECRET);

        probe.assertTenant("tenant_discord", "system:discord-inbound:" + APPLICATION_ID);
    }
}
