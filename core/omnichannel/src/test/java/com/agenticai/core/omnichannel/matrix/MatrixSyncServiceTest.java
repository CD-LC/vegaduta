package com.agenticai.core.omnichannel.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.omnichannel.matrix.MatrixSyncParser.Batch;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.TenantResolutionService;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The sync loop against a fake homeserver: initial sync, resume position, tenant, token rejection. */
class MatrixSyncServiceTest {

    private static final String BOT = "@bot:hs";

    private FakeHomeserver server;
    private MatrixCredentialService credentials;
    private MatrixEventService events;
    private MatrixSyncService sync;
    private final List<String> tenantsSeen = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        server = new FakeHomeserver();
        credentials = mock(MatrixCredentialService.class);
        events = mock(MatrixEventService.class);
        when(events.process(any(), any(), anyBoolean()))
                .thenAnswer(
                        inv -> {
                            tenantsSeen.add(TenantContext.current().map(TenantContext::getTenantId).orElse("none"));
                            return true;
                        });
        sync =
                new MatrixSyncService(
                        mock(TenantResolutionService.class),
                        credentials,
                        server.client(),
                        events,
                        50);
    }

    @AfterEach
    void tearDown() {
        sync.shutdown();
        server.close();
    }

    private void connectedWithSince(String since) {
        MatrixCredentialService.ActiveConnection connection =
                new MatrixCredentialService.ActiveConnection(
                        server.endpoint("tok"),
                        BOT,
                        since,
                        new MatrixSenderPolicy(BOT, MatrixSenderMode.PAIRING, MatrixJoinPolicy.OFF, ""));
        when(credentials.activeFor(BOT)).thenReturn(Optional.of(connection));
    }

    @Test
    void theFirstSyncSkipsHistoryAndLaterOnesDeliverMessagesInTheOwningTenant() {
        AtomicInteger calls = new AtomicInteger();
        server.on(
                "GET /_matrix/client/v3/sync",
                r -> {
                    int n = calls.incrementAndGet();
                    if (n == 1) {
                        return FakeHomeserver.Response.json(
                                200,
                                "{\"next_batch\":\"s1\",\"rooms\":{\"join\":{\"!r:hs\":{\"timeline\":{\"events\":["
                                        + "{\"type\":\"m.room.message\",\"event_id\":\"$old\",\"sender\":\"@a:hs\","
                                        + "\"content\":{\"msgtype\":\"m.text\",\"body\":\"old\"}}]}}}}}");
                    }
                    if (n == 2) {
                        return FakeHomeserver.Response.json(
                                200,
                                "{\"next_batch\":\"s2\",\"rooms\":{\"join\":{\"!r:hs\":{\"timeline\":{\"events\":["
                                        + "{\"type\":\"m.room.message\",\"event_id\":\"$new\",\"sender\":\"@a:hs\","
                                        + "\"content\":{\"msgtype\":\"m.text\",\"body\":\"new\"}}]}}}}}");
                    }
                    pause();
                    return FakeHomeserver.Response.json(200, "{\"next_batch\":\"s2\"}");
                });
        connectedWithSince(null);

        sync.ensureSyncing(BOT, "tenant_abc");

        ArgumentCaptor<Batch> batches = ArgumentCaptor.forClass(Batch.class);
        ArgumentCaptor<Boolean> handle = ArgumentCaptor.forClass(Boolean.class);
        verify(events, timeout(5000).atLeast(2)).process(any(), batches.capture(), handle.capture());
        assertThat(handle.getAllValues().get(0)).isFalse(); // initial: history is not handled
        assertThat(handle.getAllValues().get(1)).isTrue();
        assertThat(batches.getAllValues().get(1).events().get(0).eventId()).isEqualTo("$new");
        assertThat(tenantsSeen).allMatch("tenant_abc"::equals);
        // The position is persisted after the initial sync and after the batch that carried a message.
        verify(credentials, timeout(5000)).saveSinceToken(BOT, "s1");
        verify(credentials, timeout(5000)).saveSinceToken(BOT, "s2");
        // Second request resumes from s1.
        assertThat(server.requestsTo("GET", "/_matrix/client/v3/sync").get(1).query()).contains("since=s1");
    }

    @Test
    void aSavedPositionIsResumedWithoutReplayingHistory() {
        server.on(
                "GET /_matrix/client/v3/sync",
                r -> {
                    pause();
                    return FakeHomeserver.Response.json(200, "{\"next_batch\":\"s8\"}");
                });
        connectedWithSince("s7");

        sync.ensureSyncing(BOT, "tenant_abc");

        verify(events, timeout(5000)).process(any(), any(), eq(true));
        assertThat(server.requestsTo("GET", "/_matrix/client/v3/sync").get(0).query()).contains("since=s7");
        verify(events, never()).process(any(), any(), eq(false));
    }

    @Test
    void aRejectedTokenMarksTheCredentialInvalidAndStopsTheLoop() throws Exception {
        server.on(
                "GET /_matrix/client/v3/sync",
                401,
                "{\"errcode\":\"M_UNKNOWN_TOKEN\",\"error\":\"Unrecognised access token\"}");
        connectedWithSince("s7");

        sync.ensureSyncing(BOT, "tenant_abc");

        verify(credentials, timeout(5000)).markInvalid(BOT);
        Thread.sleep(200);
        assertThat(server.requestsTo("GET", "/_matrix/client/v3/sync")).hasSize(1);
        verify(events, never()).process(any(), any(), anyBoolean());
    }

    @Test
    void anExpiredPositionRestartsFromNowInsteadOfFailingForever() {
        AtomicInteger calls = new AtomicInteger();
        server.on(
                "GET /_matrix/client/v3/sync",
                r -> {
                    if (calls.incrementAndGet() == 1) {
                        return FakeHomeserver.Response.json(400, "{\"errcode\":\"M_UNKNOWN_POS\",\"error\":\"gone\"}");
                    }
                    pause();
                    return FakeHomeserver.Response.json(200, "{\"next_batch\":\"fresh\"}");
                });
        connectedWithSince("stale");

        sync.ensureSyncing(BOT, "tenant_abc");

        verify(credentials, timeout(5000)).saveSinceToken(BOT, null);
        verify(events, timeout(5000)).process(any(), any(), eq(false));
        verify(credentials, timeout(5000)).saveSinceToken(BOT, "fresh");
    }

    @Test
    void aBotWhoseCredentialWasDisconnectedStopsSyncing() {
        when(credentials.activeFor(BOT)).thenReturn(Optional.empty());

        sync.ensureSyncing(BOT, "tenant_abc");

        verify(credentials, timeout(5000)).activeFor(BOT);
        assertThat(server.requests).isEmpty();
    }

    @Test
    void backoffGrowsAndIsCapped() {
        assertThat(MatrixSyncService.backoffMs(1)).isEqualTo(2000);
        assertThat(MatrixSyncService.backoffMs(3)).isEqualTo(8000);
        assertThat(MatrixSyncService.backoffMs(50)).isEqualTo(60_000);
    }

    private static void pause() {
        try {
            Thread.sleep(40);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
