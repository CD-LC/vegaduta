package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@link WorkflowRunRecoveryJob} exists because a JVM crash mid-run leaves a {@link WorkflowRun}
 * row RUNNING forever - these tests pin the two halves of "stale": old {@code startedAt} AND no
 * recent trace activity. A run that is merely old but whose trace is still growing (the engine
 * saves the trace after every node) must NOT be reaped, and one bad row must never stop the rest of
 * a tenant's batch.
 *
 * <p>{@link TenantSchemaIterator} is mocked with a {@code doAnswer} that immediately invokes the
 * given action with one fixed schema name - same convention as {@code
 * WorkflowEventOutboxRelayTest}; multi-tenant iteration and TenantContext handling are that class's
 * own separately-tested concern.
 */
class WorkflowRunRecoveryJobTest {

    private static final String SCHEMA = "tenant_test";
    private static final long STALE_AFTER_MINUTES = 120;

    private TenantSchemaIterator schemaIterator;
    private WorkflowRunRepository runRepository;
    private PlatformIssueService issueService;
    private WorkflowEventPublisher eventPublisher;
    private ObjectProvider<PgAdvisoryJobLock> jobLockProvider;
    private WorkflowRunRecoveryJob job;

    /**
     * Same reason as {@code WorkflowExecutionEngineFailProofTest#mapper}: the Spring-managed mapper
     * has JavaTimeModule registered, a bare {@code new ObjectMapper()} cannot round-trip {@code
     * NodeTraceEntry}'s Instants.
     */
    private static ObjectMapper mapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        schemaIterator = mock(TenantSchemaIterator.class);
        runRepository = mock(WorkflowRunRepository.class);
        issueService = mock(PlatformIssueService.class);
        eventPublisher = mock(WorkflowEventPublisher.class);
        jobLockProvider = mock(ObjectProvider.class);

        doAnswer(
                        invocation -> {
                            Consumer<String> action = invocation.getArgument(0);
                            action.accept(SCHEMA);
                            return null;
                        })
                .when(schemaIterator)
                .forEachActiveTenantSchema(any());

