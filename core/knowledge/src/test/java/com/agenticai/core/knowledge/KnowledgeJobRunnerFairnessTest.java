package com.agenticai.core.knowledge;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

/** Per-tenant cap and wall-clock budget on the ingestion and re-embed pollers. */
class KnowledgeJobRunnerFairnessTest {

    private static final long SEC = 1_000_000_000L;

    private final TenantSchemaIterator iterator = mock(TenantSchemaIterator.class);
    private final PgAdvisoryJobLock lock = mock(PgAdvisoryJobLock.class);

    KnowledgeJobRunnerFairnessTest() {
        // Run the exclusive body directly, over two tenants.
        doAnswer(
                        inv -> {
                            ((Runnable) inv.getArgument(1)).run();
                            return true;
                        })
                .when(lock)
                .tryRunExclusive(any(), any());
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

    /** Repo stub that honours the page size like the real bounded query. */
    private static void queued(KnowledgeIngestionJobRepository repo, int n) {
        when(repo.findIdsByStatusOrderByCreatedAtAsc(
                        eq(KnowledgeIngestionJobStatus.QUEUED), any(Pageable.class)))
                .thenAnswer(
                        inv -> {
                            Pageable p = inv.getArgument(1);
                            int limit = p.isPaged() ? Math.min(n, p.getPageSize()) : n;
                            List<UUID> ids = new ArrayList<>();
                            for (int i = 0; i < limit; i++) {
                                ids.add(UUID.randomUUID());
                            }
                            return ids;
                        });
    }

    private static List<KnowledgeReembedJob> reembedJobs(int n) {
        List<KnowledgeReembedJob> jobs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            KnowledgeReembedJob j = mock(KnowledgeReembedJob.class);
            jobs.add(j);
        }
        return jobs;
    }

    private KnowledgeIngestionJobRunner ingestion(
            KnowledgeIngestionJobService svc,
            KnowledgeIngestionJobRepository repo,
            int cap,
            long budget,
            AtomicLong clock) {
        return new KnowledgeIngestionJobRunner(iterator, repo, svc, lock, cap, budget, clock::get);
    }

    @Test
    void ingestionCapsJobsPerTenant() {
        KnowledgeIngestionJobRepository repo = mock(KnowledgeIngestionJobRepository.class);
        KnowledgeIngestionJobService svc = mock(KnowledgeIngestionJobService.class);
        queued(repo, 5);

        ingestion(svc, repo, 2, 0, new AtomicLong()).processQueuedJobs();

        verify(svc, times(4)).processJob(any()); // 2 tenants x cap 2
    }

    @Test
    void ingestionZeroCapMeansUnlimited() {
        KnowledgeIngestionJobRepository repo = mock(KnowledgeIngestionJobRepository.class);
        KnowledgeIngestionJobService svc = mock(KnowledgeIngestionJobService.class);
        queued(repo, 3);

        ingestion(svc, repo, 0, 0, new AtomicLong()).processQueuedJobs();

        verify(svc, times(6)).processJob(any());
    }

    @Test
    void ingestionStopsStartingJobsOnceBudgetSpent() {
        KnowledgeIngestionJobRepository repo = mock(KnowledgeIngestionJobRepository.class);
        KnowledgeIngestionJobService svc = mock(KnowledgeIngestionJobService.class);
        queued(repo, 5);
        AtomicLong clock = new AtomicLong();
        // Each job takes 10s, budget 15s: jobs start at 0s and 10s, the third is blocked at 20s.
        doAnswer(
                        inv -> {
                            clock.addAndGet(10 * SEC);
                            return null;
                        })
                .when(svc)
                .processJob(any());

        ingestion(svc, repo, 0, 15 * SEC, clock).processQueuedJobs();

        verify(svc, times(2)).processJob(any());
    }

    @Test
    void ingestionSkipsLaterTenantsOnceBudgetSpent() {
        KnowledgeIngestionJobRepository repo = mock(KnowledgeIngestionJobRepository.class);
        KnowledgeIngestionJobService svc = mock(KnowledgeIngestionJobService.class);
        queued(repo, 1);
        AtomicLong clock = new AtomicLong();
        doAnswer(
                        inv -> {
                            clock.addAndGet(100 * SEC);
                            return null;
                        })
                .when(svc)
                .processJob(any());

        ingestion(svc, repo, 2, 10 * SEC, clock).processQueuedJobs();

        verify(svc, times(1)).processJob(any());
        verify(repo, times(1))
                .findIdsByStatusOrderByCreatedAtAsc(
                        eq(KnowledgeIngestionJobStatus.QUEUED),
                        any(Pageable.class)); // t2 not even queried
    }

    @Test
    void reembedCapsJobsPerTenant() {
        KnowledgeReembedJobRepository repo = mock(KnowledgeReembedJobRepository.class);
        KnowledgeReembedService svc = mock(KnowledgeReembedService.class);
        when(repo.findByStatusInOrderByCreatedAtAsc(any())).thenReturn(reembedJobs(3));

        new KnowledgeReembedJobRunner(iterator, repo, svc, lock, 1, 0, new AtomicLong()::get)
                .processActiveJobs();

        verify(svc, times(2)).processJob(any()); // 2 tenants x cap 1
    }

