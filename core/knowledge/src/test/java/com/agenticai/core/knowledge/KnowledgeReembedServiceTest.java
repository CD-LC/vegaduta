package com.agenticai.core.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.knowledge.dto.KnowledgeReembedJobResponse;
import com.agenticai.core.knowledge.dto.SidecarChunk;
import com.agenticai.core.knowledge.dto.SidecarEmbedResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@link KnowledgeReembedService}'s start rules and its worker's phase
 * ordering (embed into shadow -> atomic flip -> sweep), with JdbcTemplate
 * stubbed by distinctive SQL fragments the same way KnowledgeSearchServiceTest
 * does. {@link KnowledgeCollection} is mocked (JPA-generated id, no setter).
 */
class KnowledgeReembedServiceTest {

    private static final String SOURCE = "minilm-l6-v2";
    private static final String TARGET = "multilingual-minilm";

    private KnowledgeReembedJobRepository jobRepository;
    private KnowledgeCollectionRepository collectionRepository;
    private SidecarClient sidecarClient;
    private JdbcTemplate jdbcTemplate;
    private PlatformTransactionManager txManager;
    private KnowledgeReembedService service;
    private UUID collectionId;

    @BeforeEach
    void setUp() {
        jobRepository = mock(KnowledgeReembedJobRepository.class);
        collectionRepository = mock(KnowledgeCollectionRepository.class);
        sidecarClient = mock(SidecarClient.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        txManager = mock(PlatformTransactionManager.class);
        service = new KnowledgeReembedService(jobRepository, collectionRepository, sidecarClient, jdbcTemplate, txManager, 2);
        collectionId = UUID.randomUUID();
        when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(jobRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private void stubCollection(String model) {
        KnowledgeCollection c = mock(KnowledgeCollection.class);
        when(c.getId()).thenReturn(collectionId);
        when(c.getEmbeddingModel()).thenReturn(model);
        when(collectionRepository.findById(collectionId)).thenReturn(Optional.of(c));
    }

    @SuppressWarnings("unchecked")
    private void stubCounts(long strays, long total) {
        when(jdbcTemplate.queryForObject(argThat((String sql) -> sql != null && sql.contains("embedding_model <> ?") && !sql.contains("NOT EXISTS")),
            eq(Long.class), any(Object[].class))).thenReturn(strays);
        when(jdbcTemplate.queryForObject(argThat((String sql) -> sql != null && sql.endsWith("WHERE collection_id = ?")),
            eq(Long.class), any(Object[].class))).thenReturn(total);
    }

    // --- start ------------------------------------------------------------------------------

    @Test
    void startRejectsAModelOutsideTheCatalog() {
        assertThrows(IllegalArgumentException.class, () -> service.start(collectionId, "text-embedding-3-small"));
        verify(jobRepository, never()).saveAndFlush(any());
    }

    @Test
    void startRejectsAMissingCollection() {
        when(collectionRepository.findById(collectionId)).thenReturn(Optional.empty());
        assertThrows(KnowledgeCollectionNotFoundException.class, () -> service.start(collectionId, TARGET));
    }

    @Test
    void startQueuesAJobAndMarksTheCollectionReembedding() {
        stubCollection(SOURCE);
        stubCounts(7, 7);

        KnowledgeReembedJobResponse response = service.start(collectionId, TARGET);

        assertEquals(KnowledgeReembedJobStatus.QUEUED, response.status());
        assertEquals(SOURCE, response.sourceModel());
        assertEquals(TARGET, response.targetModel());
        assertEquals(7, response.totalChunks());
        verify(jdbcTemplate).update(
            argThat((String sql) -> sql.contains("knowledge_collection") && sql.contains("SET embedding_status = ?")),
            eq(KnowledgeCollection.STATUS_REEMBEDDING), eq(collectionId)
        );
    }

    @Test
    void resubmittingTheSameTargetWhileActiveReturnsTheExistingJob() {
        stubCollection(SOURCE);
        KnowledgeReembedJob active = new KnowledgeReembedJob(collectionId, SOURCE, TARGET, 3);
        when(jobRepository.findFirstByCollectionIdAndStatusIn(eq(collectionId), any())).thenReturn(Optional.of(active));

        KnowledgeReembedJobResponse response = service.start(collectionId, TARGET);

        assertEquals(TARGET, response.targetModel());
        verify(jobRepository, never()).saveAndFlush(any());
    }

    @Test
    void aDifferentTargetWhileActiveIsAConflict() {
        stubCollection(SOURCE);
        KnowledgeReembedJob active = new KnowledgeReembedJob(collectionId, SOURCE, "bge-small-en", 3);
        when(jobRepository.findFirstByCollectionIdAndStatusIn(eq(collectionId), any())).thenReturn(Optional.of(active));

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service.start(collectionId, TARGET));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
    }

    @Test
    void reembeddingToTheCurrentModelWithNoStrayChunksIsAConflict() {
        stubCollection(TARGET);
        stubCounts(0, 5);

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service.start(collectionId, TARGET));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
    }

    @Test
    void reembeddingToTheCurrentModelIsAllowedWhenStrayChunksNeedRepair() {
        stubCollection(TARGET);
        stubCounts(2, 5);

        assertEquals(KnowledgeReembedJobStatus.QUEUED, service.start(collectionId, TARGET).status());
    }

    // --- processJob -------------------------------------------------------------------------

    private SidecarEmbedResponse embedded(int n) {
        List<SidecarChunk> chunks = java.util.stream.IntStream.range(0, n)
            .mapToObj(i -> new SidecarChunk(i, "t" + i, List.of(0.1, 0.2)))
            .toList();
        return new SidecarEmbedResponse(TARGET, 768, 200, 0, chunks);
    }

    @SuppressWarnings("unchecked")
    private void stubPendingBatches(List<?>... batches) {
        var stubbing = when(jdbcTemplate.query(argThat((String sql) -> sql != null && sql.contains("NOT EXISTS")),
            any(RowMapper.class), any(Object[].class)));
        for (List<?> batch : batches) {
            stubbing = stubbing.thenReturn((List<Object>) batch);
        }
    }

    @SuppressWarnings("unchecked")
    private void stubSweepBatches(List<?>... batches) {
        var stubbing = when(jdbcTemplate.query(argThat((String sql) -> sql != null && sql.startsWith("SELECT id, content FROM")),
            any(RowMapper.class), any(Object[].class)));
        for (List<?> batch : batches) {
            stubbing = stubbing.thenReturn((List<Object>) batch);
        }
    }

    private UUID queuedJob() {
        UUID jobId = UUID.randomUUID();
        KnowledgeReembedJob job = new KnowledgeReembedJob(collectionId, SOURCE, TARGET, 2);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        return jobId;
    }

    @Test
    @SuppressWarnings("unchecked")
    void processJobEmbedsIntoShadowThenFlipsAtomicallyThenSweepsAndCompletes() {
        UUID jobId = queuedJob();
        List<KnowledgeReembedService.ChunkText> batch = List.of(
            new KnowledgeReembedService.ChunkText(UUID.randomUUID(), "hello"),
            new KnowledgeReembedService.ChunkText(UUID.randomUUID(), "world")
        );
        stubPendingBatches(batch, List.of());
        stubSweepBatches(List.of());
        when(sidecarClient.embedPreChunked(anyList(), eq(TARGET))).thenReturn(embedded(2));
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(2L);

        service.processJob(jobId);

        // Shadow write, never a direct write to live chunks during EMBEDDING.
        verify(jdbcTemplate).batchUpdate(argThat((String sql) -> sql.startsWith("INSERT INTO") && sql.contains("knowledge_chunk_reembed_shadow")
            && sql.contains("ON CONFLICT (chunk_id, embedding_model)")), anyList());
        // The flip: one transaction copying shadow vectors, switching the model, and cleaning shadow rows.
        verify(txManager).getTransaction(any());
        verify(txManager).commit(any());
        verify(jdbcTemplate).update(argThat((String sql) -> sql.contains("knowledge_chunk_reembed_shadow s")
            && sql.contains("SET embedding = s.embedding")), eq(TARGET), eq(collectionId), eq(TARGET));
        verify(jdbcTemplate).update(argThat((String sql) -> sql.contains("SET embedding_model = ?, embedding_status = ?")),
            eq(TARGET), eq(KnowledgeCollection.STATUS_READY), eq(collectionId));
        verify(jdbcTemplate).update(argThat((String sql) -> sql.startsWith("DELETE FROM") && sql.contains("knowledge_chunk_reembed_shadow")),
            eq(collectionId));
        org.mockito.ArgumentCaptor<KnowledgeReembedJob> saved = org.mockito.ArgumentCaptor.forClass(KnowledgeReembedJob.class);
        verify(jobRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertEquals(KnowledgeReembedJobStatus.COMPLETED, saved.getValue().getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void sidecarFailureBeforeTheFlipFailsTheJobAndReturnsTheCollectionToReadyOnItsOldModel() {
        UUID jobId = queuedJob();
        stubPendingBatches(List.of(new KnowledgeReembedService.ChunkText(UUID.randomUUID(), "hello")));
        when(sidecarClient.embedPreChunked(anyList(), eq(TARGET))).thenThrow(new SidecarCommunicationException("sidecar down", null));

        service.processJob(jobId);

        verify(txManager, never()).getTransaction(any());
        verify(jdbcTemplate, never()).update(argThat((String sql) -> sql.contains("SET embedding_model = ?")), any(Object[].class));
        verify(jdbcTemplate).update(argThat((String sql) -> sql.contains("SET embedding_status = ?")),
            eq(KnowledgeCollection.STATUS_READY), eq(collectionId));
        org.mockito.ArgumentCaptor<KnowledgeReembedJob> saved = org.mockito.ArgumentCaptor.forClass(KnowledgeReembedJob.class);
        verify(jobRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertEquals(KnowledgeReembedJobStatus.FAILED, saved.getValue().getStatus());
        assertEquals("sidecar down", saved.getValue().getErrorMessage());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aJobResumedInSweepingSkipsEmbeddingAndTheFlip() {
        UUID jobId = UUID.randomUUID();
        KnowledgeReembedJob job = new KnowledgeReembedJob(collectionId, SOURCE, TARGET, 2);
        job.markEmbedding();
        job.markSweeping();
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        UUID straggler = UUID.randomUUID();
        stubSweepBatches(List.of(new KnowledgeReembedService.ChunkText(straggler, "late arrival")), List.of());
        when(sidecarClient.embedPreChunked(anyList(), eq(TARGET))).thenReturn(embedded(1));
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(2L);

        service.processJob(jobId);

        verify(txManager, never()).getTransaction(any());
        verify(jdbcTemplate, never()).batchUpdate(argThat((String sql) -> sql.startsWith("INSERT INTO")), anyList());
        // In-place update of the straggler, guarded so it never rewrites an already-converted row.
        verify(jdbcTemplate).batchUpdate(argThat((String sql) -> sql.startsWith("UPDATE")
            && sql.contains("SET embedding = ?::public.vector, embedding_model = ?") && sql.contains("embedding_model <> ?")), anyList());
        assertEquals(KnowledgeReembedJobStatus.COMPLETED, job.getStatus());
    }

    @Test
    void terminalJobsAreNotReprocessed() {
        UUID jobId = UUID.randomUUID();
        KnowledgeReembedJob job = new KnowledgeReembedJob(collectionId, SOURCE, TARGET, 2);
        job.markFailed("earlier");
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        service.processJob(jobId);

        verify(jobRepository, never()).save(any());
        verify(sidecarClient, never()).embedPreChunked(anyList(), anyString());
    }
}
