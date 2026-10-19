package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.jpa.repository.Query;

/**
 * The TTL sweep for {@link RunDataStore} rows. The delete itself is a JPQL statement that needs a database to
 * run, so the parked-run exclusion is pinned from three sides without one: which statuses count as parked
 * (and that every status is classified), that the job hands exactly that set and a correct cutoff to the
 * repository, and that the statement's text and the entity fields it names still line up.
 */
class RunDataCleanupJobTest {

    private static final Set<WorkflowRunStatus> PARKED = EnumSet.of(
        WorkflowRunStatus.AWAITING_APPROVAL, WorkflowRunStatus.AWAITING_INPUT,
        WorkflowRunStatus.AWAITING_VOICE_INPUT, WorkflowRunStatus.PAUSED);
    /** Statuses whose run will never read another node output, so their rows are safe to expire. */
    private static final Set<WorkflowRunStatus> SETTLED = EnumSet.of(
        WorkflowRunStatus.RUNNING, WorkflowRunStatus.COMPLETED, WorkflowRunStatus.FAILED, WorkflowRunStatus.CANCELLED);

    private TenantSchemaIterator schemaIterator;
    private WorkflowRunNodeOutputRepository repository;
    private ObjectProvider<PgAdvisoryJobLock> lockProvider;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        schemaIterator = mock(TenantSchemaIterator.class);
        repository = mock(WorkflowRunNodeOutputRepository.class);
        lockProvider = mock(ObjectProvider.class);
        when(lockProvider.getIfAvailable()).thenReturn(null);
    }

    private RunDataCleanupJob job(long ttlDays) {
        return new RunDataCleanupJob(schemaIterator, new RunDataStore(repository), lockProvider, ttlDays);
    }

    private void tenants(String... schemas) {
        doAnswer(inv -> {
            Consumer<String> action = inv.getArgument(0);
            Arrays.stream(schemas).forEach(action);
            return null;
        }).when(schemaIterator).forEachActiveTenantSchema(any());
    }

    // ---- which runs are protected

    @Test
    void parkedStatusesAreExactlyTheRunsThatResumeLater() {
        assertEquals(PARKED, RunDataStore.PARKED_STATUSES);
    }

    @Test
    void everyRunStatusIsClassifiedAsParkedOrSettledSoANewOneCannotSlipThrough() {
        Set<WorkflowRunStatus> classified = EnumSet.copyOf(PARKED);
        classified.addAll(SETTLED);
        assertEquals(EnumSet.allOf(WorkflowRunStatus.class), classified,
            "a new WorkflowRunStatus must be added to RunDataStore.PARKED_STATUSES if the run resumes later, "
                + "and to this test's SETTLED set if it does not");
    }

    // ---- the job

    @Test
    void sweepDeletesRowsOlderThanTheTtlAndPassesTheParkedSet() {
        tenants("tenant_a");
        Instant before = Instant.now();

        job(14).sweepAllTenants();

        Instant after = Instant.now();
        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<WorkflowRunStatus>> parked = ArgumentCaptor.forClass(Collection.class);
        verify(repository).deleteOlderThan(cutoff.capture(), parked.capture());
        assertTrue(!cutoff.getValue().isBefore(before.minus(Duration.ofDays(14)))
                && !cutoff.getValue().isAfter(after.minus(Duration.ofDays(14))),
            "cutoff is now minus the 14 day TTL: " + cutoff.getValue());
        assertEquals(PARKED, Set.copyOf(parked.getValue()));
    }

    @Test
    void ttlIsClampedToAtLeastOneDay() {
        tenants("tenant_a");
        Instant before = Instant.now();

        job(0).sweepAllTenants();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repository).deleteOlderThan(cutoff.capture(), any());
        assertTrue(cutoff.getValue().isAfter(before.minus(Duration.ofDays(1)).minusSeconds(1)),
            "a zero or negative ttl must never turn into 'delete everything'");
    }

    @Test
    void everyActiveTenantSchemaIsSweptAndOneFailureDoesNotStopTheRest() {
        tenants("tenant_a", "tenant_b", "tenant_c");
        when(repository.deleteOlderThan(any(), any()))
            .thenReturn(1)
            .thenThrow(new IllegalStateException("db blip"))
            .thenReturn(2);

        job(14).sweepAllTenants();

        verify(repository, times(3)).deleteOlderThan(any(), any());
    }

    @Test
    void withAJobLockTheSweepRunsInsideItUnderTheDocumentedKey() {
        PgAdvisoryJobLock lock = mock(PgAdvisoryJobLock.class);
        when(lockProvider.getIfAvailable()).thenReturn(lock);
        tenants("tenant_a");
        when(lock.tryRunExclusive(anyString(), any())).thenReturn(false);

        job(14).purgeExpired();

        ArgumentCaptor<Runnable> body = ArgumentCaptor.forClass(Runnable.class);
        verify(lock).tryRunExclusive(eq("workflow-run-data-cleanup"), body.capture());
        verify(repository, never()).deleteOlderThan(any(), any());
        body.getValue().run();
        verify(repository).deleteOlderThan(any(), any());
    }

    @Test
    void withoutALockProviderTheSweepStillRuns() {
        tenants("tenant_a");
        job(14).purgeExpired();
        verify(repository).deleteOlderThan(any(), any());
    }

    // ---- the statement itself

    @Test
    void theDeleteStatementExcludesParkedRunsAndNamesRealFields() throws Exception {
        Method m = WorkflowRunNodeOutputRepository.class.getMethod("deleteOlderThan", Instant.class, Collection.class);
        String jpql = m.getAnnotation(Query.class).value();

        assertTrue(jpql.startsWith("delete from WorkflowRunNodeOutput o"), jpql);
        assertTrue(jpql.contains("o.createdAt < :cutoff"), jpql);
        assertTrue(jpql.contains("not exists"), jpql);
        assertTrue(jpql.contains("from WorkflowRun r where r.id = o.runId and r.status in :parked"), jpql);

        // Every property the statement dereferences exists on its entity (a rename would otherwise only fail at boot).
        assertField(WorkflowRunNodeOutput.class, "createdAt");
        assertField(WorkflowRunNodeOutput.class, "runId");
        assertField(WorkflowRun.class, "id");
        assertField(WorkflowRun.class, "status");
        assertEquals(WorkflowRunStatus.class, WorkflowRun.class.getDeclaredField("status").getType());
        assertEquals(List.of("cutoff", "parked"), Arrays.stream(m.getParameters())
            .map(p -> p.getAnnotation(org.springframework.data.repository.query.Param.class).value()).toList());
    }

    private static void assertField(Class<?> type, String name) throws NoSuchFieldException {
        type.getDeclaredField(name);
    }
}
