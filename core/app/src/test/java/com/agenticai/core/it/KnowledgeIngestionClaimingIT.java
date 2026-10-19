package com.agenticai.core.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.knowledge.KnowledgeDocumentService;
import com.agenticai.core.knowledge.KnowledgeIngestionJob;
import com.agenticai.core.knowledge.KnowledgeIngestionJobClaimer;
import com.agenticai.core.knowledge.KnowledgeIngestionJobClaimer.Claim;
import com.agenticai.core.knowledge.KnowledgeIngestionJobRepository;
import com.agenticai.core.knowledge.KnowledgeIngestionJobRunner;
import com.agenticai.core.knowledge.KnowledgeIngestionJobService;
import com.agenticai.core.knowledge.KnowledgeIngestionService;
import com.agenticai.core.knowledge.SidecarClient;
import com.agenticai.core.knowledge.dto.IngestResponse;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.storage.FileStorageService;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import com.agenticai.core.tenancy.TenantSchemaMigrator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javax.sql.DataSource;

/**
 * Per-job claiming of {@code knowledge_ingestion_job} on real Postgres. Two layers:
 *
 * <ul>
 *   <li>the claimer's statements: N concurrent runners over M jobs process every job exactly once,
 *       a dead claim is reclaimed after its lease (lease expiry is simulated by back-dating {@code
 *       updated_at}, so no test sleeps race a lease), a heartbeat keeps a live claim, the attempt
 *       cap fails a poison job, a stale attempt cannot touch the row, claims never cross tenant
 *       schemas;
 *   <li>the real runner + service + claimer together (only the sidecar, the embedding/ingest call
 *       and the document service are stubs): completion, the reclaim purge of partial chunks,
 *       poison handling, a real heartbeat thread, and a replica without claiming not re-running a
 *       claimed job.
 * </ul>
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class KnowledgeIngestionClaimingIT {

    private static final String TENANT = "tenant_it_claim";
    private static final String OTHER = "tenant_it_claim_other";
    private static final UUID COLLECTION_ID = UUID.randomUUID();
    private static final int MAX_ATTEMPTS = 3;

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static KnowledgeIngestionJobClaimer claimer;

    @BeforeAll
    static void migrate() {
        dataSource = IntegrationTestContainers.freshDatabase("claim_it");
        jdbc = new JdbcTemplate(dataSource);
        PlatformMigrations.migrateBoot(dataSource);
        TenantSchemaMigrator migrator = new TenantSchemaMigrator(dataSource);
        migrator.migrate(TENANT);
        migrator.migrate(OTHER);
        for (String schema : List.of(TENANT, OTHER)) {
            jdbc.update(
                    "INSERT INTO \""
                            + schema
                            + "\".knowledge_collection (id, name, embedding_model) VALUES (?, ?,"
                            + " ?)",
                    COLLECTION_ID,
                    "it-collection",
                    "minilm-l6-v2");
        }
        claimer = new KnowledgeIngestionJobClaimer(jdbc);
    }

    @BeforeEach
    void clean() {
        for (String schema : List.of(TENANT, OTHER)) {
            jdbc.update("DELETE FROM \"" + schema + "\".knowledge_ingestion_job");
            jdbc.update("DELETE FROM \"" + schema + "\".knowledge_parent_chunk");
            jdbc.update("DELETE FROM \"" + schema + "\".knowledge_chunks");
            jdbc.update("DELETE FROM \"" + schema + "\".knowledge_document");
        }
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private static void asTenant(String schema) {
        TenantContext.set(TenantContext.of(schema, "integration-test", Set.of()));
    }

    private static List<UUID> enqueue(String schema, int n) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add(enqueueJob(schema, UUID.randomUUID(), null, i));
        }
        return ids;
    }

    private static UUID enqueueJob(String schema, UUID id, UUID documentId, int ageOrder) {
        jdbc.update(
                "INSERT INTO \""
                        + schema
                        + "\".knowledge_ingestion_job (id, filename, collection_id, chunk_size,"
                        + " chunk_overlap, status, audio_content, document_id, created_at,"
                        + " updated_at) VALUES (?, ?, ?, 500, 50, 'QUEUED', ?, ?, now() + (? *"
                        + " interval '1 millisecond'), now())",
                id,
                "f" + ageOrder + ".mp3",
                COLLECTION_ID,
                new byte[] {1, 2, 3},
                documentId,
                ageOrder);
        return id;
    }

    private static String status(String schema, UUID id) {
        return jdbc.queryForObject(
                "SELECT status FROM \"" + schema + "\".knowledge_ingestion_job WHERE id = ?",
                String.class,
                id);
    }

    private static Map<String, Object> row(String schema, UUID id) {
        return jdbc.queryForMap(
                "SELECT status, result_chunk_count, error_message, audio_content IS NULL AS"
                        + " no_audio, updated_at FROM \""
                        + schema
                        + "\".knowledge_ingestion_job WHERE id = ?",
                id);
    }

    /** Simulates the lease running out: the runner stopped heartbeating {@code seconds} ago. */
    private static void ageHeartbeat(String schema, UUID id, int seconds) {
        jdbc.update(
                "UPDATE \""
                        + schema
                        + "\".knowledge_ingestion_job SET updated_at = now() - (? * interval '1"
                        + " second') WHERE id = ?",
                seconds,
                id);
    }

    // ---------------------------------------------------------------- the claimer's statements

    @Test
    void heartbeatFailsFastInsteadOfHangingOnALockedRow() throws Exception {
        UUID id = enqueue(TENANT, 1).get(0);
        asTenant(TENANT);
        Claim claim = claimer.claimNext(300, MAX_ATTEMPTS).orElseThrow();
        assertThat(claim.jobId()).isEqualTo(id);
        KnowledgeIngestionJobClaimer quick = new KnowledgeIngestionJobClaimer(jdbc, 1);

        // Another transaction holds the row (a stuck writer): the heartbeat must give up.
        try (java.sql.Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (java.sql.PreparedStatement ps =
                    blocker.prepareStatement(
                            "SELECT 1 FROM " + claim.table() + " WHERE id = ? FOR UPDATE")) {
                ps.setObject(1, id);
                ps.executeQuery();
            }
            long start = System.nanoTime();
            // preemptive so a regression (an unbounded wait) fails instead of hanging the suite
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                    java.time.Duration.ofSeconds(30),
                    () ->
                            org.assertj.core.api.Assertions.assertThatThrownBy(
                                            () -> quick.heartbeat(claim))
                                    .isInstanceOf(
                                            org.springframework.dao.DataAccessException.class));
            assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start))
                    .as("seconds until the heartbeat gave up")
                    .isLessThan(10);
            blocker.rollback();
        }
        // once the lock is gone the same claim heartbeats normally
        assertThat(quick.heartbeat(claim)).isTrue();
    }

    @Test
    void concurrentRunnersProcessEveryJobExactlyOnce() throws Exception {
        int runners = 6;
        int jobs = 60;
        List<UUID> ids = enqueue(TENANT, jobs);
        Map<UUID, AtomicInteger> processed = new ConcurrentHashMap<>();
        Map<String, AtomicInteger> perRunner = new ConcurrentHashMap<>();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(runners);
        List<Future<?>> futures = new ArrayList<>();
        for (int r = 0; r < runners; r++) {
            String name = "runner-" + r;
            futures.add(
                    pool.submit(
                            () -> {
                                asTenant(TENANT);
                                go.await();
                                Optional<Claim> claim;
                                while ((claim = claimer.claimNext(300, MAX_ATTEMPTS)).isPresent()) {
                                    Claim c = claim.get();
                                    processed
                                            .computeIfAbsent(c.jobId(), k -> new AtomicInteger())
                                            .incrementAndGet();
                                    perRunner
                                            .computeIfAbsent(name, k -> new AtomicInteger())
                                            .incrementAndGet();
                                    Thread.sleep(5); // the "work", long enough for others to claim
                                    claimer.complete(c.jobId(), c.attempt(), 1);
                                }
                                return null;
                            }));
        }
        go.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertThat(processed.keySet()).containsExactlyInAnyOrderElementsOf(ids);
        assertThat(processed.values()).allSatisfy(c -> assertThat(c.get()).isEqualTo(1));
        assertThat(perRunner.size()).as("work was spread over several runners").isGreaterThan(1);
        assertThat(ids).allSatisfy(id -> assertThat(status(TENANT, id)).isEqualTo("COMPLETE"));
    }

    @Test
    void deadClaimIsReclaimedAfterLeaseButLiveClaimIsNot() {
        asTenant(TENANT);
        UUID id = enqueue(TENANT, 1).get(0);

        Claim first = claimer.claimNext(30, MAX_ATTEMPTS).orElseThrow();
        assertThat(first.jobId()).isEqualTo(id);
        assertThat(first.reclaimed()).isFalse();
        assertThat(first.attempt()).isEqualTo(1);
        assertThat(status(TENANT, id)).isEqualTo("PROCESSING");

        // Within the lease nobody else can take it.
        assertThat(claimer.claimNext(30, MAX_ATTEMPTS)).isEmpty();

        // A heartbeat extends the lease: after 40 s of silence the job would be claimable, but a
        // heartbeat 40 s in puts it back inside the lease.
        ageHeartbeat(TENANT, id, 40);
        assertThat(claimer.heartbeat(first)).isTrue();
        assertThat(claimer.claimNext(30, MAX_ATTEMPTS)).isEmpty();

        // The runner "crashes" (no more heartbeats); after the lease the job is handed over once.
        ageHeartbeat(TENANT, id, 40);
        Claim second = claimer.claimNext(30, MAX_ATTEMPTS).orElseThrow();
        assertThat(second.jobId()).isEqualTo(id);
        assertThat(second.reclaimed()).isTrue();
        assertThat(second.attempt()).isEqualTo(2);
        assertThat(second.poisoned()).isFalse();
        assertThat(claimer.claimNext(30, MAX_ATTEMPTS)).isEmpty();
    }

    @Test
    void aJobThatKeepsKillingItsRunnerIsFailedAfterTheAttemptCap() {
        asTenant(TENANT);
        UUID id = enqueue(TENANT, 1).get(0);

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Claim claim = claimer.claimNext(30, MAX_ATTEMPTS).orElseThrow();
            assertThat(claim.attempt()).isEqualTo(attempt);
            assertThat(claim.poisoned()).isFalse();
            ageHeartbeat(TENANT, id, 40); // the runner died without finishing
        }

        Claim poisoned = claimer.claimNext(30, MAX_ATTEMPTS).orElseThrow();
        assertThat(poisoned.jobId()).isEqualTo(id);
        assertThat(poisoned.poisoned()).isTrue();
        Map<String, Object> row = row(TENANT, id);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat((String) row.get("error_message")).contains("Gave up after 3 attempts");
        assertThat(row.get("no_audio")).isEqualTo(true);
        // Failed is terminal: it is never handed out again.
        assertThat(claimer.claimNext(30, MAX_ATTEMPTS)).isEmpty();
    }

    @Test
    void aSlowEarlierAttemptCannotTouchWhatALaterAttemptOwns() {
        asTenant(TENANT);
        UUID id = enqueue(TENANT, 1).get(0);
        Claim slow = claimer.claimNext(30, MAX_ATTEMPTS).orElseThrow();
        ageHeartbeat(TENANT, id, 40);
        Claim fast = claimer.claimNext(30, MAX_ATTEMPTS).orElseThrow();

        assertThat(claimer.heartbeat(slow)).as("stale attempt cannot heartbeat").isFalse();
        assertThat(claimer.complete(id, fast.attempt(), 7)).isTrue();

        // The late first attempt now tries to regress the terminal state.
        assertThat(claimer.fail(id, slow.attempt(), "late failure")).isFalse();
        assertThat(claimer.complete(id, slow.attempt(), 99)).isFalse();
        Map<String, Object> row = row(TENANT, id);
        assertThat(row.get("status")).isEqualTo("COMPLETE");
        assertThat(row.get("result_chunk_count")).isEqualTo(7);
        assertThat(row.get("error_message")).isNull();
    }

    @Test
    void theAdvisoryLockPathCannotTakeAJobSomeoneElseOwns() {
        asTenant(TENANT);
        UUID id = enqueue(TENANT, 1).get(0);
        Claim claim = claimer.claimNext(300, MAX_ATTEMPTS).orElseThrow();
        assertThat(claimer.startIfQueued(id)).as("already claimed").isFalse();
        claimer.complete(id, claim.attempt(), 1);
        assertThat(claimer.startIfQueued(id)).as("already complete").isFalse();

        UUID queued = enqueue(TENANT, 1).get(0);
        assertThat(claimer.startIfQueued(queued)).isTrue();
        assertThat(claimer.startIfQueued(queued)).as("only the first taker wins").isFalse();
        assertThat(claimer.claimNext(300, MAX_ATTEMPTS)).as("and it is not claimable").isEmpty();
    }

    @Test
    void completedAndFailedJobsAreNeverClaimed() {
        asTenant(TENANT);
        List<UUID> ids = enqueue(TENANT, 2);
        jdbc.update(
                "UPDATE \""
                        + TENANT
                        + "\".knowledge_ingestion_job SET status = 'COMPLETE' WHERE id"
                        + " = ?",
                ids.get(0));
        jdbc.update(
                "UPDATE \""
                        + TENANT
                        + "\".knowledge_ingestion_job SET status = 'FAILED',"
                        + " updated_at = now() - interval '1 day' WHERE id = ?",
                ids.get(1));
        assertThat(claimer.claimNext(1, MAX_ATTEMPTS)).isEmpty();
    }

    @Test
    void claimsStayInsideTheCurrentTenantSchema() {
        List<UUID> mine = enqueue(TENANT, 1);
        List<UUID> theirs = enqueue(OTHER, 1);

        asTenant(TENANT);
        assertThat(claimer.claimNext(300, MAX_ATTEMPTS).orElseThrow().jobId())
                .isEqualTo(mine.get(0));
        assertThat(claimer.claimNext(300, MAX_ATTEMPTS)).isEmpty();
        assertThat(status(OTHER, theirs.get(0))).isEqualTo("QUEUED");

        asTenant(OTHER);
        assertThat(claimer.claimNext(300, MAX_ATTEMPTS).orElseThrow().jobId())
                .isEqualTo(theirs.get(0));
    }

    // ------------------------------------------- runner + service + claimer on real Postgres

    private final SidecarClient sidecar = mock(SidecarClient.class);
    private final KnowledgeIngestionService ingestion = mock(KnowledgeIngestionService.class);
    private final KnowledgeDocumentService documents = mock(KnowledgeDocumentService.class);
    private final KnowledgeIngestionJobRepository repo =
            mock(KnowledgeIngestionJobRepository.class);
    private final PgAdvisoryJobLock lock = mock(PgAdvisoryJobLock.class);
    private final TenantSchemaIterator iterator = mock(TenantSchemaIterator.class);

    private KnowledgeIngestionJobRunner runner(boolean claiming, long leaseSeconds) {
        KnowledgeIngestionJobService service =
                new KnowledgeIngestionJobService(
                        repo,
                        sidecar,
                        ingestion,
                        documents,
                        mock(FileStorageService.class),
                        jdbc,
                        claimer);
        when(repo.findById(any()))
                .thenAnswer(inv -> Optional.of(entityFor(inv.getArgument(0, UUID.class))));
        when(sidecar.transcribeAudio(anyString(), any())).thenReturn("transcript");
        // Runs the per-tenant work for TENANT only, with the tenant context set like the real
        // iterator does.
        org.mockito.Mockito.doAnswer(
                        inv -> {
                            asTenant(TENANT);
                            try {
                                inv.getArgument(0, Consumer.class).accept(TENANT);
                            } finally {
                                TenantContext.clear();
                            }
                            return null;
                        })
                .when(iterator)
                .forEachActiveTenantSchema(any());
        return new KnowledgeIngestionJobRunner(
                iterator,
                repo,
                service,
                lock,
                10,
                0,
                claimer,
                claiming,
                leaseSeconds,
                MAX_ATTEMPTS);
    }

    private static KnowledgeIngestionJob entityFor(UUID jobId) {
        Map<String, Object> r =
                jdbc.queryForMap(
                        "SELECT filename, document_id FROM \""
                                + TENANT
                                + "\".knowledge_ingestion_job WHERE id = ?",
                        jobId);
        KnowledgeIngestionJob job =
                new KnowledgeIngestionJob(
                        (String) r.get("filename"),
                        COLLECTION_ID,
                        500,
                        50,
                        new byte[] {1, 2, 3},
                        (UUID) r.get("document_id"));
        ReflectionTestUtils.setField(job, "id", jobId);
        return job;
    }

    private static UUID document(String schema, UUID id) {
        jdbc.update(
                "INSERT INTO \""
                        + schema
                        + "\".knowledge_document (id, collection_id, filename, file_size_bytes,"
                        + " status) VALUES (?, ?, 'a.mp3', 3, 'QUEUED')",
                id,
                COLLECTION_ID);
        return id;
    }

    private static UUID chunk(String schema, UUID documentId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO \""
                        + schema
                        + "\".knowledge_chunks (id, source, chunk_index, content, embedding,"
                        + " embedding_model, collection_id, document_id) VALUES (?, 'a.mp3', 0,"
                        + " 'c', array_fill(0::real, ARRAY[768])::vector, 'minilm-l6-v2', ?, ?)",
                id,
                COLLECTION_ID,
                documentId);
        jdbc.update(
                "INSERT INTO \""
                        + schema
                        + "\".knowledge_parent_chunk (id, collection_id, document_id,"
                        + " parent_index, content) VALUES (?, ?, ?, 0, 'p')",
                UUID.randomUUID(),
                COLLECTION_ID,
                documentId);
        return id;
    }

    private static int chunks(String schema, UUID documentId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM \"" + schema + "\".knowledge_chunks WHERE document_id = ?",
                Integer.class,
                documentId);
    }

    private static int parents(String schema, UUID documentId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM \""
                        + schema
                        + "\".knowledge_parent_chunk WHERE document_id = ?",
                Integer.class,
                documentId);
    }

    @Test
    void runnerCompletesQueuedJobsEndToEnd() {
        List<UUID> ids = enqueue(TENANT, 3);
        KnowledgeIngestionJobRunner runner = runner(true, 300);
        when(ingestion.ingest(anyString(), anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(new IngestResponse(4, 768, List.of()));

        runner.processQueuedJobs();

        for (UUID id : ids) {
            Map<String, Object> row = row(TENANT, id);
            assertThat(row.get("status")).isEqualTo("COMPLETE");
            assertThat(row.get("result_chunk_count")).isEqualTo(4);
            assertThat(row.get("no_audio")).isEqualTo(true);
        }
    }

    @Test
    void reclaimPurgesOnlyTheDeadRunsPartialChunksOfThatDocumentInThatTenant() {
        UUID doc = document(TENANT, UUID.randomUUID());
        UUID otherDoc = document(TENANT, UUID.randomUUID());
        // Same document id in another tenant schema must be untouched.
        document(OTHER, doc);
        UUID job = enqueueJob(TENANT, UUID.randomUUID(), doc, 0);
        asTenant(TENANT);
        claimer.claimNext(30, MAX_ATTEMPTS).orElseThrow(); // the first runner claims, then dies
        TenantContext.clear();
        UUID partial = chunk(TENANT, doc);
        chunk(TENANT, otherDoc);
        chunk(OTHER, doc);
        ageHeartbeat(TENANT, job, 40);

        KnowledgeIngestionJobRunner runner = runner(true, 30);
        AtomicInteger chunksSeenByIngest = new AtomicInteger(-1);
        AtomicInteger parentsSeenByIngest = new AtomicInteger(-1);
        when(ingestion.ingest(anyString(), anyString(), anyInt(), anyInt(), any(), eq(doc)))
                .thenAnswer(
                        inv -> {
                            chunksSeenByIngest.set(chunks(TENANT, doc));
                            parentsSeenByIngest.set(parents(TENANT, doc));
                            chunk(TENANT, doc);
                            return new IngestResponse(1, 768, List.of());
                        });

        runner.processQueuedJobs();

        assertThat(chunksSeenByIngest.get()).as("partial output purged before the re-run").isZero();
        assertThat(chunks(TENANT, doc)).as("only the re-run's chunk remains").isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM \""
                                        + TENANT
                                        + "\".knowledge_chunks WHERE id = ?",
                                Integer.class,
                                partial))
                .isZero();
        assertThat(parentsSeenByIngest.get()).as("parent chunks purged before the re-run").isZero();
        assertThat(parents(TENANT, doc)).as("only the re-run's parent chunk remains").isEqualTo(1);
        assertThat(chunks(TENANT, otherDoc)).as("other document's chunks survive").isEqualTo(1);
        assertThat(parents(TENANT, otherDoc)).isEqualTo(1);
        assertThat(chunks(OTHER, doc)).as("same document id in another tenant").isEqualTo(1);
        assertThat(parents(OTHER, doc)).isEqualTo(1);
        assertThat(status(TENANT, job)).isEqualTo("COMPLETE");
    }

    @Test
    void poisonJobIsFailedWithoutBeingRunAgain() {
        UUID doc = document(TENANT, UUID.randomUUID());
        UUID job = enqueueJob(TENANT, UUID.randomUUID(), doc, 0);
        jdbc.update(
                "UPDATE \""
                        + TENANT
                        + "\".knowledge_ingestion_job SET status = 'PROCESSING',"
                        + " result_chunk_count = ?, updated_at = now() - interval '1 hour' WHERE"
                        + " id = ?",
                MAX_ATTEMPTS,
                job);

        runner(true, 30).processQueuedJobs();

        verify(sidecar, never()).transcribeAudio(anyString(), any());
        verify(ingestion, never())
                .ingest(anyString(), anyString(), anyInt(), anyInt(), any(), any());
        verify(documents).markFailed(eq(doc), anyString());
        Map<String, Object> row = row(TENANT, job);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("no_audio")).isEqualTo(true);
    }

    @Test
    void realHeartbeatThreadKeepsALongRunningClaimAlive() {
        UUID job = enqueue(TENANT, 1).get(0);
        KnowledgeIngestionJobRunner runner = runner(true, 4); // lease 4 s, heartbeat every 1 s
        AtomicReference<Timestamp> before = new AtomicReference<>();
        AtomicReference<Timestamp> during = new AtomicReference<>();
        AtomicReference<Boolean> claimableMidRun = new AtomicReference<>();
        when(ingestion.ingest(anyString(), anyString(), anyInt(), anyInt(), any(), any()))
                .thenAnswer(
                        inv -> {
                            before.set(updatedAt(job));
                            Thread.sleep(6000); // longer than the 4 s lease
                            during.set(updatedAt(job));
                            // Another replica polling mid-run finds nothing to claim.
                            claimableMidRun.set(claimer.claimNext(4, MAX_ATTEMPTS).isPresent());
                            return new IngestResponse(1, 768, List.of());
                        });

        runner.processQueuedJobs();

        assertThat(during.get()).as("heartbeat advanced updated_at").isAfter(before.get());
        assertThat(claimableMidRun.get()).as("a live claim is not reclaimed").isFalse();
        assertThat(status(TENANT, job)).isEqualTo("COMPLETE");
    }

    @Test
    void aReplicaWithoutClaimingDoesNotRerunAJobAClaimingReplicaOwns() {
        UUID job = enqueue(TENANT, 1).get(0);
        asTenant(TENANT);
        claimer.claimNext(300, MAX_ATTEMPTS).orElseThrow(); // flag-on replica owns it
        TenantContext.clear();
        KnowledgeIngestionJobRunner legacy = runner(false, 300);
        // The legacy poll lists ids by a stale QUEUED read and calls processJob on them.
        when(repo.findIdsByStatusOrderByCreatedAtAsc(any(), any())).thenReturn(List.of(job));
        org.mockito.Mockito.doAnswer(
                        inv -> {
                            inv.getArgument(1, Runnable.class).run();
                            return true;
                        })
                .when(lock)
                .tryRunExclusive(anyString(), any(Runnable.class));

        legacy.processQueuedJobs();

        verify(sidecar, never()).transcribeAudio(anyString(), any());
        assertThat(status(TENANT, job)).isEqualTo("PROCESSING");
    }

    private static Timestamp updatedAt(UUID job) {
        return jdbc.queryForObject(
                "SELECT updated_at FROM \"" + TENANT + "\".knowledge_ingestion_job WHERE id = ?",
                Timestamp.class,
                job);
    }
}
