package com.agenticai.core.omnichannel.x;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.InboundMessageDedupService;
import com.agenticai.core.omnichannel.session.TurnContextProbe;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNodeService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerPort;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ADR-0022 stage 4: the X DM poller runs each tenant's DMs under the tenant the {@link
 * TenantSchemaIterator} sets for that schema (stood in for here, as the real one sets and clears
 * the ThreadLocal around the callback), and the {@code TurnContext} it builds is that instance.
 */
class XDmPollingJobTurnContextTest {

    private static final String SCHEMA = "tenant_x";

    private TurnContextProbe probe;
    private XDmPollingJob job;

    @BeforeEach
    void setUp() {
        PgAdvisoryJobLock jobLock = mock(PgAdvisoryJobLock.class);
        TenantSchemaIterator schemaIterator = mock(TenantSchemaIterator.class);
        XOAuthService oauth = mock(XOAuthService.class);
        XApiClient api = mock(XApiClient.class);
        ChannelSessionService sessions = mock(ChannelSessionService.class);
        InboundMessageDedupService dedup = mock(InboundMessageDedupService.class);
        when(dedup.tryClaim(eq(ChannelType.X), anyString())).thenReturn(true);
        AgentChatService chat = mock(AgentChatService.class);
        probe = TurnContextProbe.on(chat);
        job =
                new XDmPollingJob(
                        jobLock,
                        schemaIterator,
                        oauth,
                        api,
                        sessions,
                        chat,
                        mock(HumanApprovalGateService.class),
                        mock(WorkflowEventPublisher.class),
                        mock(WorkflowTriggerNodeService.class),
                        mock(WorkflowTriggerPort.class),
                        dedup);

        when(jobLock.tryRunExclusive(anyString(), any(Runnable.class)))
                .thenAnswer(
                        inv -> {
                            inv.getArgument(1, Runnable.class).run();
                            return true;
                        });
        doAnswer(
                        inv -> {
                            TenantContext.set(
                                    TenantContext.of(SCHEMA, "system:x-dm-poll", Set.of()));
                            try {
                                inv.getArgument(0, Consumer.class).accept(SCHEMA);
                            } finally {
                                TenantContext.clear();
                            }
                            return null;
                        })
                .when(schemaIterator)
                .forEachActiveTenantSchema(any());

        XCredential credential = mock(XCredential.class);
        when(credential.getLastDmEventId()).thenReturn("cursor-1");
        when(credential.getXUserId()).thenReturn("own-user-1");
        when(oauth.activeCredential()).thenReturn(Optional.of(credential));
        when(oauth.currentAccessToken()).thenReturn("access-token");
        when(api.fetchNewMessagesSince("access-token", "cursor-1", "own-user-1"))
                .thenReturn(
                        new XApiClient.PollResult(
                                List.of(
                                        new XApiClient.DmEvent(
                                                "dm-evt-1", "conv-1", "sender-1", "hello")),
                                "cursor-2"));

        ChannelSession session = mock(ChannelSession.class);
        when(session.isPaired()).thenReturn(true);
        when(session.getId()).thenReturn(UUID.randomUUID());
        when(session.getAgentId()).thenReturn(UUID.randomUUID());
        when(session.getSessionId()).thenReturn("session-1");
        when(session.getExternalId()).thenReturn("conv-1");
        when(sessions.resolveOrCreate(eq(ChannelType.X), anyString(), anyString()))
                .thenReturn(session);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void theTurnContextIsTheTenantTheIteratorSetForTheSchema() {
        job.pollAllTenants();

        probe.assertTenant(SCHEMA, "system:x-dm-poll");
        TurnContextProbe.assertThreadClean();
    }
}
