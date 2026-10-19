package com.agenticai.core.agents.job.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.job.AgentJobLaunch;
import com.agenticai.core.agents.job.AgentJobLauncher;
import com.agenticai.core.agents.job.AgentJobProperties;
import com.agenticai.core.agents.job.AgentJobRepository;
import com.agenticai.core.agents.job.schedule.AgentScheduleService.DueSchedule;
import com.agenticai.core.agents.job.schedule.dto.AgentScheduleRequest;
import com.agenticai.core.audit.NotificationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Pageable;

@SuppressWarnings("unchecked")
class AgentScheduleServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");

    private AgentScheduleRepository repository;
    private AgentScheduleRunRepository runRepository;
    private AgentRepository agentRepository;
    private AgentService agentService;
    private AgentJobLauncher launcher;
    private ScheduledWorkflowRunner runner;
    private com.agenticai.core.security.TenantLimits tenantLimits;
    private AgentScheduleService service;

    private final UUID scheduleId = UUID.randomUUID();
    private final UUID agentId = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(AgentScheduleRepository.class);
        runRepository = mock(AgentScheduleRunRepository.class);
        agentRepository = mock(AgentRepository.class);
        agentService = mock(AgentService.class);
        launcher = mock(AgentJobLauncher.class);
        runner = mock(ScheduledWorkflowRunner.class);
        ObjectProvider<ScheduledWorkflowRunner> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(runner);

        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn("Reporter");
        when(agent.getAutonomyTier()).thenReturn(AgentAutonomyTier.FULLY_AUTONOMOUS);
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        when(agentService.requireAccess(eq(agentId), any())).thenReturn(agent);

        when(runRepository.save(any(AgentScheduleRun.class))).thenAnswer(i -> i.getArgument(0));
        when(runRepository.findByScheduleIdOrderByStartedAtDesc(any(), any(Pageable.class))).thenReturn(List.of());
        when(launcher.findLiveForSchedule(scheduleId)).thenReturn(Optional.empty());
        when(launcher.enqueue(any())).thenReturn(UUID.randomUUID());
        when(repository.claimOccurrence(any(), any(), any(), any())).thenReturn(1);

        // The automation plan-gate: these tests act as a tenant that HAS the
        // entitlement (and the gate skips on absent TenantContext anyway).
        tenantLimits = mock(com.agenticai.core.security.TenantLimits.class);
        when(tenantLimits.automationAllowed(any())).thenReturn(true);

        service = new AgentScheduleService(
            repository, agentRepository, agentService, launcher, mock(AgentJobRepository.class),
            new AgentJobProperties(), mock(NotificationService.class), new SimpleMeterRegistry(),
            runRepository, provider, tenantLimits);
    }

    private DueSchedule due(String cron, Integer interval, AgentScheduleTarget target, UUID wf) {
        return due(cron, interval, target, wf, false);
    }

    private DueSchedule due(String cron, Integer interval, AgentScheduleTarget target, UUID wf, boolean sentinel) {
        return new DueSchedule(
            scheduleId, agentId, cron, interval, target, wf, "UTC", "Do the {{agentName}} thing at {{scheduledAt}}",
            NOW.minusSeconds(5), null, "user-1", sentinel);
    }

    // ------------------------------------------------------------ AGENT target

    @Test
    void agentTargetEnqueuesJobAndRecordsRun() {
        service.fireOrSkip(due("0 0 9 * * *", null, AgentScheduleTarget.AGENT, null), NOW);

        ArgumentCaptor<AgentJobLaunch> launch = ArgumentCaptor.forClass(AgentJobLaunch.class);
        verify(launcher).enqueue(launch.capture());
        assertEquals(agentId, launch.getValue().agentId());
        assertTrue(launch.getValue().goal().startsWith("Do the Reporter thing at 2026-09-25T09:59:55"));
        assertEquals("0 0 9 * * *", launch.getValue().originRef());
        verify(runner, never()).submit(any(), any(), any(), any(), any());

        ArgumentCaptor<AgentScheduleRun> run = ArgumentCaptor.forClass(AgentScheduleRun.class);
        verify(runRepository).save(run.capture());
        assertEquals(AgentScheduleRun.ENQUEUED, run.getValue().getStatus());
        assertEquals("AGENT", run.getValue().getTargetType());
        assertEquals(AgentScheduleRun.TRIGGER_SCHEDULE, run.getValue().getTriggerKind());
        assertTrue(run.getValue().getJobId() != null);
    }

    @Test
    void intervalScheduleClaimsNextSlotAsNowPlusInterval() {
        service.fireOrSkip(due(null, 300, AgentScheduleTarget.AGENT, null), NOW);

        // compare-and-set from the OBSERVED slot to now + interval
        verify(repository).claimOccurrence(scheduleId, NOW.plusSeconds(300), NOW, NOW.minusSeconds(5));
        ArgumentCaptor<AgentJobLaunch> launch = ArgumentCaptor.forClass(AgentJobLaunch.class);
        verify(launcher).enqueue(launch.capture());
        assertEquals("every 300s", launch.getValue().originRef());
    }

    @Test
    void lostClaimFiresNothingAndWritesNoRun() {
        when(repository.claimOccurrence(any(), any(), any(), any())).thenReturn(0);
        service.fireOrSkip(due("0 0 9 * * *", null, AgentScheduleTarget.AGENT, null), NOW);
        verify(launcher, never()).enqueue(any());
        verify(runner, never()).submit(any(), any(), any(), any(), any());
        verify(runRepository, never()).save(any());
    }

    // ----------------------------------------------------------- WORKFLOW target

    @Test
    void workflowTargetSubmitsToRunnerAndNeverEnqueuesAgentJob() {
        when(runner.exists(workflowId)).thenReturn(true);
        when(runRepository.findFirstByScheduleIdAndStatusAndStartedAtAfterOrderByStartedAtDesc(
            any(), any(), any())).thenReturn(Optional.empty());

        service.fireOrSkip(due(null, 600, AgentScheduleTarget.WORKFLOW, workflowId), NOW);

        verify(launcher, never()).enqueue(any());
        ArgumentCaptor<BiConsumer<String, String>> cb = ArgumentCaptor.forClass(BiConsumer.class);
        ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
        verify(runner).submit(eq(workflowId), any(UUID.class), input.capture(), eq("user-1"), cb.capture());
        assertTrue(input.getValue().contains("Reporter"));

        ArgumentCaptor<AgentScheduleRun> run = ArgumentCaptor.forClass(AgentScheduleRun.class);
        verify(runRepository).save(run.capture());
        AgentScheduleRun row = run.getValue();
        assertEquals(AgentScheduleRun.RUNNING, row.getStatus());
        assertEquals("WORKFLOW", row.getTargetType());
        assertTrue(row.getWorkflowRunId() != null);

        // completion callback finishes the same row
        when(runRepository.findById(row.getId())).thenReturn(Optional.of(row));
        cb.getValue().accept("FAILED", "boom");
        assertEquals(AgentScheduleRun.FAILED, row.getStatus());
        assertEquals("boom", row.getError());
    }

    @Test
    void workflowTargetSkipsWhenWorkflowGone() {
        when(runner.exists(workflowId)).thenReturn(false);
        service.fireOrSkip(due(null, 600, AgentScheduleTarget.WORKFLOW, workflowId), NOW);
        verify(runner, never()).submit(any(), any(), any(), any(), any());
        verify(launcher, never()).enqueue(any());
        verify(runRepository, never()).save(any());
    }

    @Test
    void workflowTargetSkipsWhilePreviousRunStillRunning() {
        when(runner.exists(workflowId)).thenReturn(true);
        AgentScheduleRun running = AgentScheduleRun.create(
            UUID.randomUUID(), scheduleId, AgentScheduleTarget.WORKFLOW, AgentScheduleRun.TRIGGER_SCHEDULE,
            AgentScheduleRun.RUNNING, NOW.minusSeconds(60), null, UUID.randomUUID(), null);
        when(runRepository.findFirstByScheduleIdAndStatusAndStartedAtAfterOrderByStartedAtDesc(
            eq(scheduleId), eq(AgentScheduleRun.RUNNING), any())).thenReturn(Optional.of(running));

        service.fireOrSkip(due(null, 600, AgentScheduleTarget.WORKFLOW, workflowId), NOW);

        verify(runner, never()).submit(any(), any(), any(), any(), any());
        verify(runRepository, never()).save(any());
    }

    @Test
    void workflowTargetRunnerRejectionMarksRunFailed() {
        when(runner.exists(workflowId)).thenReturn(true);
        when(runRepository.findFirstByScheduleIdAndStatusAndStartedAtAfterOrderByStartedAtDesc(
            any(), any(), any())).thenReturn(Optional.empty());
        doThrow(new RejectedExecutionException("full"))
            .when(runner).submit(any(), any(), any(), any(), any());

        service.fireOrSkip(due(null, 600, AgentScheduleTarget.WORKFLOW, workflowId), NOW);

        ArgumentCaptor<AgentScheduleRun> saved = ArgumentCaptor.forClass(AgentScheduleRun.class);
        verify(runRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        AgentScheduleRun last = saved.getAllValues().get(saved.getAllValues().size() - 1);
        assertEquals(AgentScheduleRun.FAILED, last.getStatus());
    }

    // -------------------------------------------------------------- validation

    @Test
    void upsertRejectsBothCronAndInterval() {
        assertThrows(IllegalArgumentException.class, () -> service.upsert(
            agentId, new AgentScheduleRequest("0 9 * * *", "UTC", "go", 300, null, null)));
        verify(repository, never()).save(any());
    }

    @Test
    void upsertRejectsNeitherCronNorInterval() {
        assertThrows(IllegalArgumentException.class, () -> service.upsert(
            agentId, new AgentScheduleRequest(null, "UTC", "go", null, null, null)));
    }

    @Test
    void upsertRejectsIntervalBelowMinimum() {
        assertThrows(IllegalArgumentException.class, () -> service.upsert(
            agentId, new AgentScheduleRequest(null, "UTC", "go", 30, null, null)));
    }

    @Test
    void upsertRejectsWorkflowTargetWithoutWorkflowIdAndAgentTargetWithOne() {
        assertThrows(IllegalArgumentException.class, () -> service.upsert(
            agentId, new AgentScheduleRequest(null, "UTC", "go", 300, "WORKFLOW", null)));
        assertThrows(IllegalArgumentException.class, () -> service.upsert(
            agentId, new AgentScheduleRequest(null, "UTC", "go", 300, "AGENT", workflowId)));
        assertThrows(IllegalArgumentException.class, () -> service.upsert(
            agentId, new AgentScheduleRequest(null, "UTC", "go", 300, "BOGUS", null)));
    }

    @Test
    void parseTargetDefaultsToAgent() {
        assertEquals(AgentScheduleTarget.AGENT, AgentScheduleService.parseTarget(null));
        assertEquals(AgentScheduleTarget.WORKFLOW, AgentScheduleService.parseTarget("workflow"));
    }

    @Test
    void listRunsCapsLimit() {
        AgentSchedule schedule = mock(AgentSchedule.class);
        when(schedule.getId()).thenReturn(scheduleId);
        when(repository.findByAgentId(agentId)).thenReturn(Optional.of(schedule));
        service.listRuns(agentId, 9999);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(runRepository).findByScheduleIdOrderByStartedAtDesc(eq(scheduleId), page.capture());
        assertEquals(AgentScheduleService.RUN_HISTORY_LIMIT, page.getValue().getPageSize());
    }
}
