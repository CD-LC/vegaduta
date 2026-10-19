package com.agenticai.core.agents.job.schedule;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.job.schedule.AgentScheduleService.DueSchedule;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** The tick iterates tenants under the lock, dispatches every due schedule, and survives a bad row. */
class AgentScheduleTickerTest {

    private static DueSchedule due(AgentScheduleTarget target) {
        return new DueSchedule(
            UUID.randomUUID(), UUID.randomUUID(), null, 300, target,
            target == AgentScheduleTarget.WORKFLOW ? UUID.randomUUID() : null,
            "UTC", "go", Instant.now(), null, "user", false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void dispatchesEveryDueScheduleOfBothTargetsAndCatchesPerRow() {
        TenantSchemaIterator iterator = mock(TenantSchemaIterator.class);
        AgentScheduleService service = mock(AgentScheduleService.class);
        PgAdvisoryJobLock lock = mock(PgAdvisoryJobLock.class);

        doAnswer(i -> {
            ((Runnable) i.getArgument(1)).run();
            return true;
        }).when(lock).tryRunExclusive(anyString(), any(Runnable.class));
        // two tenants
        doAnswer(i -> {
            Consumer<String> action = i.getArgument(0);
            action.accept("tenant_a");
            action.accept("tenant_b");
            return null;
        }).when(iterator).forEachActiveTenantSchema(any());

        DueSchedule bad = due(AgentScheduleTarget.AGENT);
        DueSchedule agent = due(AgentScheduleTarget.AGENT);
        DueSchedule workflow = due(AgentScheduleTarget.WORKFLOW);
        when(service.findDue(any())).thenReturn(List.of(bad, workflow, agent));
        doThrow(new RuntimeException("boom")).when(service).fireOrSkip(org.mockito.ArgumentMatchers.eq(bad), any());

        new AgentScheduleTicker(iterator, service, lock).fireDueSchedules();

        // per-row catch: the schedules after the bad one still fire, in both tenants
        verify(service, times(2)).fireOrSkip(org.mockito.ArgumentMatchers.eq(bad), any());
        verify(service, times(2)).fireOrSkip(org.mockito.ArgumentMatchers.eq(workflow), any());
        verify(service, times(2)).fireOrSkip(org.mockito.ArgumentMatchers.eq(agent), any());
    }
}
