package com.agenticai.core.agents.job.durability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.job.AgentJob;
import com.agenticai.core.agents.job.AgentJobStatus;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The boot report: evidence that a restart stranded work, and proof that
 * producing that evidence changes nothing.
 *
 * <p>The last part is the point. The obvious version of a "boot sweep" - treat
 * every RUNNING row as an orphan and reclaim it - cannot distinguish a row
 * abandoned by a dead process from one a sibling replica is executing right now,
 * and would destroy the second kind. So this class only counts and logs, and the
 * heartbeat-based orphan sweep (which CAN tell them apart) does every
 * transition.
 */
class AgentJobBootReconciliationTest {

    private AgentJobRecoveryLookup lookup;
    private TenantSchemaIterator schemaIterator;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        lookup = mock(AgentJobRecoveryLookup.class);
        meterRegistry = new SimpleMeterRegistry();

        schemaIterator = mock(TenantSchemaIterator.class);
        doAnswer(invocation -> {
            Consumer<String> action = invocation.getArgument(0, Consumer.class);
            action.accept("tenant_acme");
            action.accept("tenant_globex");
            return null;
        }).when(schemaIterator).forEachActiveTenantSchema(any());
    }

    @Test
    void rowsLeftRunningByAPreviousProcessAreCountedAcrossEveryTenantSchema() {
        AgentJob stranded = DurabilityTestSupport.abandonedRunningJob();
        // acme has two stranded rows, globex none - the per-schema iteration has
        // to accumulate rather than report only the last schema it visited.
        when(lookup.countByStatus(AgentJobStatus.RUNNING)).thenReturn(2L, 0L);
        when(lookup.countByStatusAndOwnerInstance(eq(AgentJobStatus.RUNNING), anyString())).thenReturn(1L);
        when(lookup.findTop20ByStatusOrderByCreatedAtAsc(AgentJobStatus.RUNNING))
            .thenReturn(List.of(stranded));

        reconciliation().reportOrphansAtBoot();

        assertEquals(2.0, meterRegistry.counter("agent.job.recovery.orphans_at_boot").count());
    }

    @Test
    void aSchemaWithNothingRunningCostsNoFurtherQueries() {
        when(lookup.countByStatus(AgentJobStatus.RUNNING)).thenReturn(0L);

        reconciliation().reportOrphansAtBoot();

        // Startup is the worst possible time to issue queries nobody needs, and
        // the common case by far is that the previous process shut down cleanly.
        verify(lookup, never()).countByStatusAndOwnerInstance(any(AgentJobStatus.class), anyString());
        verify(lookup, never()).findTop20ByStatusOrderByCreatedAtAsc(any(AgentJobStatus.class));
        assertEquals(0.0, meterRegistry.counter("agent.job.recovery.orphans_at_boot").count());
    }

    @Test
    void theReportOnlyReadsAndNeverTransitionsAnything() {
        AgentJob stranded = DurabilityTestSupport.abandonedRunningJob();
        when(lookup.countByStatus(AgentJobStatus.RUNNING)).thenReturn(1L, 0L);
        when(lookup.countByStatusAndOwnerInstance(eq(AgentJobStatus.RUNNING), anyString())).thenReturn(0L);
        when(lookup.findTop20ByStatusOrderByCreatedAtAsc(AgentJobStatus.RUNNING))
            .thenReturn(List.of(stranded));

        reconciliation().reportOrphansAtBoot();

        // The strongest available statement of "reports, never reclaims": the
        // lookup extends the bare Repository marker, so it has no write method
        // to call, and the row is untouched afterwards. A blanket boot reclaim
        // here would kill a sibling replica's live job.
        assertEquals(AgentJobStatus.RUNNING, stranded.getStatus());
        assertEquals(DurabilityTestSupport.DEAD_REPLICA, stranded.getOwnerInstance());
    }

    @Test
    void aFailingReportNeverBreaksStartup() {
        // Core must boot even if this read cannot run, and the orphan sweep
        // (which does the actual recovery) does not depend on it at all.
        when(lookup.countByStatus(AgentJobStatus.RUNNING))
            .thenThrow(new IllegalStateException("relation \"agent_job\" does not exist"));
        doAnswer(invocation -> {
            invocation.getArgument(0, Consumer.class).accept("tenant_acme");
            return null;
        }).when(schemaIterator).forEachActiveTenantSchema(any());

        reconciliation().reportOrphansAtBoot();
    }

    private AgentJobBootReconciliation reconciliation() {
        return new AgentJobBootReconciliation(
            lookup, schemaIterator, DurabilityTestSupport.properties(), meterRegistry);
    }
}
