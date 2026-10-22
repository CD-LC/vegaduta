package com.agenticai.core.omnichannel.teams;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.audit.AuditLogService;
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
 * ADR-0022 stage 4: the Teams webhook resolves the tenant from the bot app id, and the {@code
 * TurnContext} handed to the chat turn carries that tenant (the ThreadLocal instance on the reply
 * task), never an ambient one.
 */
class TeamsWebhookControllerTurnContextTest {

    private static final String APP_ID = "app-0000-1111";
    private static final String ACTIVITY =
            """
            {"type":"message","id":"act-1","text":"hello","serviceUrl":"https://smba.example/",
             "from":{"id":"user-1"},"conversation":{"id":"conv-1"}}
            """;

    private TurnContextProbe probe;
    private TeamsWebhookController controller;

    @BeforeEach
    void setUp() throws Exception {
        TenantResolutionService tenants = mock(TenantResolutionService.class);
        TeamsCredentialRepository credentialRepository = mock(TeamsCredentialRepository.class);
        TeamsBotFrameworkJwtValidator jwt = mock(TeamsBotFrameworkJwtValidator.class);
        ChannelSessionService sessions = mock(ChannelSessionService.class);
        InboundMessageDedupService dedup = mock(InboundMessageDedupService.class);
        AgentChatService chat = mock(AgentChatService.class);
        probe = TurnContextProbe.on(chat);
        controller =
                new TeamsWebhookController(
                        tenants,
                        credentialRepository,
                        mock(TeamsCredentialService.class),
                        jwt,
                        sessions,
                        chat,
                        mock(HumanApprovalGateService.class),
                        mock(WorkflowEventPublisher.class),
                        mock(AuditLogService.class),
                        dedup,
                        mock(WorkflowTriggerNodeService.class),
                        mock(WorkflowTriggerPort.class),
                        new AsyncInboundReply(Runnable::run));
        when(tenants.resolveTenantSchema(ExternalAccountProvider.TEAMS, APP_ID))
                .thenReturn(Optional.of("tenant_teams"));
        TeamsCredential credential = mock(TeamsCredential.class);
        when(credential.getAppId()).thenReturn(APP_ID);
        when(credentialRepository.findFirstByOrderByCreatedAtAsc())
                .thenReturn(Optional.of(credential));
        when(jwt.isValid(any(), eq(APP_ID), any())).thenReturn(true);
        when(dedup.tryClaim(eq(ChannelType.TEAMS), any())).thenReturn(true);
        ChannelSession session = mock(ChannelSession.class);
        when(session.isPaired()).thenReturn(true);
        when(session.getId()).thenReturn(UUID.randomUUID());
        when(session.getAgentId()).thenReturn(UUID.randomUUID());
        when(session.getSessionId()).thenReturn("session-1");
        when(session.getExternalId()).thenReturn("conv-1");
        when(sessions.resolveOrCreate(eq(ChannelType.TEAMS), anyString(), anyString()))
                .thenReturn(session);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void theTurnContextCarriesTheTenantResolvedFromTheBotOnAThreadWithNoTenant() throws Exception {
        controller.inbound(
                APP_ID,
                com.agenticai.core.json.JsonMappers.plain().readTree(ACTIVITY),
                "Bearer token");

        probe.assertTenant("tenant_teams", "system:teams-inbound:" + APP_ID);
        TurnContextProbe.assertThreadClean();
    }

    @Test
    void aTenantAlreadyOnTheRequestThreadIsNotWhatTheTurnRunsUnder() throws Exception {
        TenantContext.set(TenantContext.of("tenant_other", "someone", Set.of()));

        controller.inbound(
                APP_ID,
                com.agenticai.core.json.JsonMappers.plain().readTree(ACTIVITY),
                "Bearer token");

        probe.assertTenant("tenant_teams", "system:teams-inbound:" + APP_ID);
    }
}
