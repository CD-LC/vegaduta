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
 * Re-index after the embedding SOURCE changed under an unchanged catalog key (e.g. sidecar MiniLM to
 * Ollama nomic-embed-text): the old "already uses this model" refusal must not apply, every chunk must
 * be rebuilt (not only those whose key differs), and the collection must be rebound with the vectors.
 */
class KnowledgeReembedRefreshTest {

    private static final String MODEL = "minilm-l6-v2";
    private static final String OLD = "ollama:nomic-embed-text:768";
    private static final String LIVE = "ollama:bge-m3:1024";

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
        when(sidecarClient.embeddingFingerprint()).thenReturn(LIVE);
    }

    private void stubCollection(String boundFingerprint) {
        KnowledgeCollection c = mock(KnowledgeCollection.class);
        when(c.getId()).thenReturn(collectionId);
        when(c.getEmbeddingModel()).thenReturn(MODEL);
        when(c.getEmbeddingFingerprint()).thenReturn(boundFingerprint);
        when(collectionRepository.findById(collectionId)).thenReturn(Optional.of(c));
    }

    @SuppressWarnings("unchecked")
    private void stubCounts(long strays, long total) {
        when(jdbcTemplate.queryForObject(argThat((String sql) -> sql != null && sql.contains("embedding_model <> ?") && !sql.contains("NOT EXISTS")),
            eq(Long.class), any(Object[].class))).thenReturn(strays);
        when(jdbcTemplate.queryForObject(argThat((String sql) -> sql != null && sql.endsWith("WHERE collection_id = ?")),
            eq(Long.class), any(Object[].class))).thenReturn(total);
    }

    @Test
    void sameModelKeyIsStillAConflictWhenTheSourceHasNotChanged() {
        stubCollection(LIVE);
        stubCounts(0, 5);
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service.start(collectionId, MODEL));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
    }

    @Test
    void sameModelKeyIsAllowedWhenTheSourceChangedAndClearsStaleShadowRows() {
        stubCollection(OLD);
        stubCounts(0, 5);

        KnowledgeReembedJobResponse r = service.start(collectionId, MODEL);

        assertEquals(KnowledgeReembedJobStatus.QUEUED, r.status());
        verify(jdbcTemplate).update(
            argThat((String sql) -> sql.startsWith("DELETE FROM") && sql.contains("knowledge_chunk_reembed_shadow")), eq(collectionId));
    }

    @Test
    void anUnboundCollectionIsNotTreatedAsChanged() {
        stubCollection(null);
        stubCounts(0, 5);
        assertThrows(ResponseStatusException.class, () -> service.start(collectionId, MODEL));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aRefreshJobRebuildsEveryChunkFlipsWithoutTheKeyFilterAndRebinds() {
        stubCollection(OLD);
        UUID jobId = UUID.randomUUID();
        KnowledgeReembedJob job = new KnowledgeReembedJob(collectionId, MODEL, MODEL, 2);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        List<KnowledgeReembedService.ChunkText> batch = List.of(
            new KnowledgeReembedService.ChunkText(UUID.randomUUID(), "hello"),
            new KnowledgeReembedService.ChunkText(UUID.randomUUID(), "world"));
        // Pending selection must NOT filter on embedding_model <> ? (every chunk already carries the key).
        when(jdbcTemplate.query(argThat((String sql) -> sql != null && sql.contains("NOT EXISTS") && !sql.contains("embedding_model <> ?")),
            any(RowMapper.class), any(Object[].class))).thenReturn((List<Object>) (List<?>) batch, List.of());
        when(jdbcTemplate.query(argThat((String sql) -> sql != null && sql.startsWith("SELECT id, content FROM")),
            any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        when(sidecarClient.embedPreChunked(anyList(), eq(MODEL))).thenReturn(new SidecarEmbedResponse(
            MODEL, 1024, 200, 0, List.of(new SidecarChunk(0, "hello", List.of(0.1)), new SidecarChunk(1, "world", List.of(0.2)))));
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(2L);

        service.processJob(jobId);

        // Copy shadow -> live with NO "c.embedding_model <> ?" guard, because the key is unchanged.
        verify(jdbcTemplate).update(argThat((String sql) -> sql.contains("SET embedding = s.embedding")
            && !sql.contains("c.embedding_model <>")), eq(MODEL), eq(collectionId));
        verify(jdbcTemplate, never()).update(argThat((String sql) -> sql.contains("c.embedding_model <>")),
            any(Object[].class));
        // Collection rebound to the live source in the same flip.
        verify(jdbcTemplate).update(argThat((String sql) -> sql.contains("SET embedding_fingerprint = ?")), eq(LIVE), eq(collectionId));
        assertEquals(KnowledgeReembedJobStatus.COMPLETED, job.getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aKeyChangingJobWithAnUnchangedSourceStillUsesTheKeyFilterAndAlsoRebinds() {
        stubCollection(LIVE);
        UUID jobId = UUID.randomUUID();
        KnowledgeReembedJob job = new KnowledgeReembedJob(collectionId, MODEL, "multilingual-minilm", 1);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jdbcTemplate.query(argThat((String sql) -> sql != null && sql.contains("NOT EXISTS") && sql.contains("embedding_model <> ?")),
            any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        when(jdbcTemplate.query(argThat((String sql) -> sql != null && sql.startsWith("SELECT id, content FROM")),
            any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);

        service.processJob(jobId);

        verify(jdbcTemplate).update(argThat((String sql) -> sql.contains("SET embedding = s.embedding")
            && sql.contains("c.embedding_model <> ?")), eq("multilingual-minilm"), eq(collectionId), eq("multilingual-minilm"));
        verify(jdbcTemplate).update(argThat((String sql) -> sql.contains("SET embedding_fingerprint = ?")), eq(LIVE), eq(collectionId));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aKeyChangingJobWhoseSourceAlsoChangedRebuildsEveryChunkWithoutTheKeyFilter() {
        stubCollection(OLD);
        UUID jobId = UUID.randomUUID();
        KnowledgeReembedJob job = new KnowledgeReembedJob(collectionId, MODEL, "multilingual-minilm", 1);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jdbcTemplate.query(argThat((String sql) -> sql != null && sql.contains("NOT EXISTS") && !sql.contains("embedding_model <> ?")),
            any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        when(jdbcTemplate.query(argThat((String sql) -> sql != null && sql.startsWith("SELECT id, content FROM")),
            any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);

        service.processJob(jobId);

        verify(jdbcTemplate).update(argThat((String sql) -> sql.contains("SET embedding = s.embedding")
            && !sql.contains("c.embedding_model <>")), eq("multilingual-minilm"), eq(collectionId));
        verify(jdbcTemplate).update(argThat((String sql) -> sql.contains("SET embedding_fingerprint = ?")), eq(LIVE), eq(collectionId));
    }
}
