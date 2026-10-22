package com.agenticai.core.agents.job.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.job.AgentJob;
import com.agenticai.core.agents.job.AgentJobExecutor;
import com.agenticai.core.agents.job.AgentJobRepository;
import com.agenticai.core.agents.job.AgentJobService;
import com.agenticai.core.agents.job.AgentJobStatus;
import com.agenticai.core.agents.job.dto.AgentJobResponse;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Cancelling a PARKED job, which before approval parking existed was a cancel that reported success
 * and stopped nothing.
 *
 * <p>The bug this locks: {@code AgentJobService#cancel}'s fast path is QUEUED-guarded and misses a
 * parked row; {@code requestCancel} writes only the flag column; and {@code stopLocally} finds no
 * worker to interrupt <b>because a parked job deliberately holds none</b>. So the row would sit
 * AWAITING_APPROVAL with {@code cancel_requested = true} forever - and, worse than merely being
 * stuck, it would hold V208's live-job-per-schedule unique index shut, muting that agent's whole
 * schedule while every layer reported itself healthy.
 *
 * <p>The fix is the third branch in {@code cancel}, and it has to be a guarded {@code
 * AWAITING_APPROVAL -> CANCELLED} UPDATE rather than the generic status compare-and-set: it also
 * stamps {@code finished_at} and clears {@code owner_instance}.
 */
class AgentJobParkedCancelTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void cancellingAParkedJobActuallyTerminatesIt() {
        AgentJob parked = AgentJobApprovalTestSupport.parkedJob(Instant.now().plusSeconds(3600));
        Fixture fixture = new Fixture(parked);

        AgentJobResponse response = fixture.service.cancel(parked.getAgentId(), parked.getId());

        assertEquals(AgentJobStatus.CANCELLED, response.status());
        assertTrue(response.terminal());
        verify(fixture.repository)
                .cancelIfAwaitingApproval(eq(parked.getId()), anyString(), any(Instant.class));
        // The cooperative path would have been a no-op: there is no worker to
        // interrupt, which is the entire point of parking.
        verify(fixture.repository, never())
                .requestCancel(any(UUID.class), anyString(), any(Instant.class));
        verify(fixture.executor, never()).stopLocally(any(UUID.class));
    }

    @Test
    void theQueuedFastPathIsTriedBeforeTheParkedOne() {
        // Ordering matters: a QUEUED job must never be routed through the
        // AWAITING_APPROVAL statement (it would match nothing and fall through
        // to the cooperative path, leaving a queued job alive with only a flag
        // set on it).
        AgentJob queued = AgentJobApprovalTestSupport.queuedJob(600, 15);
        Fixture fixture = new Fixture(queued);
        when(fixture.repository.cancelIfStillQueued(
                        eq(queued.getId()), anyString(), any(Instant.class)))
                .thenReturn(1);

        fixture.service.cancel(queued.getAgentId(), queued.getId());

        verify(fixture.repository)
                .cancelIfStillQueued(eq(queued.getId()), anyString(), any(Instant.class));
        verify(fixture.repository, never())
                .cancelIfAwaitingApproval(any(UUID.class), anyString(), any(Instant.class));
    }

    /** An {@link AgentJobService} wired over one job, with access checks stubbed open. */
    private static final class Fixture {

        private final AgentJobRepository repository;
        private final AgentJobExecutor executor = mock(AgentJobExecutor.class);
        private final AgentJobService service;

        private Fixture(AgentJob job) {
            this.repository = AgentJobApprovalTestSupport.repositoryFor(job);
            when(repository.findByIdAndAgentId(job.getId(), job.getAgentId()))
                    .thenReturn(Optional.of(job));
            this.service =
                    new AgentJobService(
                            repository,
                            mock(AgentRepository.class),
                            mock(AgentService.class),
                            executor,
                            AgentJobApprovalTestSupport.properties(),
                            new SimpleMeterRegistry(),
                            new ObjectMapper(),
                            15);
        }
    }
}
