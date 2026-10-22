package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Waking a parked run: due waits resume exactly once through the existing PAUSED re-entry, a run
 * that is no longer parked at the wait's checkpoint retires the row instead of being woken, and the
 * webhook / timeout paths shape the node output as documented.
 */
class WorkflowWaitResumeServiceTest {

    private static final UUID WORKFLOW_ID = UUID.randomUUID();

    private WorkflowWaitRepository waits;
    private WorkflowRunRepository runs;
    private WorkflowBackgroundRunService backgroundRuns;
    private WorkflowWaitResumeService service;

    private static ObjectMapper mapper() {
        return com.agenticai.core.json.JsonMappers.withJavaTime();
    }

    @BeforeEach
    void setUp() {
        waits = mock(WorkflowWaitRepository.class);
        runs = mock(WorkflowRunRepository.class);
        backgroundRuns = mock(WorkflowBackgroundRunService.class);
        service = new WorkflowWaitResumeService(waits, runs, backgroundRuns, mapper());
    }

    private static WorkflowRun parkedRun(String resumeNodeId, String resumeInput) {
        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), WORKFLOW_ID, "wf", "user-1");
        run.pause("[]", resumeNodeId, resumeInput);
        return run;
    }

    private static WorkflowWait waitRow(WorkflowRun run, String mode, boolean failOnTimeout) {
        return new WorkflowWait(
                UUID.randomUUID(),
                run.getId(),
                WORKFLOW_ID,
                "w1",
                "a1",
                mode,
                Instant.now().minusSeconds(5),
                failOnTimeout);
    }

    private void due(WorkflowWait... due) {
        when(waits.findByStatusAndWakeAtLessThanEqualOrderByWakeAtAsc(
                        eq(WorkflowWait.Status.WAITING), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(due));
    }

    private void claimSucceeds(WorkflowWait wait) {
        when(waits.claim(
                        eq(wait.getId()),
                        eq(WorkflowWait.Status.WAITING),
                        eq(WorkflowWait.Status.RESUMED),
                        any(Instant.class)))
                .thenReturn(1);
    }

    private void checkpointUpdates(WorkflowRun run) {
        when(runs.updateParkedCheckpoint(
                        eq(run.getId()), eq(WorkflowRunStatus.PAUSED), anyString(), any()))
                .thenReturn(1);
    }

    // ---- the sweep
    // ----------------------------------------------------------------------------------------------

    @Test
    void aDueDurationWaitResumesTheRunThroughTheNormalPausedReEntry() {
        WorkflowRun run = parkedRun("a1", "upstream");
        WorkflowWait wait = waitRow(run, "DURATION", true);
        when(runs.findById(run.getId())).thenReturn(Optional.of(run));
        due(wait);
        claimSucceeds(wait);
        checkpointUpdates(run);

        int handled = service.sweepDue(Instant.now());

        assertEquals(1, handled);
        ArgumentCaptor<String> trace = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
        verify(runs)
                .updateParkedCheckpoint(
                        eq(run.getId()),
                        eq(WorkflowRunStatus.PAUSED),
                        trace.capture(),
                        input.capture());
        assertEquals(
                "upstream", input.getValue(), "a timed wait passes its input through untouched");
        assertTrue(
                trace.getValue().contains("Wait finished - wake time reached."), trace.getValue());
        verify(backgroundRuns).resumeRun(WORKFLOW_ID, run.getId());
    }

    @Test
    void aRaceLostOnTheClaimLeavesTheRunAlone() {
        WorkflowRun run = parkedRun("a1", "in");
        WorkflowWait wait = waitRow(run, "DURATION", true);
        when(runs.findById(run.getId())).thenReturn(Optional.of(run));
        due(wait);
        when(waits.claim(
                        eq(wait.getId()),
                        eq(WorkflowWait.Status.WAITING),
                        eq(WorkflowWait.Status.RESUMED),
                        any(Instant.class)))
                .thenReturn(0);

        assertEquals(0, service.sweepDue(Instant.now()));

        verify(backgroundRuns, never()).resumeRun(any(), any());
        verify(runs, never()).updateParkedCheckpoint(any(), any(), any(), any());
    }

    @Test
    void aCancelledRunRetiresTheWaitInsteadOfWakingIt() {
        WorkflowRun run = parkedRun("a1", "in");
        run.clearPauseEnvelope();
        run.complete(
                WorkflowRunStatus.CANCELLED,
                "[]",
                "Cancelled by user request while paused.",
                null,
                null);
        WorkflowWait wait = waitRow(run, "DURATION", true);
        when(runs.findById(run.getId())).thenReturn(Optional.of(run));
        due(wait);

        assertEquals(0, service.sweepDue(Instant.now()));

        verify(waits)
                .claim(
                        eq(wait.getId()),
                        eq(WorkflowWait.Status.WAITING),
                        eq(WorkflowWait.Status.CANCELLED),
                        any(Instant.class));
        verify(backgroundRuns, never()).resumeRun(any(), any());
    }

    @Test
    void aRunParkedSomewhereElseNowRetiresTheStaleWait() {
        WorkflowRun run = parkedRun("some-other-node", "in");
        WorkflowWait wait = waitRow(run, "DURATION", true); // resumes at a1
        when(runs.findById(run.getId())).thenReturn(Optional.of(run));
        due(wait);

        service.sweepDue(Instant.now());

        verify(waits)
                .claim(
                        eq(wait.getId()),
                        eq(WorkflowWait.Status.WAITING),
                        eq(WorkflowWait.Status.CANCELLED),
                        any(Instant.class));
        verify(backgroundRuns, never()).resumeRun(any(), any());
    }

    @Test
    void aMissingRunRetiresTheWait() {
        WorkflowRun run = parkedRun("a1", "in");
        WorkflowWait wait = waitRow(run, "DURATION", true);
        when(runs.findById(run.getId())).thenReturn(Optional.empty());
        due(wait);

        service.sweepDue(Instant.now());

        verify(waits)
                .claim(
                        eq(wait.getId()),
                        eq(WorkflowWait.Status.WAITING),
                        eq(WorkflowWait.Status.CANCELLED),
                        any(Instant.class));
    }

    @Test
    void aStillRunningRunIsGivenTimeToParkAndOnlyAnOrphanIsRetired() {
        WorkflowRun running =
                new WorkflowRun(UUID.randomUUID(), WORKFLOW_ID, "wf", "user-1"); // RUNNING
        WorkflowWait wait = waitRow(running, "DURATION", true);
        when(runs.findById(running.getId())).thenReturn(Optional.of(running));
        due(wait);

        service.sweepDue(Instant.now());
        verify(waits, never()).claim(any(), any(), any(), any());

        service.sweepDue(Instant.now().plus(Duration.ofMinutes(11)));
        verify(waits)
                .claim(
                        eq(wait.getId()),
                        eq(WorkflowWait.Status.WAITING),
                        eq(WorkflowWait.Status.CANCELLED),
                        any(Instant.class));
        verify(backgroundRuns, never()).resumeRun(any(), any());
    }

    @Test
    void anOperatorClaimBetweenTheReadAndTheCheckpointLeavesTheRunAlone() {
        WorkflowRun run = parkedRun("a1", "in");
        WorkflowWait wait = waitRow(run, "DURATION", true);
        when(runs.findById(run.getId())).thenReturn(Optional.of(run));
        due(wait);
        claimSucceeds(wait);
        when(runs.updateParkedCheckpoint(any(), any(), any(), any())).thenReturn(0);

        assertEquals(0, service.sweepDue(Instant.now()));

        verify(backgroundRuns, never()).resumeRun(any(), any());
    }

    @Test
    void oneBadRowDoesNotStopTheRestOfTheBatch() {
        WorkflowRun bad = parkedRun("a1", "in");
        WorkflowRun good = parkedRun("a1", "in");
        WorkflowWait badWait = waitRow(bad, "DURATION", true);
        WorkflowWait goodWait = waitRow(good, "DURATION", true);
        when(runs.findById(bad.getId())).thenThrow(new RuntimeException("db blip"));
        when(runs.findById(good.getId())).thenReturn(Optional.of(good));
        due(badWait, goodWait);
        claimSucceeds(goodWait);
        checkpointUpdates(good);

        assertEquals(1, service.sweepDue(Instant.now()));

        verify(backgroundRuns).resumeRun(WORKFLOW_ID, good.getId());
    }

    // ---- webhook mode
    // ---------------------------------------------------------------------------------------------

    @Test
    void aTimedOutWebhookWaitFailsTheRunWhenConfiguredTo() {
        WorkflowRun run = parkedRun("a1", "in");
        WorkflowWait wait = waitRow(run, "WEBHOOK", true);
        when(runs.findById(run.getId())).thenReturn(Optional.of(run));
        due(wait);
        claimSucceeds(wait);
        when(runs.compareAndSetStatus(
                        run.getId(), WorkflowRunStatus.PAUSED, WorkflowRunStatus.FAILED))
                .thenReturn(1);

        assertEquals(1, service.sweepDue(Instant.now()));

        assertEquals(WorkflowRunStatus.FAILED, run.getStatus());
        assertTrue(run.getErrorMessage().contains("timed out"), run.getErrorMessage());
        assertEquals(null, run.getPausedNodeId(), "the pause envelope is cleared");
        verify(runs).save(run);
        verify(backgroundRuns, never()).resumeRun(any(), any());
    }

    @Test
    void aTimedOutWebhookWaitCanContinueWithATimedOutFlag() throws Exception {
        WorkflowRun run = parkedRun("a1", "upstream");
        WorkflowWait wait = waitRow(run, "WEBHOOK", false);
        when(runs.findById(run.getId())).thenReturn(Optional.of(run));
        due(wait);
        claimSucceeds(wait);
        checkpointUpdates(run);

        service.sweepDue(Instant.now());

        ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
        verify(runs)
                .updateParkedCheckpoint(
                        eq(run.getId()),
                        eq(WorkflowRunStatus.PAUSED),
                        anyString(),
                        input.capture());
        JsonNode envelope = mapper().readTree(input.getValue());
        assertTrue(envelope.get("timedOut").asBoolean());
        assertEquals("upstream", envelope.get("input").asString());
        verify(backgroundRuns).resumeRun(WORKFLOW_ID, run.getId());
    }

    @Test
    void theResumeUrlResumesTheWaitWithThePostedBodyAsTheOutput() throws Exception {
        WorkflowRun run = parkedRun("a1", "upstream");
        WorkflowWait wait = waitRow(run, "WEBHOOK", true);
        when(waits.findFirstByRunIdAndStatus(run.getId(), WorkflowWait.Status.WAITING))
                .thenReturn(Optional.of(wait));
        when(runs.findById(run.getId())).thenReturn(Optional.of(run));
        claimSucceeds(wait);
        checkpointUpdates(run);

        WorkflowWaitResumeService.ResumeOutcome outcome =
                service.resumeByWebhook(run.getId(), "w1", Map.of("approved", true));

        assertEquals(WorkflowWaitResumeService.ResumeOutcome.RESUMED, outcome);
        ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> trace = ArgumentCaptor.forClass(String.class);
        verify(runs)
                .updateParkedCheckpoint(
                        eq(run.getId()),
                        eq(WorkflowRunStatus.PAUSED),
                        trace.capture(),
                        input.capture());
        JsonNode envelope = mapper().readTree(input.getValue());
        assertTrue(envelope.get("resume").get("approved").asBoolean());
        assertEquals("upstream", envelope.get("input").asString());
        assertTrue(
                trace.getValue().contains("Resumed by a call to the resume URL."),
                trace.getValue());
        verify(backgroundRuns).resumeRun(WORKFLOW_ID, run.getId());
    }

    @Test
    void aRunScopedUrlResumesWhicheverWebhookWaitTheRunIsParkedAt() {
        WorkflowRun run = parkedRun("a1", "upstream");
        WorkflowWait wait = waitRow(run, "WEBHOOK", true);
        when(waits.findFirstByRunIdAndStatus(run.getId(), WorkflowWait.Status.WAITING))
                .thenReturn(Optional.of(wait));
        when(runs.findById(run.getId())).thenReturn(Optional.of(run));
        claimSucceeds(wait);
        checkpointUpdates(run);

        WorkflowWaitResumeService.ResumeOutcome outcome =
                service.resumeByWebhook(
                        run.getId(), WorkflowWaitResumeTokens.ANY_NODE, Map.of("ok", true));

        assertEquals(WorkflowWaitResumeService.ResumeOutcome.RESUMED, outcome);
        verify(backgroundRuns).resumeRun(WORKFLOW_ID, run.getId());
    }

    @Test
    void theResumeUrlIsRefusedWhenThereIsNothingToResumeOnThatNode() {
        WorkflowRun run = parkedRun("a1", "in");
        UUID runId = run.getId();
        // nothing waiting
        when(waits.findFirstByRunIdAndStatus(runId, WorkflowWait.Status.WAITING))
                .thenReturn(Optional.empty());
        assertEquals(
                WorkflowWaitResumeService.ResumeOutcome.NOT_WAITING,
                service.resumeByWebhook(runId, "w1", Map.of()));

        // waiting, but on a different node than the token names
        WorkflowWait wait = waitRow(run, "WEBHOOK", true);
        when(waits.findFirstByRunIdAndStatus(runId, WorkflowWait.Status.WAITING))
                .thenReturn(Optional.of(wait));
        assertEquals(
                WorkflowWaitResumeService.ResumeOutcome.NOT_WAITING,
                service.resumeByWebhook(runId, "other", Map.of()));

        // a timed wait cannot be resumed through the URL
        WorkflowWait timed = waitRow(run, "DURATION", true);
        when(waits.findFirstByRunIdAndStatus(runId, WorkflowWait.Status.WAITING))
                .thenReturn(Optional.of(timed));
        assertEquals(
                WorkflowWaitResumeService.ResumeOutcome.NOT_WAITING,
                service.resumeByWebhook(runId, "w1", Map.of()));

        verify(backgroundRuns, never()).resumeRun(any(), any());
    }

    @Test
    void aSecondCallToTheSameResumeUrlIsNotWaitingAnymore() {
        WorkflowRun run = parkedRun("a1", "in");
        WorkflowWait wait = waitRow(run, "WEBHOOK", true);
        when(waits.findFirstByRunIdAndStatus(run.getId(), WorkflowWait.Status.WAITING))
                .thenReturn(Optional.of(wait));
        when(runs.findById(run.getId())).thenReturn(Optional.of(run));
        when(waits.claim(
                        eq(wait.getId()),
                        eq(WorkflowWait.Status.WAITING),
                        eq(WorkflowWait.Status.RESUMED),
                        any(Instant.class)))
                .thenReturn(0); // the first call already claimed it

        assertEquals(
                WorkflowWaitResumeService.ResumeOutcome.NOT_WAITING,
                service.resumeByWebhook(run.getId(), "w1", Map.of()));
        verify(backgroundRuns, never()).resumeRun(any(), any());
    }
}
