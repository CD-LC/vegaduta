package com.agenticai.core.knowledge;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Claiming mode of the ingestion poller: no advisory lock, per-tenant cap, one claim at a time. */
class KnowledgeIngestionClaimingRunnerTest {

    private final TenantSchemaIterator iterator = mock(TenantSchemaIterator.class);
    private final PgAdvisoryJobLock lock = mock(PgAdvisoryJobLock.class);
    private final KnowledgeIngestionJobRepository repo =
            mock(KnowledgeIngestionJobRepository.class);
    private final KnowledgeIngestionJobService svc = mock(KnowledgeIngestionJobService.class);
    private final KnowledgeIngestionJobClaimer claimer = mock(KnowledgeIngestionJobClaimer.class);

    KnowledgeIngestionClaimingRunnerTest() {
        doAnswer(
                        inv -> {
                            Consumer<String> c = inv.getArgument(0);
                            c.accept("t1");
                            c.accept("t2");
                            return null;
                        })
                .when(iterator)
                .forEachActiveTenantSchema(any());
    }

    private static KnowledgeIngestionJobClaimer.Claim claim() {
        return new KnowledgeIngestionJobClaimer.Claim(
                UUID.randomUUID(), "t", false, 1, false, null);
    }

    private static KnowledgeIngestionJobClaimer.Claim poisoned() {
        return new KnowledgeIngestionJobClaimer.Claim(UUID.randomUUID(), "t", true, 3, true, null);
    }

    private KnowledgeIngestionJobRunner runner(int cap) {
        return new KnowledgeIngestionJobRunner(
                iterator, repo, svc, lock, cap, 0, System::nanoTime, claimer, 300, 3);
    }

    @Test
    void claimsUpToTheCapPerTenantWithoutTheAdvisoryLock() {
        when(claimer.claimNext(300, 3)).thenAnswer(inv -> Optional.of(claim()));

        runner(2).processQueuedJobs();

        verify(svc, times(4)).processClaimedJob(any()); // 2 tenants x cap 2
        verify(lock, never()).tryRunExclusive(any(), any());
        verify(svc, never()).processJob(any());
    }

    @Test
    void stopsAtTheFirstEmptyClaim() {
        when(claimer.claimNext(300, 3))
                .thenReturn(Optional.of(claim()), Optional.empty(), Optional.empty());

        runner(5).processQueuedJobs();

        verify(svc, times(1)).processClaimedJob(any());
    }

    @Test
    void aFailingJobDoesNotStopTheTenantQueue() {
        when(claimer.claimNext(300, 3)).thenAnswer(inv -> Optional.of(claim()));
        doAnswer(
                        inv -> {
                            throw new IllegalStateException("boom");
                        })
                .when(svc)
                .processClaimedJob(any());

        runner(2).processQueuedJobs();

        verify(svc, times(4)).processClaimedJob(any());
    }

    @Test
    void heartbeatsWhileAJobRunsAndStopsWhenItEnds() throws Exception {
        KnowledgeIngestionJobClaimer.Claim c = claim();
        when(claimer.claimNext(4, 3)).thenReturn(Optional.of(c), Optional.empty());
        when(claimer.heartbeat(c)).thenReturn(true);
        doAnswer(
                        inv -> {
                            Thread.sleep(2600); // lease 4 s => heartbeat every 1 s
                            return null;
                        })
                .when(svc)
                .processClaimedJob(c);
        KnowledgeIngestionJobRunner r =
                new KnowledgeIngestionJobRunner(
                        iterator, repo, svc, lock, 1, 0, System::nanoTime, claimer, 4, 3);

        r.processQueuedJobs();

        verify(claimer, atLeast(2)).heartbeat(c);
        int afterJob = mockingDetails(claimer).getInvocations().size();
        Thread.sleep(1500);
        org.junit.jupiter.api.Assertions.assertEquals(
                afterJob,
                mockingDetails(claimer).getInvocations().size(),
                "no heartbeat after the job ended (cancelled in finally)");
        verify(claimer, atLeastOnce()).heartbeat(c);
    }

    @Test
    void aPoisonedClaimIsRecordedNotRunAndDoesNotUseUpTheCap() {
        KnowledgeIngestionJobClaimer.Claim bad = poisoned();
        when(claimer.claimNext(300, 3))
                .thenReturn(Optional.of(bad), Optional.of(claim()), Optional.empty());

        runner(1).processQueuedJobs();

        verify(svc).processClaimedJob(bad); // recorded on its document, no heartbeat started
        verify(claimer, never()).heartbeat(bad);
        // cap 1 per tenant: the good job after the poisoned one still ran in tenant t1
        verify(svc, times(2)).processClaimedJob(any());
    }
}
