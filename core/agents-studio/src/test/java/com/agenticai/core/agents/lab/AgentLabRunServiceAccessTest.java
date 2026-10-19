package com.agenticai.core.agents.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import com.agenticai.core.agents.lab.AgentLabDtos.LabRunResponse;
import com.agenticai.core.security.TenantContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** {@link AgentLabRunService}: reading and cancelling someone else's run. */
class AgentLabRunServiceAccessTest extends AgentLabRunServiceTestSupport {

    // ---- reading and cancelling someone else's run ----------------------------------

    @Test
    void aRunIsOnlyVisibleToItsRequesterOnItsAgent() {
        LabRunResponse run = startAndRead(consistency(1, "NONE"));

        ResponseStatusException otherUser = assertStatus(HttpStatus.NOT_FOUND,
            () -> service.get(agentId, run.runId(), "u2"));
        assertEquals("Lab run not found or expired.", otherUser.getReason());
        assertStatus(HttpStatus.NOT_FOUND, () -> service.get(UUID.randomUUID(), run.runId(), USER));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.get(agentId, UUID.randomUUID().toString(), USER));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.get(agentId, "not-a-run-id", USER));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.cancel(agentId, run.runId(), "u2"));

        // Another tenant cannot address it even with the right id and the same subject.
        TenantContext.set(TenantContext.of("t2", USER, Set.of()));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.get(agentId, run.runId(), USER));
    }

    @Test
    void cancelOnARunningRunRaisesTheFlagAndThePollReportsItWithoutWritingTheDocument() {
        AtomicReference<AgentLabDtos.LabCancelResponse> response = new AtomicReference<>();
        AtomicReference<LabRunResponse> polled = new AtomicReference<>();
        AtomicReference<List<String>> writesAfterCancel = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        when(completion.complete(any())).thenAnswer(call -> {
            if (calls.incrementAndGet() == 1) {
                String runId = store.only().run().runId();
                assertFalse(service.get(agentId, runId, USER).cancelRequested());
                response.set(service.cancel(agentId, runId, USER));
                polled.set(service.get(agentId, runId, USER));
                writesAfterCancel.set(List.copyOf(store.statusWrites));
            }
            return "ok";
        });

        LabRunResponse run = startAndRead(consistency(3, "NONE"));

        assertEquals(run.runId(), response.get().runId());
        assertTrue(response.get().cancelRequested());
        assertTrue(polled.get().cancelRequested());
        assertEquals("RUNNING", polled.get().status());
        // cancel() does not rewrite a running run's document, so it cannot overwrite the worker's newer write.
        assertEquals(List.of("QUEUED", "RUNNING"), writesAfterCancel.get());
        assertEquals("CANCELLED", run.status());
        verify(completion, times(1)).complete(any());
    }

    @Test
    void cancellingAQueuedRunEndsItAtOnceFreesTheLockAndGivesBackItsQueueSlot() {
        ParkingExecutor parking = new ParkingExecutor();
        service = serviceWith(parking);
        LabRunResponse started = service.start(agentId, consistency(3, "NONE"), USER);
        now.addAndGet(1_000);

        AgentLabDtos.LabCancelResponse response = service.cancel(agentId, started.runId(), USER);

        assertEquals(started.runId(), response.runId());
        assertTrue(response.cancelRequested());
        LabRunResponse run = service.get(agentId, started.runId(), USER);
        assertEquals("CANCELLED", run.status());
        assertTrue(run.cancelRequested());
        assertNull(run.errorMessage());
        assertEquals(Long.valueOf(now.get()), run.finishedAtMs());
        assertEquals(0, run.completedCalls());
        assertEquals(3, run.cells().size());
        assertTrue(store.locks.isEmpty(), "no waiting for a worker to come free");
        assertTrue(parking.parked.isEmpty(), "the dead task no longer holds a queue slot");
        assertEquals(0, service.liveRunCount());

        // So the user can start again straight away instead of getting 409.
        LabRunResponse next = service.start(agentId, consistency(3, "NONE"), USER);
        assertEquals("QUEUED", next.status());
        assertEquals(1, parking.parked.size());
        verifyNoInteractions(completion);
    }

    @Test
    void aRunCancelledWhileQueuedMakesNoCallsIfItsWorkerStartsAnyway() {
        // The task could not be taken back out of the queue (it sits on another replica).
        ParkingExecutor parking = new ParkingExecutor() {
            @Override
            public boolean remove(Runnable runBody) {
                return false;
            }
        };
        service = serviceWith(parking);
        LabRunResponse started = service.start(agentId, consistency(3, "NONE"), USER);
        service.cancel(agentId, started.runId(), USER);
        LabRunResponse next = service.start(agentId, consistency(3, "NONE"), USER);

        parking.parked.get(0).run();
        bind();

        LabRunResponse run = service.get(agentId, started.runId(), USER);
        assertEquals("CANCELLED", run.status());
        assertEquals(0, run.completedCalls());
        verifyNoInteractions(completion);
        assertEquals(next.runId(), store.locks.get(TENANT + ":" + USER), "the newer run keeps its lock");
    }

    @Test
    void aWorkerThatPicksTheRunUpWhileItIsBeingCancelledStillStopsBeforeItsFirstCall() {
        ParkingExecutor parking = new ParkingExecutor();
        service = serviceWith(parking);
        LabRunResponse started = service.start(agentId, consistency(3, "NONE"), USER);
        Runnable worker = parking.parked.get(0);
        // cancel() read QUEUED and raised the flag; before its compare-and-set lands the
        // worker starts. Its first fenced write makes the run RUNNING, so the
        // compare-and-set loses - and the flag, raised first, stops the worker.
        store.beforeReplace = worker;

        service.cancel(agentId, started.runId(), USER);
        bind();

        LabRunResponse run = service.get(agentId, started.runId(), USER);
        assertEquals("CANCELLED", run.status());
        assertEquals(0, run.completedCalls());
        verifyNoInteractions(completion);
        assertTrue(store.locks.isEmpty());
    }

    @Test
    void cancelOnAFinishedRunIsAcceptedAndChangesNothing() {
        LabRunResponse run = startAndRead(consistency(1, "NONE"));

        assertTrue(service.cancel(agentId, run.runId(), USER).cancelRequested());

        assertFalse(store.isCancelRequested(TENANT, run.runId()));
        assertEquals("SUCCEEDED", service.get(agentId, run.runId(), USER).status());
    }

    @Test
    void theSubjectFallsBackToTheTenantContextWhenTheControllerHasNoPrincipal() {
        LabRunResponse started = service.start(agentId, consistency(1, "NONE"), null);
        bind();

        assertEquals(USER, store.only().requestedBy());
        assertEquals("SUCCEEDED", service.get(agentId, started.runId(), null).status());
    }
}
