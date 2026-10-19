package com.agenticai.core.agents.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import com.agenticai.core.agents.lab.AgentLabDtos.LabCellResult;
import com.agenticai.core.agents.lab.AgentLabDtos.LabRunDocument;
import com.agenticai.core.agents.lab.AgentLabDtos.LabRunResponse;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** {@link AgentLabRunService}: dead runs, queued runs and the compare-and-set fence. */
class AgentLabRunServiceDeadRunsTest extends AgentLabRunServiceTestSupport {

    // ---- dead runs, queued runs and the fence ---------------------------------------

    @Test
    void aRunWhoseJvmIsGoneIsFailedOnReadAndTheUserCanStartAgainAtOnce() {
        service = serviceWith(new ParkingExecutor());
        LabRunResponse started = service.start(agentId, consistency(3, "NONE"), USER);
        assertTrue(store.isAlive(TENANT, started.runId()), "start marks the run alive before anyone can read it");
        assertEquals("QUEUED", service.get(agentId, started.runId(), USER).status());

        // The server restarted: nothing refreshes the mark any more and it lapsed.
        store.alive.clear();
        now.addAndGet(TimeUnit.SECONDS.toMillis(91));
        LabRunResponse dead = service.get(agentId, started.runId(), USER);

        assertEquals("FAILED", dead.status());
        assertEquals("The run stopped responding (the server may have restarted). Start it again.",
            dead.errorMessage());
        assertEquals(Long.valueOf(now.get()), dead.finishedAtMs());
        assertTrue(store.locks.isEmpty(), "the dead run's lock is freed");
        assertTrue(store.isCancelRequested(TENANT, started.runId()), "a worker that is still there is told to stop");

        assertEquals(0, service.liveRunCount());

        LabRunResponse next = service.start(agentId, consistency(3, "NONE"), USER);
        assertEquals("QUEUED", next.status());
        assertNotEquals(started.runId(), next.runId());
        assertEquals("FAILED", service.get(agentId, started.runId(), USER).status());
        assertEquals(next.runId(), store.locks.get(TENANT + ":" + USER));
        verifyNoInteractions(completion);
    }

    @Test
    void aWorkerThatOutlivedItsFailedRunTouchesNeitherThatRunNorTheNextOne() {
        // Parks the task and refuses to give it back: the worker of a run that was failed
        // from outside still gets a thread later (another replica's queue, say).
        ParkingExecutor parking = new ParkingExecutor() {
            @Override
            public boolean remove(Runnable runBody) {
                return false;
            }
        };
        service = serviceWith(parking);
        LabRunResponse started = service.start(agentId, consistency(3, "NONE"), USER);
        store.alive.clear();
        assertEquals("FAILED", service.get(agentId, started.runId(), USER).status());
        LabRunResponse next = service.start(agentId, consistency(3, "NONE"), USER);

        parking.parked.get(0).run();
        bind();

        assertEquals("FAILED", service.get(agentId, started.runId(), USER).status());
        assertEquals("QUEUED", service.get(agentId, next.runId(), USER).status());
        assertEquals(next.runId(), store.locks.get(TENANT + ":" + USER), "the newer run keeps its lock");
        verifyNoInteractions(completion);
    }

    @Test
    void aRunWaitingInTheQueueIsNotFailedForWritingNothing() {
        service = serviceWith(new ParkingExecutor());
        LabRunResponse started = service.start(agentId, consistency(3, "NONE"), USER);

        // Both workers are busy for 19 minutes. The queued run writes nothing in that
        // time; the heartbeat is what says it is still held by a live JVM.
        for (int minute = 1; minute <= 19; minute++) {
            now.addAndGet(TimeUnit.MINUTES.toMillis(1));
            LabRunResponse polled = service.get(agentId, started.runId(), USER);
            assertEquals("QUEUED", polled.status(), "after " + minute + " minutes in the queue");
            assertNull(polled.errorMessage());
        }
        assertEquals(started.runId(), store.locks.get(TENANT + ":" + USER));
        assertFalse(store.isCancelRequested(TENANT, started.runId()));
        // And the user's second start is still a conflict, not a takeover of a "stale" run.
        assertStatus(HttpStatus.CONFLICT, () -> service.start(agentId, consistency(3, "NONE"), USER));
        assertEquals(1, store.runs.size());
    }

    @Test
    void aRunningRunInsideOneLongModelCallIsNotFailed() {
        AtomicReference<LabRunResponse> polledMidCall = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        when(completion.complete(any())).thenAnswer(call -> {
            if (calls.incrementAndGet() == 1) {
                // One call that takes nine minutes - inside the provider read timeout.
                now.addAndGet(TimeUnit.MINUTES.toMillis(9));
                polledMidCall.set(service.get(agentId, store.only().run().runId(), USER));
            }
            return "ok";
        });

        LabRunResponse run = startAndRead(consistency(2, "NONE"));

        assertEquals("RUNNING", polledMidCall.get().status());
        assertNull(polledMidCall.get().errorMessage());
        assertEquals("SUCCEEDED", run.status());
        assertEquals(List.of("DONE", "DONE"), run.cells().stream().map(LabCellResult::status).toList());
        verify(completion, times(2)).complete(any());
    }

