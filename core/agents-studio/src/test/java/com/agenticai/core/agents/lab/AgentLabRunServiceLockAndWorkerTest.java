package com.agenticai.core.agents.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import com.agenticai.core.agents.lab.AgentLabDtos.LabRunResponse;
import com.agenticai.core.security.TenantContext;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** {@link AgentLabRunService}: the subject lock, saturation, store failures and the worker thread. */
class AgentLabRunServiceLockAndWorkerTest extends AgentLabRunServiceTestSupport {

    // ---- lock, saturation, store failures -----------------------------------------

    @Test
    void aSecondRunWhileOneIsLiveIsAConflict() {
        service = serviceWith(new ParkingExecutor());
        LabRunResponse first = service.start(agentId, consistency(3, "NONE"), USER);

        ResponseStatusException e = assertStatus(HttpStatus.CONFLICT,
            () -> service.start(agentId, consistency(3, "NONE"), USER));

        assertEquals("A lab run is already in progress. Wait for it to finish or cancel it.", e.getReason());
        assertEquals(1, store.runs.size());
        assertEquals(first.runId(), store.locks.get(TENANT + ":" + USER));
    }

    @Test
    void anotherUserIsNotBlockedByTheFirstUsersRun() {
        service = serviceWith(new ParkingExecutor());
        service.start(agentId, consistency(3, "NONE"), USER);

        TenantContext.set(TenantContext.of(TENANT, "u2", Set.of()));
        LabRunResponse second = service.start(agentId, consistency(3, "NONE"), "u2");

        assertEquals("QUEUED", second.status());
        assertEquals(2, store.runs.size());
    }

    @Test
    void aSaturatedPoolAnswers429AndLeavesNothingBehind() {
        service = serviceWith(new SaturatedExecutor());

        ResponseStatusException e = assertStatus(HttpStatus.TOO_MANY_REQUESTS,
            () -> service.start(agentId, consistency(3, "NONE"), USER));

        assertEquals("The lab is busy. Try again in a minute.", e.getReason());
        assertTrue(store.runs.isEmpty(), "the queued document is deleted");
        assertTrue(store.locks.isEmpty(), "the lock is released");
        verifyNoInteractions(completion);
    }

    @Test
    void aStoreThatCannotSaveAnswers503AndReleasesTheLock() {
        store.failSave = true;

        ResponseStatusException e = assertStatus(HttpStatus.SERVICE_UNAVAILABLE,
            () -> service.start(agentId, consistency(3, "NONE"), USER));

        assertEquals("The lab could not save the run. Try again in a minute.", e.getReason());
        assertTrue(store.locks.isEmpty());
        verifyNoInteractions(completion);
    }

    @Test
    void aStoreThatCannotBeReadAnswers503NotANotFoundThatWouldDropTheRun() {
        service = serviceWith(new ParkingExecutor());
        LabRunResponse started = service.start(agentId, consistency(3, "NONE"), USER);
        store.failFind = true;

        assertStatus(HttpStatus.SERVICE_UNAVAILABLE, () -> service.get(agentId, started.runId(), USER));
        assertStatus(HttpStatus.SERVICE_UNAVAILABLE, () -> service.cancel(agentId, started.runId(), USER));
    }

    // ---- the worker's thread ------------------------------------------------------

    @Test
    void theWorkerRunsUnderTheRequestsTenantAndClearsItAfterwards() throws Exception {
        AtomicReference<Optional<TenantContext>> duringCall = new AtomicReference<>();
        AtomicReference<Optional<TenantContext>> afterRun = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        when(completion.complete(any())).thenAnswer(call -> {
            duringCall.set(TenantContext.current());
            return "ok";
        });
        // One real worker thread: the request thread returns before the run does anything.
        AgentLabExecutor threaded = new AgentLabExecutor() {
            @Override
            public boolean trySubmit(Runnable runBody) {
                return super.trySubmit(() -> {
                    runBody.run();
                    afterRun.set(TenantContext.current());
                    finished.countDown();
                });
            }
        };
        service = serviceWith(threaded);

        LabRunResponse started = service.start(agentId, consistency(2, "NONE"), USER);

        assertTrue(finished.await(10, TimeUnit.SECONDS), "the worker finished");
        assertEquals("QUEUED", started.status());
        assertTrue(duringCall.get().isPresent());
        assertEquals(TENANT, duringCall.get().get().getTenantId());
        assertEquals(USER, duringCall.get().get().getSubject());
        assertEquals(Set.of("tenant_user"), duringCall.get().get().getRoles());
        assertTrue(afterRun.get().isEmpty(), "a pooled thread must not keep the tenant");
        // The request thread still has its own context: the run survived it returning.
        assertTrue(TenantContext.current().isPresent());
        assertEquals("SUCCEEDED", service.get(agentId, started.runId(), USER).status());
    }
}