    @Test
    void reembedHonoursBudget() {
        KnowledgeReembedJobRepository repo = mock(KnowledgeReembedJobRepository.class);
        KnowledgeReembedService svc = mock(KnowledgeReembedService.class);
        when(repo.findByStatusInOrderByCreatedAtAsc(any())).thenReturn(reembedJobs(3));
        AtomicLong clock = new AtomicLong();
        doAnswer(
                        inv -> {
                            clock.addAndGet(60 * SEC);
                            return null;
                        })
                .when(svc)
                .processJob(any());

        new KnowledgeReembedJobRunner(iterator, repo, svc, lock, 0, 30 * SEC, clock::get)
                .processActiveJobs();

        verify(svc, times(1)).processJob(any());
    }

    @Test
    void ingestionLoadsOnlyCapIdsNotTheWholeBacklog() {
        KnowledgeIngestionJobRepository repo = mock(KnowledgeIngestionJobRepository.class);
        KnowledgeIngestionJobService svc = mock(KnowledgeIngestionJobService.class);
        queued(repo, 50);

        ingestion(svc, repo, 2, 0, new AtomicLong()).processQueuedJobs();

        verify(repo, never()).findByStatus(any());
        verify(svc, times(4)).processJob(any());
    }

    /**
     * Three tenants, a budget that fits exactly one job per tick: each tick must serve the next
     * tenant.
     */
    @Test
    void ingestionRotatesTenantsAcrossTicksWhenBudgetIsSpent() {
        TenantSchemaIterator three = mock(TenantSchemaIterator.class);
        List<String> current = new ArrayList<>();
        doAnswer(
                        inv -> {
                            Consumer<String> c = inv.getArgument(0);
                            for (String t : List.of("t1", "t2", "t3")) {
                                current.clear();
                                current.add(t);
                                c.accept(t);
                            }
                            return null;
                        })
                .when(three)
                .forEachActiveTenantSchema(any());
        KnowledgeIngestionJobRepository repo = mock(KnowledgeIngestionJobRepository.class);
        KnowledgeIngestionJobService svc = mock(KnowledgeIngestionJobService.class);
        List<String> served = new ArrayList<>();
        when(repo.findIdsByStatusOrderByCreatedAtAsc(any(), any(Pageable.class)))
                .thenAnswer(
                        inv -> {
                            served.add(current.get(0));
                            return List.of(UUID.randomUUID());
                        });
        AtomicLong clock = new AtomicLong();
        doAnswer(
                        inv -> {
                            clock.addAndGet(100 * SEC); // every job alone exhausts the 10s budget
                            return null;
                        })
                .when(svc)
                .processJob(any());
        KnowledgeIngestionJobRunner runner =
                new KnowledgeIngestionJobRunner(three, repo, svc, lock, 2, 10 * SEC, clock::get);

        runner.processQueuedJobs();
        runner.processQueuedJobs();
        runner.processQueuedJobs();
        runner.processQueuedJobs();

        // Without rotation this would be t1,t1,t1,t1.
        org.junit.jupiter.api.Assertions.assertEquals(List.of("t1", "t2", "t3", "t1"), served);
    }

    @Test
    void reembedRotatesTenantsAcrossTicksWhenBudgetIsSpent() {
        TenantSchemaIterator three = mock(TenantSchemaIterator.class);
        List<String> current = new ArrayList<>();
        doAnswer(
                        inv -> {
                            Consumer<String> c = inv.getArgument(0);
                            for (String t : List.of("t1", "t2", "t3")) {
                                current.clear();
                                current.add(t);
                                c.accept(t);
                            }
                            return null;
                        })
                .when(three)
                .forEachActiveTenantSchema(any());
        KnowledgeReembedJobRepository repo = mock(KnowledgeReembedJobRepository.class);
        KnowledgeReembedService svc = mock(KnowledgeReembedService.class);
        List<String> served = new ArrayList<>();
        when(repo.findByStatusInOrderByCreatedAtAsc(any()))
                .thenAnswer(
                        inv -> {
                            served.add(current.get(0));
                            return reembedJobs(1);
                        });
        AtomicLong clock = new AtomicLong();
        doAnswer(
                        inv -> {
                            clock.addAndGet(100 * SEC);
                            return null;
                        })
                .when(svc)
                .processJob(any());
        KnowledgeReembedJobRunner runner =
                new KnowledgeReembedJobRunner(three, repo, svc, lock, 1, 10 * SEC, clock::get);

        runner.processActiveJobs();
        runner.processActiveJobs();
        runner.processActiveJobs();

        org.junit.jupiter.api.Assertions.assertEquals(List.of("t1", "t2", "t3"), served);
    }
}