    @Test
    void theHeartbeatKeepsQueuedAndRunningRunsAliveAndDropsThemWhenTheyEnd() {
        ParkingExecutor parking = new ParkingExecutor();
        service = serviceWith(parking);
        LabRunResponse queued = service.start(agentId, consistency(1, "NONE"), USER);
        assertEquals(1, service.liveRunCount());

        // Two periods on, the first mark would have lapsed; the refresh put it back.
        store.alive.clear();
        service.refreshLiveness();
        assertTrue(store.isAlive(TENANT, queued.runId()));

        // A worker picks the run up: still held, and refreshed, while it is inside a call.
        when(completion.complete(any())).thenAnswer(call -> {
            store.alive.clear();
            service.refreshLiveness();
            assertTrue(store.isAlive(TENANT, queued.runId()), "a running run is refreshed too");
            return "ok";
        });
        parking.parked.get(0).run();
        bind();

        assertEquals("SUCCEEDED", service.get(agentId, queued.runId(), USER).status());
        assertEquals(0, service.liveRunCount(), "a finished run is off the heartbeat");
        store.alive.clear();
        service.refreshLiveness();
        assertFalse(store.isAlive(TENANT, queued.runId()));
    }

    @Test
    void aFailedRefreshOfOneRunDoesNotStopTheHeartbeat() {
        service = serviceWith(new ParkingExecutor());
        LabRunResponse queued = service.start(agentId, consistency(1, "NONE"), USER);

        store.failMarkAlive = true;
        service.refreshLiveness();   // must not throw: an escaped exception cancels the schedule
        store.failMarkAlive = false;
        store.alive.clear();
        service.refreshLiveness();

        assertTrue(store.isAlive(TENANT, queued.runId()));
    }

    @Test
    void aRejectedRunIsNotKeptOnTheHeartbeat() {
        service = serviceWith(new SaturatedExecutor());

        assertStatus(HttpStatus.TOO_MANY_REQUESTS, () -> service.start(agentId, consistency(1, "NONE"), USER));

        assertEquals(0, service.liveRunCount());
    }

    @Test
    void aRunThatCannotBeMarkedAliveIsNotStarted() {
        store.failMarkAlive = true;

        assertStatus(HttpStatus.SERVICE_UNAVAILABLE, () -> service.start(agentId, consistency(1, "NONE"), USER));

        assertTrue(store.runs.isEmpty());
        assertTrue(store.locks.isEmpty());
        assertEquals(0, service.liveRunCount());
        verifyNoInteractions(completion);
    }

    @Test
    void failingADeadRunNeverOverwritesAResultTheWorkerWroteInBetween() {
        service = serviceWith(new ParkingExecutor());
        LabRunResponse started = service.start(agentId, consistency(1, "NONE"), USER);
        LabRunDocument read = store.only();
        store.alive.clear();
        // The reader has read a live document and found no liveness mark. Before its
        // write lands, the worker - alive after all - stores the final result.
        LabRunResponse r = read.run();
        LabRunDocument finished = new LabRunDocument(
            new LabRunResponse(r.runId(), r.agentId(), r.kind(), "SUCCEEDED", 1, 1, false, null,
                r.createdAtMs(), r.updatedAtMs() + 5, r.updatedAtMs() + 5,
                List.of(new LabCellResult("k0", "DONE", "Thirty days.", false, null, "NONE", null, null, 40L)),
                null),
            read.requestedBy());
        store.beforeReplace = () -> store.runs.put(TENANT + ":" + r.runId(), finished);

        LabRunResponse polled = service.get(agentId, started.runId(), USER);

        assertEquals("SUCCEEDED", polled.status());
        assertEquals("Thirty days.", polled.cells().get(0).answer());
        assertEquals(finished, store.only(), "the stored result is untouched");
        assertEquals(List.of("QUEUED"), store.statusWrites, "the reader wrote nothing");
    }

    @Test
    void failingADeadRunDoesNotOverwriteNewerProgressEither() {
        service = serviceWith(new ParkingExecutor());
        LabRunResponse started = service.start(agentId, consistency(2, "NONE"), USER);
        LabRunDocument read = store.only();
        store.alive.clear();
        LabRunResponse r = read.run();
        LabRunDocument progressed = new LabRunDocument(
            new LabRunResponse(r.runId(), r.agentId(), r.kind(), "RUNNING", 2, 1, false, null,
                r.createdAtMs(), r.updatedAtMs() + 5, null, r.cells(), null),
            read.requestedBy());
        store.beforeReplace = () -> store.runs.put(TENANT + ":" + r.runId(), progressed);

        LabRunResponse polled = service.get(agentId, started.runId(), USER);

        assertEquals("RUNNING", polled.status());
        assertEquals(1, polled.completedCalls());
        assertEquals(progressed, store.only());
        assertEquals(started.runId(), store.locks.get(TENANT + ":" + USER), "a run that was not ended keeps its lock");
    }

