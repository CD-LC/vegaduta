package com.agenticai.core.agents.scheduledtask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.job.AgentJob;
import com.agenticai.core.agents.job.AgentJobLaunch;
import com.agenticai.core.agents.job.AgentJobLauncher;
import com.agenticai.core.agents.job.AgentJobOrigin;
import com.agenticai.core.agents.job.AgentJobRepository;
import com.agenticai.core.agents.job.AgentJobStatus;
import com.agenticai.core.agents.job.AgentJobView;
import com.agenticai.core.agents.scheduledtask.ScheduledTaskSchedule.Kind;
import com.agenticai.core.agents.scheduledtask.ScheduledTaskSchedule.Spec;
import com.agenticai.core.audit.NotificationSeverity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Firing and settling of scheduled tasks against mocked persistence: exactly-once claim, the missed-run policy, the
 * overlap guard, fail-closed behaviour for an unattended run, the single retry, the failure streak and auto-pause.
 */
class ScheduledTaskRunnerTest {

    /** Tuesday 2026-10-06, half a minute past the 08:00 UTC slot. */
    private static final Instant NOW = Instant.parse("2026-10-06T08:00:30Z");
    private static final Instant SLOT = Instant.parse("2026-10-06T08:00:00Z");
    private static final Instant NEXT_SLOT = Instant.parse("2026-10-07T08:00:00Z");

    private ScheduledTaskRepository tasks;
    private ScheduledTaskRunRepository runs;
    private AgentRepository agentRepository;
    private AgentJobLauncher launcher;
    private AgentJobRepository jobRepository;
    private ScheduledTaskResultDelivery delivery;
    private Agent agent;
    private ScheduledTaskRunner runner;

