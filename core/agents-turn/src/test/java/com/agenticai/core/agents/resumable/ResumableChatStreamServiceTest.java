package com.agenticai.core.agents.resumable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.agenticai.core.agents.core.AgentController;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.resumable.ResumableChatStreamService.Started;
import com.agenticai.core.agents.resumable.ResumableChatStreamService.TurnSnapshot;
import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.agenticai.core.security.TenantContext;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Resumable chat streams end to end at the service level: tee + monotonic ids, resume replay then
 * live continuation, decoupling from the HTTP subscriber, the detach timeout, authorisation denial,
 * TTL expiry, the flag-off identity and the pre-first-token error contract.
 */
class ResumableChatStreamServiceTest {

    private static final Duration LONG_DETACH = Duration.ofMinutes(10);

    private final UUID agentId = UUID.randomUUID();
    private final AtomicLong clock = new AtomicLong(10_000_000L);
    private AgentService agentService;
    private InMemoryResumableStreamBuffer buffer;

    @BeforeEach
    void setUp() {
        agentService = mock(AgentService.class);
        TenantContext.set(TenantContext.of("tenant_a", "alice", Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private ResumableChatStreamService service(boolean enabled, int maxEvents, Duration detach) {
        buffer = new InMemoryResumableStreamBuffer(maxEvents, 600, 900, clock::get);
        return new ResumableChatStreamService(enabled, buffer, agentService, detach, clock::get);
    }

    private ResumableChatStreamService service() {
        return service(true, 1000, LONG_DETACH);
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("timed out waiting for " + what);
            }
            Thread.sleep(20L);
        }
    }

    private static List<String> data(List<ServerSentEvent<String>> events) {
        return events.stream().map(ServerSentEvent::data).toList();
    }

    private static List<String> ids(List<ServerSentEvent<String>> events) {
        return events.stream().map(ServerSentEvent::id).toList();
    }

    private static boolean isData(ServerSentEvent<String> event) {
        return event.comment() == null;
    }

    // ---- tee + ids
    // -------------------------------------------------------------------------------------

    @Test
    void startTeesEveryChunkWithMonotonicIdsAndATerminalFrame() {
        ResumableChatStreamService svc = service();
        Sinks.Many<String> source = Sinks.many().unicast().onBackpressureBuffer();
        Started started = svc.start(agentId, "s1", source.asFlux());

        source.tryEmitNext("a");
        source.tryEmitNext("b");
        source.tryEmitComplete();
        List<ServerSentEvent<String>> events =
                started.events().collectList().block(Duration.ofSeconds(5));

        assertEquals(List.of("1", "2", "3"), ids(events));
        assertEquals("a", events.get(0).data());
        assertEquals("b", events.get(1).data());
        ServerSentEvent<String> end = events.get(2);
        assertEquals(ResumableChatStreamService.TURN_END_EVENT, end.event());
        assertTrue(end.data().contains("\"status\":\"complete\""));
        assertTrue(end.data().contains("\"lastEventId\":3"));
    }

    @Test
    void emptyKeepaliveChunksAreBufferedLikeAnyOtherEvent() {
        ResumableChatStreamService svc = service();
        Started started = svc.start(agentId, "s1", Flux.just("", "x"));

        List<ServerSentEvent<String>> events =
                started.events().collectList().block(Duration.ofSeconds(5));

        assertEquals(List.of("", "x"), data(events).subList(0, 2));
    }

    // ---- resume
    // ----------------------------------------------------------------------------------------

    @Test
    void resumeReplaysAfterTheGivenIdThenContinuesLive() throws Exception {
        ResumableChatStreamService svc = service();
        Sinks.Many<String> source = Sinks.many().multicast().onBackpressureBuffer();
        Started started = svc.start(agentId, "s1", source.asFlux());
        source.tryEmitNext("a");
        source.tryEmitNext("b");

        List<ServerSentEvent<String>> got = new CopyOnWriteArrayList<>();
        Disposable resumed =
                svc.resume(started.turnId(), 1L)
                        .filter(ResumableChatStreamServiceTest::isData)
                        .subscribe(got::add);
        await(() -> got.size() >= 1, "the replayed event");
        // Replay is exclusive of the cursor: "a" (id 1) is NOT sent again.
        assertEquals("b", got.get(0).data());
        assertEquals("2", got.get(0).id());

        source.tryEmitNext("c");
        source.tryEmitComplete();
        await(() -> got.size() >= 3, "the live event and the terminal frame");
        resumed.dispose();

        assertEquals(List.of("2", "3", "4"), ids(got));
        assertEquals("c", got.get(1).data());
        assertEquals(ResumableChatStreamService.TURN_END_EVENT, got.get(2).event());
    }

    @Test
    void resumeOfAFinishedTurnReplaysEverythingAndEnds() {
        ResumableChatStreamService svc = service();
        Started started = svc.start(agentId, "s1", Flux.just("a", "b", "c"));
        started.events().blockLast(Duration.ofSeconds(5));

        List<ServerSentEvent<String>> replay =
                svc.resume(started.turnId(), null)
                        .filter(ResumableChatStreamServiceTest::isData)
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertEquals(List.of("1", "2", "3", "4"), ids(replay));
        assertEquals(List.of("a", "b", "c"), data(replay).subList(0, 3));
    }

    @Test
    void resumeAfterTheLastEventYieldsOnlyTheTerminalFrame() {
        ResumableChatStreamService svc = service();
        Started started = svc.start(agentId, "s1", Flux.just("a", "b"));
        started.events().blockLast(Duration.ofSeconds(5));

        List<ServerSentEvent<String>> replay =
                svc.resume(started.turnId(), 2L)
                        .filter(ResumableChatStreamServiceTest::isData)
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertEquals(1, replay.size());
        assertEquals(ResumableChatStreamService.TURN_END_EVENT, replay.get(0).event());
        assertEquals("3", replay.get(0).id());
    }

    @Test
    void droppedEventsAnswer410InsteadOfAnIncompleteReplay() {
        ResumableChatStreamService svc = service(true, 3, LONG_DETACH);
        Started started =
                svc.start(
                        agentId,
                        "s1",
                        Flux.just("1", "2", "3", "4", "5", "6", "7", "8", "9", "10"));
        started.events().blockLast(Duration.ofSeconds(5));

        // 10 chunks + terminal = ids 1..11, the buffer keeps 9..11.
        ResponseStatusException stale =
                assertThrows(ResponseStatusException.class, () -> svc.resume(started.turnId(), 7L));
        assertEquals(HttpStatus.GONE.value(), stale.getStatusCode().value());
        // A cursor at the edge of what is retained is still fine.
        assertEquals(
                List.of("9", "10", "11"),
                ids(
                        svc.resume(started.turnId(), 8L)
                                .filter(ResumableChatStreamServiceTest::isData)
                                .collectList()
                                .block(Duration.ofSeconds(5))));
    }

    @Test
    void anExpiredTurnAnswers404() {
        ResumableChatStreamService svc = service();
        Started started = svc.start(agentId, "s1", Flux.just("a"));
        started.events().blockLast(Duration.ofSeconds(5));

        clock.addAndGet(599_000L);
        svc.resume(started.turnId(), 0L);

        clock.addAndGet(2_000L);
        ResponseStatusException gone =
                assertThrows(ResponseStatusException.class, () -> svc.resume(started.turnId(), 0L));
        assertEquals(404, gone.getStatusCode().value());
        assertThrows(ResponseStatusException.class, () -> svc.snapshot(started.turnId()));
    }

    // ---- decoupling + detach timeout
    // -------------------------------------------------------------------

    @Test
    void generationKeepsRunningAfterTheHttpReaderDisconnects() throws Exception {
        ResumableChatStreamService svc = service();
        Sinks.Many<String> source = Sinks.many().multicast().onBackpressureBuffer();
        AtomicBoolean upstreamCancelled = new AtomicBoolean(false);
        Started started =
                svc.start(
                        agentId,
                        "s1",
                        source.asFlux().doOnCancel(() -> upstreamCancelled.set(true)));

        List<ServerSentEvent<String>> original = new CopyOnWriteArrayList<>();
        Disposable http = started.events().subscribe(original::add);
        source.tryEmitNext("a");
        await(() -> original.size() >= 1, "the first event on the original stream");
        http.dispose();

        source.tryEmitNext("b");
        source.tryEmitNext("c");
        source.tryEmitComplete();

        assertFalse(upstreamCancelled.get(), "a client disconnect must not cancel the model call");
        List<ServerSentEvent<String>> rest =
                svc.resume(started.turnId(), 1L)
                        .filter(ResumableChatStreamServiceTest::isData)
                        .collectList()
                        .block(Duration.ofSeconds(5));
        assertEquals(List.of("b", "c"), data(rest).subList(0, 2));
    }

    @Test
    void aGenerationWithNoReaderIsCancelledAtTheDetachTimeout() throws Exception {
        ResumableChatStreamService svc = service(true, 1000, Duration.ofMillis(250));
        AtomicBoolean upstreamCancelled = new AtomicBoolean(false);
        // Produces one event first: a reader that leaves BEFORE any output is cancelled at once
        // (see the next test).
        Started started =
                svc.start(
                        agentId,
                        "s1",
                        Flux.concat(Flux.just("a"), Flux.<String>never())
                                .doOnCancel(() -> upstreamCancelled.set(true)));

        // Attached: the timer is held off.
        Disposable http = started.events().subscribe();
        Thread.sleep(700L);
        assertFalse(
                upstreamCancelled.get(), "a watched turn is never cancelled by the detach timer");

        // Detached: the timer starts, then cancels the generation and closes the turn.
        http.dispose();
        await(upstreamCancelled::get, "the detach timeout to cancel the generation");
        await(
                () ->
                        ResumableChatStreamService.STATUS_DETACH_TIMEOUT.equals(
                                svc.snapshot(started.turnId()).status()),
                "the turn to be closed as detach-timeout");
    }

    @Test
    void aReaderThatLeavesBeforeTheFirstEventStopsTheGenerationAtOnce() throws Exception {
        ResumableChatStreamService svc = service();
        AtomicBoolean upstreamCancelled = new AtomicBoolean(false);
        Started started =
                svc.start(
                        agentId,
                        "s1",
                        Flux.<String>never().doOnCancel(() -> upstreamCancelled.set(true)));

        // The POST was aborted (Stop pressed) before the first byte: the client never saw
        // X-Turn-Id, so nobody can resume it.
        Disposable http = started.events().subscribe();
        http.dispose();

        await(upstreamCancelled::get, "the abandoned generation to be cancelled");
        await(
                () ->
                        ResumableChatStreamService.STATUS_ABORTED.equals(
                                svc.snapshot(started.turnId()).status()),
                "the turn to be closed as aborted");
    }

    @Test
    void aReaderThatNeverArrivesDoesNotLeakTheGeneration() throws Exception {
        ResumableChatStreamService svc = service(true, 1000, Duration.ofMillis(200));
        AtomicBoolean upstreamCancelled = new AtomicBoolean(false);
        Started started =
                svc.start(
                        agentId,
                        "s1",
                        Flux.<String>never().doOnCancel(() -> upstreamCancelled.set(true)));

        await(upstreamCancelled::get, "the unattended generation to be cancelled");
        await(
                () ->
                        ResumableChatStreamService.STATUS_DETACH_TIMEOUT.equals(
                                svc.snapshot(started.turnId()).status()),
                "the turn to be closed as detach-timeout");
    }

    @Test
    void theDetachCancelRunsUnderTheTenantTheTurnStartedIn() throws Exception {
        ResumableChatStreamService svc = service(true, 1000, Duration.ofMillis(200));
        java.util.concurrent.atomic.AtomicReference<String> tenantAtCancel =
                new java.util.concurrent.atomic.AtomicReference<>();
        Flux<String> source =
                Flux.<String>never()
                        .doOnCancel(
                                () ->
                                        tenantAtCancel.set(
                                                TenantContext.current()
                                                        .map(TenantContext::getTenantId)
                                                        .orElse("none")));
        svc.start(agentId, "s1", source);

        await(() -> tenantAtCancel.get() != null, "the cancel");
        assertEquals("tenant_a", tenantAtCancel.get());
    }

    // ---- authorisation
    // ---------------------------------------------------------------------------------

    @Test
    void anotherTenantAnotherUserAndNoContextAllAnswer404() {
        ResumableChatStreamService svc = service();
        Started started = svc.start(agentId, "s1", Flux.just("secret"));
        started.events().blockLast(Duration.ofSeconds(5));
        String turnId = started.turnId();

        TenantContext.set(TenantContext.of("tenant_b", "alice", Set.of()));
        assertEquals(
                404,
                assertThrows(ResponseStatusException.class, () -> svc.resume(turnId, 0L))
                        .getStatusCode()
                        .value());
        assertEquals(
                404,
                assertThrows(ResponseStatusException.class, () -> svc.snapshot(turnId))
                        .getStatusCode()
                        .value());

        TenantContext.set(TenantContext.of("tenant_a", "mallory", Set.of()));
        assertEquals(
                404,
                assertThrows(ResponseStatusException.class, () -> svc.resume(turnId, 0L))
                        .getStatusCode()
                        .value());
        assertEquals(
                404,
                assertThrows(ResponseStatusException.class, () -> svc.snapshot(turnId))
                        .getStatusCode()
                        .value());

        TenantContext.clear();
        assertEquals(
                404,
                assertThrows(ResponseStatusException.class, () -> svc.resume(turnId, 0L))
                        .getStatusCode()
                        .value());

        // The owner still can.
        TenantContext.set(TenantContext.of("tenant_a", "alice", Set.of()));
        assertEquals("secret", svc.snapshot(turnId).text());
    }

    @Test
    void aRevokedAgentGrantStopsTheOwnerToo() {
        ResumableChatStreamService svc = service();
        Started started = svc.start(agentId, "s1", Flux.just("a"));
        started.events().blockLast(Duration.ofSeconds(5));

        ResponseStatusException denied = new ResponseStatusException(HttpStatus.NOT_FOUND, "agent");
        doThrow(denied).when(agentService).requireAccess(any(), any());

        assertThrows(ResponseStatusException.class, () -> svc.resume(started.turnId(), 0L));
        assertThrows(ResponseStatusException.class, () -> svc.snapshot(started.turnId()));
    }

    @Test
    void unknownAndMalformedTurnIdsAnswer404() {
        ResumableChatStreamService svc = service();

        for (String bad :
                new String[] {
                    null,
                    "",
                    "short",
                    "has space in it!",
                    "x".repeat(200),
                    UUID.randomUUID().toString()
                }) {
            ResponseStatusException e =
                    assertThrows(
                            ResponseStatusException.class,
                            () -> svc.resume(bad, 0L),
                            String.valueOf(bad));
            assertEquals(404, e.getStatusCode().value());
        }
    }

    @Test
    void mayAccessRequiresTheSameTenantAndTheSameSubject() {
        ResumableStreamBuffer.TurnMeta meta =
                new ResumableStreamBuffer.TurnMeta("t", "tenant_a", "alice", "agent", "s", 0L);

        assertTrue(ResumableChatStreamService.mayAccess(meta, "tenant_a", "alice"));
        assertFalse(ResumableChatStreamService.mayAccess(meta, "tenant_b", "alice"));
        assertFalse(ResumableChatStreamService.mayAccess(meta, "tenant_a", "bob"));
        assertFalse(ResumableChatStreamService.mayAccess(meta, "tenant_a", null));
        // Security-off deployments have no subject at all: both sides null still matches.
        ResumableStreamBuffer.TurnMeta anonymous =
                new ResumableStreamBuffer.TurnMeta("t", "_none", null, "agent", "s", 0L);
        assertTrue(ResumableChatStreamService.mayAccess(anonymous, "_none", null));
    }

    // ---- flag off
    // --------------------------------------------------------------------------------------

    @Test
    void flagOffAcceptsNothingAndExposesNoSurface() {
        ResumableChatStreamService svc = service(false, 1000, LONG_DETACH);

        assertFalse(svc.isEnabled());
        assertFalse(svc.accepts("1"));
        assertFalse(svc.accepts("true"));
        assertFalse(svc.accepts(null));
        assertEquals(
                404,
                assertThrows(ResponseStatusException.class, () -> svc.resume("abcdefgh-1234", 0L))
                        .getStatusCode()
                        .value());
        assertEquals(
                404,
                assertThrows(ResponseStatusException.class, () -> svc.snapshot("abcdefgh-1234"))
                        .getStatusCode()
                        .value());
    }

    @Test
    void flagOnStillRequiresThePerRequestOptIn() {
        ResumableChatStreamService svc = service();

        assertTrue(svc.accepts("1"));
        assertTrue(svc.accepts(" true "));
        assertFalse(svc.accepts(null));
        assertFalse(svc.accepts(""));
        assertFalse(svc.accepts("0"));
        assertFalse(svc.accepts("yes"));
    }

    // ---- reconnect-storm cap
    // ---------------------------------------------------------------------------

    @Test
    void resumeReadersPerUserAreCappedAndTheSlotIsGivenBackWhenOneEnds() {
        buffer = new InMemoryResumableStreamBuffer(1000, 600, 900, clock::get);
        ResumableChatStreamService svc =
                new ResumableChatStreamService(
                        true, buffer, agentService, LONG_DETACH, clock::get, 2);
        Sinks.Many<String> source = Sinks.many().multicast().onBackpressureBuffer();
        Started started = svc.start(agentId, "s1", source.asFlux());
        source.tryEmitNext("a");

        Disposable first = svc.resume(started.turnId(), 0L).subscribe(e -> {}, e -> {});
        Disposable second = svc.resume(started.turnId(), 0L).subscribe(e -> {}, e -> {});

        Throwable refused =
                assertThrows(
                        Throwable.class,
                        () ->
                                svc.resume(started.turnId(), 0L)
                                        .collectList()
                                        .block(Duration.ofSeconds(5)));
        ResponseStatusException status = assertInstanceOf(ResponseStatusException.class, refused);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, status.getStatusCode());

        first.dispose();
        AtomicBoolean thirdRefused = new AtomicBoolean(false);
        Disposable third =
                svc.resume(started.turnId(), 0L).subscribe(e -> {}, e -> thirdRefused.set(true));
        assertFalse(thirdRefused.get(), "the slot freed by the first reader is available again");
        third.dispose();
        second.dispose();
        source.tryEmitComplete();
    }