        job =
                new WorkflowRunRecoveryJob(
                        schemaIterator,
                        runRepository,
                        mapper(),
                        issueService,
                        eventPublisher,
                        jobLockProvider,
                        STALE_AFTER_MINUTES);
    }

    /**
     * The entity deliberately has no setter for {@code startedAt} (the constructor stamps {@code
     * now()}), so tests back-date it reflectively - same precedent as {@code
     * WorkflowExecutionEngineFailProofTest}'s {@code definitionWithId}.
     */
    private static WorkflowRun runStartedAgo(Duration age) throws ReflectiveOperationException {
        WorkflowRun run =
                new WorkflowRun(
                        UUID.randomUUID(), UUID.randomUUID(), "Test Workflow", "user@example.com");
        Field startedAt = WorkflowRun.class.getDeclaredField("startedAt");
        startedAt.setAccessible(true);
        startedAt.set(run, Instant.now().minus(age));
        return run;
    }

    @Test
    void staleRunIsMarkedFailedWithInterruptedReasonAndRecoveryTraceEntry()
            throws ReflectiveOperationException {
        WorkflowRun stale = runStartedAgo(Duration.ofHours(3));
        when(runRepository.findByStatusAndStartedAtBefore(eq(WorkflowRunStatus.RUNNING), any()))
                .thenReturn(List.of(stale));

        job.sweepAllTenants();

        assertEquals(WorkflowRunStatus.FAILED, stale.getStatus());
        assertEquals(WorkflowRunRecoveryJob.FAILURE_REASON, stale.getErrorMessage());
        assertNotNull(stale.getCompletedAt());
        assertTrue(
                stale.getTraceJson().contains("\"nodeId\":\"recovery\""),
                "expected an appended recovery trace entry, got: " + stale.getTraceJson());
        assertTrue(
                stale.getTraceJson().contains(WorkflowRunRecoveryJob.FAILURE_REASON),
                "expected the trace entry to carry the interrupted reason, got: "
                        + stale.getTraceJson());
        verify(runRepository).save(stale);
        verify(eventPublisher)
                .publish(eq("workflow.run.failed"), eq(stale.getId().toString()), any());
        verify(issueService)
                .record(
                        any(),
                        eq(stale.getWorkflowId().toString()),
                        any(),
                        anyString(),
                        anyString());
    }

    @Test
    void staleCandidateWithRecentTraceActivityIsLeftRunning() throws Exception {
        // startedAt is well past the threshold, but the trace shows a node
        // finished five minutes ago - a legitimately long, still-progressing
        // run, exactly the case the trace-heartbeat refinement exists for.
        WorkflowRun longRunning = runStartedAgo(Duration.ofHours(3));
        Instant now = Instant.now();
        longRunning.updateTrace(
                mapper().writeValueAsString(
                                List.of(
                                        new NodeTraceEntry(
                                                "n1",
                                                "agent",
                                                "Agent",
                                                "OK",
                                                "done",
                                                now.minus(Duration.ofMinutes(10)),
                                                now.minus(Duration.ofMinutes(5))))));
        when(runRepository.findByStatusAndStartedAtBefore(eq(WorkflowRunStatus.RUNNING), any()))
                .thenReturn(List.of(longRunning));

        job.sweepAllTenants();

        assertEquals(WorkflowRunStatus.RUNNING, longRunning.getStatus());
        verify(runRepository, never()).save(any());
        verify(eventPublisher, never()).publish(anyString(), anyString(), any());
    }

    @Test
    void freshRunsAreNeverCandidatesAndTheCutoffMatchesTheConfiguredThreshold() {
        // Fresh runs are excluded by the SQL predicate itself, so the load-
        // bearing assertion is the cutoff this job derives: startedAt-based,
        // staleAfterMinutes before "now".
        when(runRepository.findByStatusAndStartedAtBefore(eq(WorkflowRunStatus.RUNNING), any()))
                .thenReturn(List.of());

        job.sweepAllTenants();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(runRepository)
                .findByStatusAndStartedAtBefore(eq(WorkflowRunStatus.RUNNING), cutoff.capture());
        Instant expected = Instant.now().minus(Duration.ofMinutes(STALE_AFTER_MINUTES));
        long driftSeconds = Math.abs(Duration.between(expected, cutoff.getValue()).getSeconds());
        assertTrue(
                driftSeconds < 60,
                "cutoff should be ~"
                        + STALE_AFTER_MINUTES
                        + "min ago, drifted "
                        + driftSeconds
                        + "s");
        verify(runRepository, never()).save(any());
    }

    @Test
    void oneFailingRowDoesNotStopTheRestOfTheTenantBatch() throws ReflectiveOperationException {
        WorkflowRun first = runStartedAgo(Duration.ofHours(4));
        WorkflowRun second = runStartedAgo(Duration.ofHours(4));
        when(runRepository.findByStatusAndStartedAtBefore(eq(WorkflowRunStatus.RUNNING), any()))
                .thenReturn(List.of(first, second));
        when(runRepository.save(any(WorkflowRun.class)))
                .thenThrow(new RuntimeException("simulated DB failure on first row"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        job.sweepAllTenants();

        assertEquals(WorkflowRunStatus.FAILED, second.getStatus());
        assertEquals(WorkflowRunRecoveryJob.FAILURE_REASON, second.getErrorMessage());
        verify(runRepository, times(2)).save(any(WorkflowRun.class));
    }

    @Test
    void unparseableStoredTraceIsPreservedByteForByte() throws ReflectiveOperationException {
        WorkflowRun stale = runStartedAgo(Duration.ofHours(3));
        String corrupt = "not-json{{{";
        stale.updateTrace(corrupt);
        when(runRepository.findByStatusAndStartedAtBefore(eq(WorkflowRunStatus.RUNNING), any()))
                .thenReturn(List.of(stale));

        job.sweepAllTenants();

        // Still reaped (no readable evidence of progress), but the partial
        // trace bytes are not sacrificed for the bookkeeping entry -
        // errorMessage carries the reason instead.
        assertEquals(WorkflowRunStatus.FAILED, stale.getStatus());
        assertEquals(WorkflowRunRecoveryJob.FAILURE_REASON, stale.getErrorMessage());
        assertEquals(corrupt, stale.getTraceJson());
    }

    @Test
    void scheduledTickRunsUnderTheAdvisoryLockWhenTheBeanIsPresent() {
        PgAdvisoryJobLock lock = mock(PgAdvisoryJobLock.class);
        when(jobLockProvider.getIfAvailable()).thenReturn(lock);
        doAnswer(
                        invocation -> {
                            ((Runnable) invocation.getArgument(1)).run();
                            return true;
                        })
                .when(lock)
                .tryRunExclusive(anyString(), any(Runnable.class));

        job.reapStaleRuns();

        verify(lock).tryRunExclusive(eq("workflow-run-recovery"), any(Runnable.class));
        verify(schemaIterator).forEachActiveTenantSchema(any());
    }

    @Test
    void scheduledTickSweepsUnguardedWhenTheLockBeanIsAbsent() {
        when(jobLockProvider.getIfAvailable()).thenReturn(null);

        job.reapStaleRuns();

        verify(schemaIterator).forEachActiveTenantSchema(any());
    }
}