    private final Map<UUID, ScheduledTask> taskStore = new HashMap<>();
    private final Map<UUID, ScheduledTaskRun> runStore = new HashMap<>();
    private final UUID agentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        tasks = mock(ScheduledTaskRepository.class);
        runs = mock(ScheduledTaskRunRepository.class);
        agentRepository = mock(AgentRepository.class);
        launcher = mock(AgentJobLauncher.class);
        jobRepository = mock(AgentJobRepository.class);
        delivery = mock(ScheduledTaskResultDelivery.class);
        agent = mock(Agent.class);
        when(agent.getAutonomyTier()).thenReturn(AgentAutonomyTier.SEMI_AUTONOMOUS);
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));

        when(tasks.findById(any())).thenAnswer(i -> Optional.ofNullable(taskStore.get(i.<UUID>getArgument(0))));
        when(tasks.save(any(ScheduledTask.class))).thenAnswer(i -> {
            ScheduledTask t = i.getArgument(0);
            taskStore.put(t.getId(), t);
            return t;
        });
        when(tasks.claimOccurrence(any(), any(), any(), any())).thenReturn(1);
        when(runs.findById(any())).thenAnswer(i -> Optional.ofNullable(runStore.get(i.<UUID>getArgument(0))));
        when(runs.save(any(ScheduledTaskRun.class))).thenAnswer(i -> {
            ScheduledTaskRun r = i.getArgument(0);
            runStore.put(r.getId(), r);
            return r;
        });
        when(runs.claimForSettlement(any())).thenReturn(1);
        when(launcher.enqueue(any())).thenAnswer(i -> UUID.randomUUID());
        when(delivery.deliver(any(), any())).thenReturn(ScheduledTaskRun.DELIVERY_IN_APP);

        runner = new ScheduledTaskRunner(
            tasks, runs, agentRepository, launcher, jobRepository, settings(true), delivery, new SimpleMeterRegistry());
    }

    private static ScheduledTaskSettings settings(boolean retry) {
        return new ScheduledTaskSettings(true, true, 10, 3, 15, 3, 2000, 720, retry, 3500, "in_app,email,whatsapp");
    }

    private ScheduledTask task(Instant nextRunAt, Integer maxRuns, Instant expiresAt) {
        ScheduledTaskSchedule.Compiled schedule = ScheduledTaskSchedule.compile(
            new Spec(Kind.DAILY, "08:00", null, null), "UTC", 15, SLOT);
        ScheduledTask t = ScheduledTask.create(
            UUID.randomUUID(), agentId, "Digest", "Summarise my unread email", schedule,
            ScheduledTaskDelivery.IN_APP, null, maxRuns, expiresAt, "user-1", nextRunAt, SLOT.minus(Duration.ofDays(1)));
        taskStore.put(t.getId(), t);
        return t;
    }

    private AgentJobLaunch enqueuedLaunch() {
        ArgumentCaptor<AgentJobLaunch> captor = ArgumentCaptor.forClass(AgentJobLaunch.class);
        verify(launcher).enqueue(captor.capture());
        return captor.getValue();
    }

    private List<ScheduledTaskRun> savedRuns(int expected) {
        ArgumentCaptor<ScheduledTaskRun> captor = ArgumentCaptor.forClass(ScheduledTaskRun.class);
        verify(runs, times(expected)).save(captor.capture());
        return captor.getAllValues();
    }

    // ------------------------------------------------------------------- firing

    @Test
    void aDueTaskFiresOnceAsItsOwnerWithNoRolesThroughTheNormalJobLauncher() {
        ScheduledTask t = task(SLOT, null, null);

        runner.fireOrSkip(t, NOW);

        AgentJobLaunch launch = enqueuedLaunch();
        assertThat(launch.agentId()).isEqualTo(agentId);
        assertThat(launch.origin()).isEqualTo(AgentJobOrigin.SCHEDULE);
        assertThat(launch.scheduleId()).isEqualTo(t.getId());
        assertThat(launch.occurrenceAt()).isEqualTo(SLOT);
        // The job carries the OWNER's identity (and so the owner's budget) and no roles at all.
        assertThat(launch.requestedBy()).isEqualTo("user-1");
        assertThat(launch.subjectKey()).isEqualTo("user-1");
        assertThat(launch.requestedByRoles()).isEmpty();
        assertThat(launch.goal()).startsWith("Summarise my unread email").contains("unattended run");
        // The slot is claimed with a compare-and-set against what was observed, advancing to the next slot.
        verify(tasks).claimOccurrence(t.getId(), NEXT_SLOT, NOW, SLOT);
        ScheduledTaskRun run = savedRuns(1).get(0);
        assertThat(run.getStatus()).isEqualTo(ScheduledTaskRun.STATUS_ENQUEUED);
        assertThat(run.isSettled()).isFalse();
        assertThat(run.getTriggerKind()).isEqualTo(ScheduledTaskRun.TRIGGER_SCHEDULE);
        assertThat(taskStore.get(t.getId()).getRunCount()).isEqualTo(1);
        assertThat(taskStore.get(t.getId()).getLastStatus()).isEqualTo(ScheduledTask.STATUS_RUNNING);
    }

    @Test
    void aLostClaimRaceFiresNothingAndRecordsNothing() {
        ScheduledTask t = task(SLOT, null, null);
        when(tasks.claimOccurrence(any(), any(), any(), any())).thenReturn(0);

        runner.fireOrSkip(t, NOW);

        verifyNoInteractions(launcher);
        verify(runs, never()).save(any());
        assertThat(taskStore.get(t.getId()).getConsecutiveFailures()).isZero();
        assertThat(taskStore.get(t.getId()).getLastStatus()).isNull();
    }

    @Test
    void sixHoursOfDowntimeFiresTheMissedRunExactlyOnceAndAdvancesFromNow() {
        ScheduledTask t = task(SLOT.minus(Duration.ofHours(6)), null, null);
        when(tasks.findTop200ByEnabledTrueAndNextRunAtLessThanEqualOrderByNextRunAtAsc(NOW)).thenReturn(List.of(t));

        runner.fireDueTasks(NOW);

        verify(launcher, times(1)).enqueue(any());
        // ONE claim, to the next slot after NOW - not a slot per missed day/hour.
        verify(tasks, times(1)).claimOccurrence(t.getId(), NEXT_SLOT, NOW, SLOT.minus(Duration.ofHours(6)));
        assertThat(enqueuedLaunch().occurrenceAt()).isEqualTo(SLOT.minus(Duration.ofHours(6)));
    }

    @Test
    void aRunMissedByMoreThanTheCatchUpWindowIsRecordedAsMissedAndNotReplayed() {
        Instant stale = SLOT.minus(Duration.ofDays(2));
        ScheduledTask t = task(stale, null, null);

        runner.fireOrSkip(t, NOW);

        verify(launcher, never()).enqueue(any());
        verify(tasks).claimOccurrence(t.getId(), NEXT_SLOT, NOW, stale);
        ScheduledTaskRun run = savedRuns(1).get(0);
        assertThat(run.getStatus()).isEqualTo(ScheduledTaskRun.SKIPPED);
        assertThat(run.getError()).startsWith("Missed");
        assertThat(taskStore.get(t.getId()).getLastStatus()).isEqualTo(ScheduledTask.STATUS_SKIPPED);
        assertThat(taskStore.get(t.getId()).getConsecutiveFailures()).isZero();
    }

    @Test
    void aPreviousRunStillLiveSkipsTheFiringWithoutCountingAFailure() {
        ScheduledTask t = task(SLOT, null, null);
        when(launcher.findLiveForSchedule(t.getId())).thenReturn(
            Optional.of(new AgentJobView(UUID.randomUUID(), AgentJobStatus.AWAITING_APPROVAL, SLOT, SLOT)));

        runner.fireOrSkip(t, NOW);

        verify(launcher, never()).enqueue(any());
        ScheduledTaskRun run = savedRuns(1).get(0);
        assertThat(run.getStatus()).isEqualTo(ScheduledTaskRun.SKIPPED);
        assertThat(run.getError()).contains("AWAITING_APPROVAL");
        assertThat(taskStore.get(t.getId()).getConsecutiveFailures()).isZero();
    }

    @Test
    void aSupervisedAgentFailsClosedInsteadOfParkingForever() {
        when(agent.getAutonomyTier()).thenReturn(AgentAutonomyTier.SUPERVISED);
        ScheduledTask t = task(SLOT, null, null);

        runner.fireOrSkip(t, NOW);

        verify(launcher, never()).enqueue(any());
        ScheduledTaskRun run = savedRuns(1).get(0);
        assertThat(run.getStatus()).isEqualTo(ScheduledTaskRun.FAILED);
        assertThat(run.getError()).contains("SUPERVISED");
        assertThat(taskStore.get(t.getId()).getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    void aFullyAutonomousAgentFailsClosedBecauseItWouldSkipEveryApprovalWithNobodyPresent() {
        when(agent.getAutonomyTier()).thenReturn(AgentAutonomyTier.FULLY_AUTONOMOUS);
        ScheduledTask t = task(SLOT, null, null);

        runner.fireOrSkip(t, NOW);

        verify(launcher, never()).enqueue(any());
        ScheduledTaskRun run = savedRuns(1).get(0);
        assertThat(run.getStatus()).isEqualTo(ScheduledTaskRun.FAILED);
        assertThat(run.getError()).contains("FULLY_AUTONOMOUS");
        assertThat(taskStore.get(t.getId()).getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    void aTaskWhoseOwnerLostAccessToTheAgentIsStoppedNotRunWithTheirIdentity() {
        ScheduledTaskOwnerAccess ownerAccess = mock(ScheduledTaskOwnerAccess.class);
        when(ownerAccess.problem(any(), any())).thenReturn("The person who created this task no longer has access.");
        runner.setOwnerAccess(ownerAccess);
        ScheduledTask t = task(SLOT, null, null);

        runner.fireOrSkip(t, NOW);

        verify(launcher, never()).enqueue(any());
        ScheduledTaskRun run = savedRuns(1).get(0);
        assertThat(run.getStatus()).isEqualTo(ScheduledTaskRun.FAILED);
        assertThat(run.getError()).contains("no longer has access");
        assertThat(taskStore.get(t.getId()).isEnabled()).isFalse();
        assertThat(taskStore.get(t.getId()).getDisabledReason()).startsWith("Paused:");
    }

    @Test
    void aRetryThatIsSkippedStillCountsTheOriginalFailure() {
        ScheduledTask t = task(NEXT_SLOT, null, null);
        UUID jobId = UUID.randomUUID();
        ScheduledTaskRun run = unsettledRun(t, ScheduledTaskRun.TRIGGER_SCHEDULE, 1, jobId);
        job(jobId, AgentJobStatus.FAILED, "EXECUTION_FAILED", "model timeout", null);
        when(launcher.findLiveForSchedule(t.getId())).thenReturn(
            Optional.of(new AgentJobView(UUID.randomUUID(), AgentJobStatus.RUNNING, SLOT, SLOT)));

        runner.settleOne(run, NOW);

        verify(launcher, never()).enqueue(any());
        assertThat(taskStore.get(t.getId()).getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    void aMissingAgentIsAFailureNotAnException() {
        ScheduledTask t = task(SLOT, null, null);
        when(agentRepository.findById(agentId)).thenReturn(Optional.empty());

        runner.fireOrSkip(t, NOW);

        verify(launcher, never()).enqueue(any());
        assertThat(savedRuns(1).get(0).getError()).contains("no longer exists");
        assertThat(taskStore.get(t.getId()).getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    void theUniqueIndexRejectingADuplicateOccurrenceIsASkipNotAFailure() {
        ScheduledTask t = task(SLOT, null, null);
        when(launcher.enqueue(any())).thenThrow(new DataIntegrityViolationException("uq_agent_job_schedule_occurrence"));

        runner.fireOrSkip(t, NOW);

        ScheduledTaskRun run = savedRuns(1).get(0);
        assertThat(run.getStatus()).isEqualTo(ScheduledTaskRun.SKIPPED);
        assertThat(taskStore.get(t.getId()).getConsecutiveFailures()).isZero();
    }

    @Test
    void anEnqueueFailureSuchAsAnExhaustedBudgetCountsAsAFailedRun() {
        ScheduledTask t = task(SLOT, null, null);
        when(launcher.enqueue(any())).thenThrow(new IllegalStateException("monthly budget exhausted"));

        runner.fireOrSkip(t, NOW);

        ScheduledTaskRun run = savedRuns(1).get(0);
        assertThat(run.getStatus()).isEqualTo(ScheduledTaskRun.FAILED);
        assertThat(run.getError()).contains("monthly budget exhausted");
        assertThat(taskStore.get(t.getId()).getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    void anExpiredTaskIsPausedInsteadOfFiredAndTheOwnerIsTold() {
        ScheduledTask t = task(SLOT, null, SLOT.minusSeconds(1));

        runner.fireOrSkip(t, NOW);

        verifyNoInteractions(launcher);
        verify(tasks, never()).claimOccurrence(any(), any(), any(), any());
        assertThat(taskStore.get(t.getId()).isEnabled()).isFalse();
        assertThat(taskStore.get(t.getId()).getDisabledReason()).contains("end date");
        verify(delivery).notifyOwner(any(), contains("finished"), anyString(), eq(NotificationSeverity.INFO));
    }

    @Test
    void aTaskThatUsedItsRunCapIsPausedInsteadOfFired() {
        // The tick can hold a copy that still looks due even though the cap was just reached.
        ScheduledTask t = task(SLOT, 1, null);
        t.recordStarted(UUID.randomUUID(), SLOT.minus(Duration.ofDays(1)), true);

        runner.fireOrSkip(t, NOW);

        verifyNoInteractions(launcher);
        verify(tasks, never()).claimOccurrence(any(), any(), any(), any());
        assertThat(taskStore.get(t.getId()).isEnabled()).isFalse();
    }

    @Test
    void theLastAllowedRunPausesTheTaskImmediatelySoItCannotFireAgain() {
        ScheduledTask t = task(SLOT, 1, null);

        runner.fireOrSkip(t, NOW);

        verify(launcher, times(1)).enqueue(any());
        ScheduledTask after = taskStore.get(t.getId());
        assertThat(after.isEnabled()).isFalse();
        assertThat(after.getDisabledReason()).contains("limit of 1 runs");
        verify(delivery).notifyOwner(any(), contains("finished"), anyString(), eq(NotificationSeverity.INFO));
    }

    @Test
    void aStoredScheduleThatCannotBeReadPausesTheTaskInsteadOfLoopingForever() {
        ScheduledTask real = task(SLOT, null, null);
        ScheduledTask broken = mock(ScheduledTask.class);
        when(broken.getId()).thenReturn(real.getId());
        when(broken.getCron()).thenReturn("this is not a cron");
        when(broken.getTimezone()).thenReturn("UTC");
        when(broken.getNextRunAt()).thenReturn(SLOT);

        runner.fireOrSkip(broken, NOW);

        verifyNoInteractions(launcher);
        assertThat(taskStore.get(real.getId()).isEnabled()).isFalse();
        assertThat(taskStore.get(real.getId()).getDisabledReason()).contains("could not be read");
    }

    @Test
    void oneBadTaskInATickDoesNotStopTheOthers() {
        ScheduledTask bad = task(SLOT, null, null);
        ScheduledTask good = task(SLOT, null, null);
        when(tasks.findTop200ByEnabledTrueAndNextRunAtLessThanEqualOrderByNextRunAtAsc(NOW)).thenReturn(List.of(bad, good));
        when(tasks.claimOccurrence(eq(bad.getId()), any(), any(), any())).thenThrow(new IllegalStateException("db blip"));

        runner.fireDueTasks(NOW);

        ArgumentCaptor<AgentJobLaunch> captor = ArgumentCaptor.forClass(AgentJobLaunch.class);
        verify(launcher).enqueue(captor.capture());
        assertThat(captor.getValue().scheduleId()).isEqualTo(good.getId());
    }

    @Test
    void theGoalNamesTheTaskAndTellsTheAgentNobodyCanAnswer() {
        ScheduledTask t = task(SLOT, null, null);
        String goal = ScheduledTaskRunner.renderGoal(t, SLOT);
        assertThat(goal).contains("Summarise my unread email", "\"Digest\"", "2026-10-06T08:00:00Z", "nobody can answer questions");
        assertThat(goal).doesNotContain("will be sent as");
    }

    // ------------------------------------------------------------------ settling

    private ScheduledTaskRun unsettledRun(ScheduledTask t, String trigger, int attempt, UUID jobId) {
        ScheduledTaskRun run = ScheduledTaskRun.enqueued(UUID.randomUUID(), t.getId(), trigger, attempt, jobId, SLOT);
        runStore.put(run.getId(), run);
        return run;
    }

    private AgentJob job(UUID id, AgentJobStatus status, String errorCode, String errorMessage, String result) {
        AgentJob job = mock(AgentJob.class);
        when(job.getId()).thenReturn(id);
        when(job.getStatus()).thenReturn(status);
        when(job.getErrorCode()).thenReturn(errorCode);
        when(job.getErrorMessage()).thenReturn(errorMessage);
        when(job.getResultText()).thenReturn(result);
        when(job.getCostUsd()).thenReturn(new BigDecimal("0.123400"));
        when(jobRepository.findById(id)).thenReturn(Optional.of(job));
        return job;
    }

    @Test
    void aSucceededRunIsDeliveredRecordedAndResetsTheStreak() {
        ScheduledTask t = task(NEXT_SLOT, null, null);
        t.recordFinalFailure(3, SLOT);
        UUID jobId = UUID.randomUUID();
        ScheduledTaskRun run = unsettledRun(t, ScheduledTaskRun.TRIGGER_SCHEDULE, 1, jobId);
        job(jobId, AgentJobStatus.SUCCEEDED, null, null, "Top 5 emails: ...");

        runner.settleOne(run, NOW);

        verify(delivery).deliver(t, "Top 5 emails: ...");
        assertThat(run.getStatus()).isEqualTo(ScheduledTaskRun.SUCCEEDED);
        assertThat(run.isSettled()).isTrue();
        assertThat(run.getDeliveryStatus()).isEqualTo(ScheduledTaskRun.DELIVERY_IN_APP);
        assertThat(run.getCostUsd()).isEqualByComparingTo("0.1234");
        assertThat(taskStore.get(t.getId()).getLastStatus()).isEqualTo(ScheduledTask.STATUS_SUCCEEDED);
        assertThat(taskStore.get(t.getId()).getConsecutiveFailures()).isZero();
    }

    @Test
    void aSucceededJobWhoseResultIsTheNoProviderNoticeIsRecordedAsFailedAndCountsTowardAutoPause() {
        // B8: used to be SUCCEEDED and delivered as the task's result, so the streak never moved.
        ScheduledTask t = task(NEXT_SLOT, null, null);
        for (int i = 0; i < 3; i++) {
            UUID jobId = UUID.randomUUID();
            ScheduledTaskRun run = unsettledRun(t, ScheduledTaskRun.TRIGGER_SCHEDULE, 1, jobId);
            job(jobId, AgentJobStatus.SUCCEEDED, null, null,
                "No platform LLM is configured for this workspace right now. Please contact support, "
                    + "or switch this agent to your own provider (BYOK) in Providers settings.");
            runner.settleOne(run, NOW.plusSeconds(i));
            assertThat(run.getStatus()).isEqualTo(ScheduledTaskRun.FAILED);
            assertThat(run.getError()).contains("no usable LLM provider");
        }
        verify(delivery, never()).deliver(any(), any());
        verify(launcher, never()).enqueue(any()); // not retried: the same gap would repeat
        ScheduledTask after = taskStore.get(t.getId());
        assertThat(after.getConsecutiveFailures()).isEqualTo(3);
        assertThat(after.isEnabled()).isFalse();
    }

    @Test
    void theNoProviderMarkerMatchesTheRealChatServiceNotice() {
        assertThat(ScheduledTaskRunner.isNoProviderReply(
            com.agenticai.core.agents.turn.AgentChatService.NO_PLATFORM_LLM_NOTICE_PREFIX + " for this workspace")).isTrue();
        assertThat(ScheduledTaskRunner.isNoProviderReply("Top 5 emails: ...")).isFalse();
        assertThat(ScheduledTaskRunner.isNoProviderReply(null)).isFalse();
    }

    @Test
    void aRunWhoseJobIsStillGoingOrParkedOnAnApprovalIsLeftAlone() {
        ScheduledTask t = task(NEXT_SLOT, null, null);
        for (AgentJobStatus live : List.of(AgentJobStatus.QUEUED, AgentJobStatus.RUNNING, AgentJobStatus.AWAITING_APPROVAL)) {
            UUID jobId = UUID.randomUUID();
            ScheduledTaskRun run = unsettledRun(t, ScheduledTaskRun.TRIGGER_SCHEDULE, 1, jobId);
            job(jobId, live, null, null, null);
            runner.settleOne(run, NOW);
            assertThat(run.isSettled()).isFalse();
        }
        verify(runs, never()).claimForSettlement(any());
        verifyNoInteractions(delivery);
    }

    @Test
    void anotherReplicaSettlingTheRunFirstMeansNothingIsDeliveredTwice() {
        ScheduledTask t = task(NEXT_SLOT, null, null);
        UUID jobId = UUID.randomUUID();
        ScheduledTaskRun run = unsettledRun(t, ScheduledTaskRun.TRIGGER_SCHEDULE, 1, jobId);
        job(jobId, AgentJobStatus.SUCCEEDED, null, null, "result");
        when(runs.claimForSettlement(run.getId())).thenReturn(0);

        runner.settleOne(run, NOW);

        verify(delivery, never()).deliver(any(), any());
        assertThat(taskStore.get(t.getId()).getLastStatus()).isNull();
    }

    @Test
    void aFailedFirstAttemptIsRetriedExactlyOnceAndDoesNotYetCountAsAFailure() {
        ScheduledTask t = task(NEXT_SLOT, null, null);
        UUID jobId = UUID.randomUUID();
        ScheduledTaskRun run = unsettledRun(t, ScheduledTaskRun.TRIGGER_SCHEDULE, 1, jobId);
        job(jobId, AgentJobStatus.FAILED, "EXECUTION_FAILED", "model timeout", null);

        runner.settleOne(run, NOW);

        assertThat(run.getStatus()).isEqualTo(ScheduledTaskRun.FAILED);
        AgentJobLaunch retry = enqueuedLaunch();
        assertThat(retry.scheduleId()).isEqualTo(t.getId());
        assertThat(retry.occurrenceAt()).isEqualTo(NOW);
        assertThat(retry.requestedByRoles()).isEmpty();
        ScheduledTaskRun retryRun = runStore.values().stream()
            .filter(r -> ScheduledTaskRun.TRIGGER_RETRY.equals(r.getTriggerKind())).findFirst().orElseThrow();
        assertThat(retryRun.getAttempt()).isEqualTo(2);
        assertThat(taskStore.get(t.getId()).getConsecutiveFailures()).isZero();
        // A retry must not spend the run cap: no counted run was added.
        assertThat(taskStore.get(t.getId()).getRunCount()).isZero();
    }

    @Test
    void theRetryFailingToo_isTheFinalFailureAndCountsOnceWithoutAThirdAttempt() {
        ScheduledTask t = task(NEXT_SLOT, null, null);
        UUID jobId = UUID.randomUUID();
        ScheduledTaskRun retryRun = unsettledRun(t, ScheduledTaskRun.TRIGGER_RETRY, 2, jobId);
        job(jobId, AgentJobStatus.FAILED, "EXECUTION_FAILED", "model timeout", null);

        runner.settleOne(retryRun, NOW);

        verify(launcher, never()).enqueue(any());
        assertThat(taskStore.get(t.getId()).getConsecutiveFailures()).isEqualTo(1);
        verify(delivery).notifyOwner(any(), contains("failed"), anyString(), eq(NotificationSeverity.WARNING));
    }

    @Test
    void threeFinalFailuresInARowPauseTheTaskAndNotifyTheOwner() {
        ScheduledTask t = task(NEXT_SLOT, null, null);
        for (int i = 0; i < 3; i++) {
            UUID jobId = UUID.randomUUID();
            ScheduledTaskRun retryRun = unsettledRun(t, ScheduledTaskRun.TRIGGER_RETRY, 2, jobId);
            job(jobId, AgentJobStatus.FAILED, "EXECUTION_FAILED", "boom " + i, null);
            runner.settleOne(retryRun, NOW.plusSeconds(i));
        }
        ScheduledTask after = taskStore.get(t.getId());
        assertThat(after.getConsecutiveFailures()).isEqualTo(3);
        assertThat(after.isEnabled()).isFalse();
        assertThat(after.getNextRunAt()).isNull();
        verify(delivery).notifyOwner(any(), contains("paused"), contains("3 times in a row"), eq(NotificationSeverity.ERROR));
    }

    @Test
    void aSuccessBetweenFailuresKeepsTheTaskRunning() {
        ScheduledTask t = task(NEXT_SLOT, null, null);
        t.recordFinalFailure(3, SLOT);
        t.recordFinalFailure(3, SLOT);
        UUID okJob = UUID.randomUUID();
        ScheduledTaskRun ok = unsettledRun(t, ScheduledTaskRun.TRIGGER_SCHEDULE, 1, okJob);
        job(okJob, AgentJobStatus.SUCCEEDED, null, null, "fine");
        runner.settleOne(ok, NOW);
        UUID badJob = UUID.randomUUID();
        ScheduledTaskRun bad = unsettledRun(t, ScheduledTaskRun.TRIGGER_RETRY, 2, badJob);
        job(badJob, AgentJobStatus.FAILED, "X", "y", null);
        runner.settleOne(bad, NOW);

        assertThat(taskStore.get(t.getId()).isEnabled()).isTrue();
        assertThat(taskStore.get(t.getId()).getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    void anApprovalTimeoutIsNeverRetriedBecauseItWouldJustParkOnTheSameUnansweredApproval() {
        ScheduledTask t = task(NEXT_SLOT, null, null);
        UUID jobId = UUID.randomUUID();
        ScheduledTaskRun run = unsettledRun(t, ScheduledTaskRun.TRIGGER_SCHEDULE, 1, jobId);
        job(jobId, AgentJobStatus.FAILED, "APPROVAL_TIMEOUT", "nobody approved the send", null);

        runner.settleOne(run, NOW);

        verify(launcher, never()).enqueue(any());
        assertThat(taskStore.get(t.getId()).getConsecutiveFailures()).isEqualTo(1);
        assertThat(run.getError()).contains("APPROVAL_TIMEOUT");
    }

    @Test
    void aManualRunFailingIsShownButNeverRetriedOrCountedTowardThePause() {
        ScheduledTask t = task(NEXT_SLOT, null, null);
        UUID jobId = UUID.randomUUID();
        ScheduledTaskRun run = unsettledRun(t, ScheduledTaskRun.TRIGGER_MANUAL, 1, jobId);
        job(jobId, AgentJobStatus.FAILED, "EXECUTION_FAILED", "boom", null);

        runner.settleOne(run, NOW);

        verify(launcher, never()).enqueue(any());
        assertThat(taskStore.get(t.getId()).getConsecutiveFailures()).isZero();
        assertThat(taskStore.get(t.getId()).getLastStatus()).isEqualTo(ScheduledTask.STATUS_FAILED);
    }

    @Test
    void aCancelledRunIsRecordedAsCancelledNotAsAFailure() {
        ScheduledTask t = task(NEXT_SLOT, null, null);
        UUID jobId = UUID.randomUUID();
        ScheduledTaskRun run = unsettledRun(t, ScheduledTaskRun.TRIGGER_SCHEDULE, 1, jobId);
        job(jobId, AgentJobStatus.CANCELLED, null, null, null);

        runner.settleOne(run, NOW);

        assertThat(run.getStatus()).isEqualTo(ScheduledTaskRun.CANCELLED);
        verify(launcher, never()).enqueue(any());
        assertThat(taskStore.get(t.getId()).getConsecutiveFailures()).isZero();
        assertThat(taskStore.get(t.getId()).getLastStatus()).isEqualTo(ScheduledTask.STATUS_CANCELLED);
    }

    @Test
    void aVanishedJobRecordIsHandledAsAFailureNotACrash() {
        ScheduledTask t = task(NEXT_SLOT, null, null);
        UUID jobId = UUID.randomUUID();
        ScheduledTaskRun run = unsettledRun(t, ScheduledTaskRun.TRIGGER_RETRY, 2, jobId);
        when(jobRepository.findById(jobId)).thenReturn(Optional.empty());

        runner.settleOne(run, NOW);

        assertThat(run.getStatus()).isEqualTo(ScheduledTaskRun.FAILED);
        assertThat(run.getError()).contains("could not be found");
        assertThat(taskStore.get(t.getId()).getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    void withRetriesSwitchedOffAFailedScheduledRunIsFinalImmediately() {
        runner = new ScheduledTaskRunner(
            tasks, runs, agentRepository, launcher, jobRepository, settings(false), delivery, new SimpleMeterRegistry());
        ScheduledTask t = task(NEXT_SLOT, null, null);
        UUID jobId = UUID.randomUUID();
        ScheduledTaskRun run = unsettledRun(t, ScheduledTaskRun.TRIGGER_SCHEDULE, 1, jobId);
        job(jobId, AgentJobStatus.FAILED, "EXECUTION_FAILED", "boom", null);

        runner.settleOne(run, NOW);

        verify(launcher, never()).enqueue(any());
        assertThat(taskStore.get(t.getId()).getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    void theSweepSettlesWhatItCanAndSurvivesABadRow() {
        ScheduledTask t = task(NEXT_SLOT, null, null);
        UUID goodJob = UUID.randomUUID();
        ScheduledTaskRun good = unsettledRun(t, ScheduledTaskRun.TRIGGER_SCHEDULE, 1, goodJob);
        job(goodJob, AgentJobStatus.SUCCEEDED, null, null, "ok");
        UUID badJob = UUID.randomUUID();
        ScheduledTaskRun bad = unsettledRun(t, ScheduledTaskRun.TRIGGER_SCHEDULE, 1, badJob);
        when(jobRepository.findById(badJob)).thenThrow(new IllegalStateException("db blip"));
        when(runs.findTop100BySettledFalseOrderByStartedAtAsc()).thenReturn(List.of(bad, good));

        runner.settleFinishedRuns(NOW);

        assertThat(good.isSettled()).isTrue();
        assertThat(bad.isSettled()).isFalse();
    }

    @Test
    void theRetryRuleAsAPureMatrix() {
        String sched = ScheduledTaskRun.TRIGGER_SCHEDULE;
        assertThat(ScheduledTaskRunner.shouldRetry(true, sched, 1, true, "EXECUTION_FAILED")).isTrue();
        assertThat(ScheduledTaskRunner.shouldRetry(true, sched, 1, true, null)).isTrue();
        assertThat(ScheduledTaskRunner.shouldRetry(false, sched, 1, true, "EXECUTION_FAILED")).isFalse();
        assertThat(ScheduledTaskRunner.shouldRetry(true, sched, 2, true, "EXECUTION_FAILED")).isFalse();
        assertThat(ScheduledTaskRunner.shouldRetry(true, sched, 1, false, "EXECUTION_FAILED")).isFalse();
        assertThat(ScheduledTaskRunner.shouldRetry(true, ScheduledTaskRun.TRIGGER_MANUAL, 1, true, "X")).isFalse();
        assertThat(ScheduledTaskRunner.shouldRetry(true, ScheduledTaskRun.TRIGGER_RETRY, 1, true, "X")).isFalse();
        assertThat(ScheduledTaskRunner.shouldRetry(true, sched, 1, true, "APPROVAL_TIMEOUT")).isFalse();
        assertThat(ScheduledTaskRunner.shouldRetry(true, sched, 1, true, "APPROVAL_PARK_LIMIT")).isFalse();
    }
}