    // ---- errors
    // ----------------------------------------------------------------------------------------

    @Test
    void aFailureBeforeAnyOutputSurfacesAsTheOriginalErrorSoItStillMapsToAStatus() {
        ResumableChatStreamService svc = service();
        IllegalStateException boom = new IllegalStateException("provider rejected the request");
        Started started = svc.start(agentId, "s1", Flux.error(boom));

        Throwable thrown =
                assertThrows(
                        Throwable.class,
                        () -> started.events().collectList().block(Duration.ofSeconds(5)));
        assertSame(boom, thrown, "the original exception must surface unchanged");
    }

    @Test
    void aFailureAfterOutputEndsTheStreamWithAnErrorStatusFrame() {
        ResumableChatStreamService svc = service();
        Started started =
                svc.start(
                        agentId,
                        "s1",
                        Flux.concat(
                                Flux.just("partial"),
                                Flux.<String>error(new IllegalStateException("late"))));

        List<ServerSentEvent<String>> events =
                started.events().collectList().block(Duration.ofSeconds(5));

        assertEquals(2, events.size());
        assertEquals("partial", events.get(0).data());
        assertEquals(ResumableChatStreamService.TURN_END_EVENT, events.get(1).event());
        assertTrue(events.get(1).data().contains("\"status\":\"error\""));
    }