    @Test
    void aRunStillQueuedAtTheDeadlineIsFailedAsNeverStarted() {
        ParkingExecutor parking = new ParkingExecutor();
        service = serviceWith(parking);
        LabRunResponse started = service.start(agentId, consistency(3, "NONE"), USER);

        now.addAndGet(TimeUnit.MINUTES.toMillis(20));
        LabRunResponse polled = service.get(agentId, started.runId(), USER);

        assertEquals("FAILED", polled.status());
        assertEquals(AgentLabRunService.QUEUE_TIMEOUT_MESSAGE, polled.errorMessage());
        assertTrue(polled.errorMessage().endsWith("."));
        assertEquals(0, polled.completedCalls());
        assertTrue(store.locks.isEmpty());
        assertTrue(parking.parked.isEmpty(), "its slot in the queue is given back");
        assertEquals(0, service.liveRunCount());
        // Not the dead-run path: no worker needs telling, so no cancel flag.
        assertFalse(store.isCancelRequested(TENANT, started.runId()));
    }

    @Test
    void startClearsADeadHolderItselfWhenNobodyPolledTheDeadRun() {
        service = serviceWith(new ParkingExecutor());
        LabRunResponse dead = service.start(agentId, consistency(3, "NONE"), USER);
        store.alive.clear();

        LabRunResponse next = service.start(agentId, consistency(3, "NONE"), USER);

        assertEquals("QUEUED", next.status());
        assertEquals("FAILED", service.get(agentId, dead.runId(), USER).status());
        assertEquals(next.runId(), store.locks.get(TENANT + ":" + USER));
    }

    @Test
    void startClearsALockLeftBehindByARunThatAlreadyEnded() {
        LabRunResponse finished = startAndRead(consistency(1, "NONE"));
        assertEquals("SUCCEEDED", finished.status());
        // The worker's release was lost (a Redis blip); the lock still names the finished run.
        store.locks.put(TENANT + ":" + USER, finished.runId());

        LabRunResponse next = startAndRead(consistency(1, "NONE"));

        assertEquals("SUCCEEDED", next.status());
    }

    @Test
    void aLockWhoseRunHasNoDocumentYetIsRespected() {
        // What a concurrent start looks like between taking the lock and writing its document.
        store.locks.put(TENANT + ":" + USER, UUID.randomUUID().toString());

        assertStatus(HttpStatus.CONFLICT, () -> service.start(agentId, consistency(1, "NONE"), USER));
    }

    @Test
    void aWorkerWriteAfterTheRunWasFailedDoesNotChangeTheStoredStatus() {
        AtomicInteger calls = new AtomicInteger();
        when(completion.complete(any())).thenAnswer(call -> {
            if (calls.incrementAndGet() == 1) {
                // While the first call is in flight, a reader marks the run FAILED.
                LabRunDocument doc = store.only();
                LabRunResponse r = doc.run();
                store.runs.put(TENANT + ":" + r.runId(), new LabRunDocument(
                    new LabRunResponse(r.runId(), r.agentId(), r.kind(), "FAILED", r.plannedCalls(),
                        r.completedCalls(), false, "Marked failed elsewhere.", r.createdAtMs(), r.updatedAtMs(),
                        r.updatedAtMs(), r.cells(), r.similarity()),
                    doc.requestedBy()));
                store.statusWrites.clear();
            }
            return "late answer";
        });

        LabRunResponse run = startAndRead(consistency(3, "NONE"));

        assertEquals("FAILED", run.status());
        assertEquals("Marked failed elsewhere.", run.errorMessage());
        assertEquals("PENDING", run.cells().get(0).status(), "the late answer was never written");
        assertTrue(store.statusWrites.isEmpty(), "the worker wrote nothing after the run became terminal");
        verify(completion, times(1)).complete(any());
    }

    @Test
    void aWorkerWhoseDocumentIsGoneStopsWithoutRecreatingIt() {
        when(completion.complete(any())).thenAnswer(call -> {
            store.runs.clear();
            return "ok";
        });

        LabRunResponse started = service.start(agentId, consistency(3, "NONE"), USER);
        bind();

        assertTrue(store.runs.isEmpty());
        assertStatus(HttpStatus.NOT_FOUND, () -> service.get(agentId, started.runId(), USER));
        verify(completion, times(1)).complete(any());
    }

    @Test
    void theWorkerDoesNotReleaseALockHeldByADifferentRun() {
        String newerRun = UUID.randomUUID().toString();
        when(completion.complete(any())).thenAnswer(call -> {
            // This run was failed as stale and the user started another one.
            store.locks.put(TENANT + ":" + USER, newerRun);
            return "ok";
        });

        startAndRead(consistency(2, "NONE"));

        assertEquals(newerRun, store.locks.get(TENANT + ":" + USER));
    }
}
