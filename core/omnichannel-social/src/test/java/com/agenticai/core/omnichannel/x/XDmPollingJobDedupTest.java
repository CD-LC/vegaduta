package com.agenticai.core.omnichannel.x;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.InboundMessageDedupService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNodeService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerPort;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Inbound-dedup wiring for the X DM polling job - closing the coordination log's 2026-08-26 finding
 * that the two polling channels never called {@link InboundMessageDedupService} at all. The poll
 * cursor alone leaves a real replay window: {@code advancePollCursor} runs after the whole event
 * batch, so a crash/restart mid-batch (or a failed cursor save) re-fetches the same dm_events on
 * the next tick and would re-reply to every one of them.
 *
 * <p>Same manual {@code mock(X.class)} + constructor-injection shape as {@link
 * com.agenticai.core.omnichannel.discord.DiscordWebhookControllerDedupTest}, including the
 * unpaired-notice-already-sent session so the flow ends synchronously at {@link
 * ChannelSessionService#resolveOrCreate} - the first side effect past the dedup gate - and never
 * reaches the blocking agent-chat leg.
 */
class XDmPollingJobDedupTest {

    private static final String SCHEMA = "tenant_test";
    private static final String EVENT_ID = "dm-evt-1";

    private ChannelSessionService channelSessionService;
    private InboundMessageDedupService dedupService;
    private XDmPollingJob job;

    @BeforeEach
    void setUp() {
        PgAdvisoryJobLock jobLock = mock(PgAdvisoryJobLock.class);
        TenantSchemaIterator schemaIterator = mock(TenantSchemaIterator.class);
        XOAuthService oauthService = mock(XOAuthService.class);
        XApiClient apiClient = mock(XApiClient.class);
        channelSessionService = mock(ChannelSessionService.class);
        dedupService = mock(InboundMessageDedupService.class);

        job =
                new XDmPollingJob(
                        jobLock,
                        schemaIterator,
                        oauthService,
                        apiClient,
                        channelSessionService,
                        mock(AgentChatService.class),
                        mock(HumanApprovalGateService.class),
                        mock(WorkflowEventPublisher.class),
                        mock(WorkflowTriggerNodeService.class),
                        mock(WorkflowTriggerPort.class),
                        dedupService);

        // Run the job lock's/schema iterator's callback synchronously and inline.
        when(jobLock.tryRunExclusive(anyString(), any(Runnable.class)))
                .thenAnswer(
                        invocation -> {
                            invocation.getArgument(1, Runnable.class).run();
                            return true;
                        });
        doAnswer(
                        invocation -> {
                            invocation.getArgument(0, Consumer.class).accept(SCHEMA);
                            return null;
                        })
                .when(schemaIterator)
                .forEachActiveTenantSchema(any());

        XCredential credential = mock(XCredential.class);
        when(credential.getLastDmEventId()).thenReturn("cursor-1");
        when(credential.getXUserId()).thenReturn("own-user-1");
        when(oauthService.activeCredential()).thenReturn(Optional.of(credential));
        when(oauthService.currentAccessToken()).thenReturn("access-token");

        // The failed-cursor-save replay scenario: every tick re-fetches the
        // exact same event batch.
        XApiClient.DmEvent event = new XApiClient.DmEvent(EVENT_ID, "conv-1", "sender-1", "hello");
        when(apiClient.fetchNewMessagesSince("access-token", "cursor-1", "own-user-1"))
                .thenReturn(new XApiClient.PollResult(List.of(event), "cursor-2"));

        // Unpaired sender whose pairing notice already went out - the
        // earliest fully-synchronous exit past the dedup gate, so "was this
        // event processed" reduces to "was resolveOrCreate called".
        ChannelSession session = mock(ChannelSession.class);
        when(session.isPaired()).thenReturn(false);
        when(session.isPairingNoticeSent()).thenReturn(true);
        when(channelSessionService.resolveOrCreate(eq(ChannelType.X), anyString(), anyString()))
                .thenReturn(session);
    }

    @Test
    void reFetchOfTheSameDmEventIdAcrossTicksIsProcessedExactlyOnce() {
        when(dedupService.tryClaim(ChannelType.X, EVENT_ID)).thenReturn(true, false);

        job.pollAllTenants();
        job.pollAllTenants();

        verify(channelSessionService, times(1)).resolveOrCreate(ChannelType.X, "conv-1", "hello");
    }
}