    // ---- snapshot
    // --------------------------------------------------------------------------------------

    @Test
    void snapshotReassemblesTheReplyWithoutStepEventsOrErrorMarkers() {
        ResumableChatStreamService svc = service();
        Started started =
                svc.start(
                        agentId,
                        "s1",
                        Flux.just(
                                "Hel",
                                AgentStepEventEmitter.STEP_EVENT_MARKER
                                        + "{\"type\":\"tool_call\"}",
                                "lo",
                                AgentController.STREAM_ERROR_MARKER + "oops"));
        started.events().blockLast(Duration.ofSeconds(5));

        TurnSnapshot snapshot = svc.snapshot(started.turnId());

        assertEquals("Hello", snapshot.text());
        assertEquals(ResumableChatStreamService.STATUS_COMPLETE, snapshot.status());
        assertEquals(5L, snapshot.lastEventId());
    }

    @Test
    void snapshotOfARunningTurnReportsRunningAndWhatIsBufferedSoFar() {
        ResumableChatStreamService svc = service();
        Sinks.Many<String> source = Sinks.many().multicast().onBackpressureBuffer();
        Started started = svc.start(agentId, "s1", source.asFlux());
        source.tryEmitNext("Hel");
        source.tryEmitNext("lo");

        TurnSnapshot snapshot = svc.snapshot(started.turnId());

        assertEquals(ResumableChatStreamService.STATUS_RUNNING, snapshot.status());
        assertEquals("Hello", snapshot.text());
        source.tryEmitComplete();
    }

    @Test
    void snapshotOfAGappedTurnHasNoText() {
        ResumableChatStreamService svc = service(true, 2, LONG_DETACH);
        Started started = svc.start(agentId, "s1", Flux.just("a", "b", "c", "d", "e"));
        started.events().blockLast(Duration.ofSeconds(5));

        TurnSnapshot snapshot = svc.snapshot(started.turnId());

        assertEquals(ResumableChatStreamService.STATUS_UNKNOWN, snapshot.status());
        assertNull(snapshot.text());
        assertInstanceOf(TurnSnapshot.class, snapshot);
    }
}
