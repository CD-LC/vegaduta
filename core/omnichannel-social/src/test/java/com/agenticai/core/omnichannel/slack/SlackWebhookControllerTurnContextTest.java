package com.agenticai.core.omnichannel.slack;

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
 * ADR-0022 stage 4: the Slack webhook resolves the tenant from the team id, so the {@code
 * TurnContext} it hands to the chat turn must carry that tenant (the very ThreadLocal instance),
 * never whatever tenant the request thread happened to hold.
 */
class SlackWebhookControllerTurnContextTest {

    private static final String TEAM_ID = "T12345678";
    private static final String BODY =
            """
            {"team_id":"T12345678","type":"event_callback","event_id":"Ev1",
             "event":{"type":"message","user":"U11111111","channel":"C22222222","text":"hello"}}
            """;

    private TurnContextProbe probe;
    private SlackWebhookController controller;

    @BeforeEach
    void setUp() {
        SlackCredentialService credentials = mock(SlackCredentialService.class);
        TenantResolutionService tenants = mock(TenantResolutionService.class);
        ChannelSessionService sessions = mock(ChannelSessionService.class);
        InboundMessageDedupService dedup = mock(InboundMessageDedupService.class);
        AgentChatService chat = mock(AgentChatService.class);
        probe = TurnContextProbe.on(chat);
        controller =
                new SlackWebhookController(
                        mock(SlackSignatureValidator.class),
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
        when(tenants.resolveTenantSchema(ExternalAccountProvider.SLACK, TEAM_ID))
                .thenReturn(Optional.of("tenant_slack"));
        when(credentials.resolveActive())
                .thenReturn(
                        new SlackCredentialService.DecryptedCredential(
                                TEAM_ID, "UBOT00000", "xoxb-token", "signing-secret"));
        when(dedup.tryClaim(eq(ChannelType.SLACK), any())).thenReturn(true);
        ChannelSession session = mock(ChannelSession.class);
        when(session.isPaired()).thenReturn(true);
        when(session.getId()).thenReturn(UUID.randomUUID());
        when(session.getAgentId()).thenReturn(UUID.randomUUID());
        when(session.getSessionId()).thenReturn("session-1");
        when(session.getExternalId()).thenReturn("C22222222");
        when(sessions.resolveOrCreate(eq(ChannelType.SLACK), anyString(), anyString()))
                .thenReturn(session);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void theTurnContextCarriesTheTenantResolvedFromTheTeamOnAThreadWithNoTenant() {
        controller.inbound(BODY, "1717171717", "v0=sig");

        probe.assertTenant("tenant_slack", "system:slack-inbound:" + TEAM_ID);
        TurnContextProbe.assertThreadClean();
    }

    @Test
    void aTenantAlreadyOnTheRequestThreadIsNotWhatTheTurnRunsUnder() {
        TenantContext.set(TenantContext.of("tenant_other", "someone", Set.of()));

        controller.inbound(BODY, "1717171717", "v0=sig");

        probe.assertTenant("tenant_slack", "system:slack-inbound:" + TEAM_ID);
    }
}
