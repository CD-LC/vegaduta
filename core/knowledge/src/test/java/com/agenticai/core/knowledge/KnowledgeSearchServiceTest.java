package com.agenticai.core.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.knowledge.dto.SearchResultResponse;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * {@link KnowledgeSearchService} is the RAG pipeline's core - the class the
 * user's own brief called out by name as a Tier 2 priority. It fuses an
 * independent vector search and keyword search via Reciprocal Rank Fusion
 * (RRF) and optionally reranks the fused shortlist, and had zero coverage
 * despite three behaviors worth locking down as regressions: (1) the RRF
 * math itself - a chunk found by both searches must outrank one found by
 * only one, per {@link #fuse}'s own javadoc, (2) keyword search's
 * documented fail-open handling of {@link
 * org.springframework.dao.DataAccessException} (a tenant schema predating
 * migration V87's {@code content_tsv} backfill must degrade to vector-only
 * results, not break search entirely), and (3) the candidate-pool sizing
 * formula ({@code min(50, max(20, topK*4))}) that determines how many
 * candidates fusion actually has to work with.
 *
 * <p>Mocks {@link JdbcTemplate} (both raw-SQL methods - {@code vectorSearch}
 * and {@code keywordSearch} - go through {@code jdbcTemplate.query(String,
 * RowMapper, Object...)}, stubbed here by matching on distinctive SQL
 * fragments - {@code "embedding OPERATOR(public.<=>)"} for the vector query, {@code
 * "ts_rank_cd"} for the keyword query - since {@link RowMapper} lambdas are
 * never actually invoked when the surrounding method call itself is
 * stubbed), {@link SidecarClient} (for {@code embedQuery}/{@code rerank}),
 * and {@link KnowledgeCollectionRepository}. {@link KnowledgeCollection} is
 * mocked rather than constructed for real, per this codebase's established
 * convention ({@code HumanApprovalGateServiceTest}'s javadoc): only {@code
 * getId()}/{@code getEmbeddingModel()} are exercised here, and {@code id} is
 * a JPA {@code @GeneratedValue} field with no way to set it through the
 * public constructor.
 */
class KnowledgeSearchServiceTest {

    private JdbcTemplate jdbcTemplate;
    private SidecarClient sidecarClient;
    private KnowledgeCollectionRepository collectionRepository;
    private KnowledgeDocumentRepository documentRepository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        sidecarClient = mock(SidecarClient.class);
        collectionRepository = mock(KnowledgeCollectionRepository.class);
        documentRepository = mock(KnowledgeDocumentRepository.class);
        when(documentRepository.findAllById(any())).thenReturn(List.of());
    }

    private KnowledgeSearchService serviceWithRerank(boolean rerankEnabled) {
        return new KnowledgeSearchService(
            sidecarClient, jdbcTemplate, collectionRepository, documentRepository, rerankEnabled);
    }

    private KnowledgeCollection collection(UUID id, String embeddingModel) {
        KnowledgeCollection c = mock(KnowledgeCollection.class);
        when(c.getId()).thenReturn(id);
        when(c.getEmbeddingModel()).thenReturn(embeddingModel);
        return c;
    }

    private SearchResultResponse row(UUID id, UUID collectionId) {
        return new SearchResultResponse(id, "source.md", 0, "chunk content for " + id, collectionId, 0.0, null);
    }

    @SuppressWarnings("unchecked")
    private void stubVectorRows(List<SearchResultResponse> rows) {
        when(jdbcTemplate.query(argThat(sql -> sql != null && sql.contains("embedding OPERATOR(public.<=>)")), any(RowMapper.class), any(Object[].class)))
            .thenReturn(rows);
    }

    @SuppressWarnings("unchecked")
    private void stubKeywordRows(List<SearchResultResponse> rows) {
        when(jdbcTemplate.query(argThat(sql -> sql != null && sql.contains("ts_rank_cd")), any(RowMapper.class), any(Object[].class)))
            .thenReturn(rows);
    }

    // --- empty-target-collection short circuits ------------------------------

    @Test
    void searchReturnsEmptyListWithoutTouchingJdbcWhenNoCollectionsExistAtAll() {
        when(collectionRepository.findAll()).thenReturn(List.of());

        List<SearchResultResponse> results = serviceWithRerank(false).search("query", 10);

        assertEquals(List.of(), results);
        verify(jdbcTemplate, never()).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    @Test
    void searchReturnsEmptyListWhenTheRequestedCollectionIdsResolveToNothing() {
        UUID requestedId = UUID.randomUUID();
        when(collectionRepository.findAllById(Set.of(requestedId))).thenReturn(List.of());

        List<SearchResultResponse> results = serviceWithRerank(false).search("query", 10, Set.of(requestedId));

        assertEquals(List.of(), results);
        verify(collectionRepository).findAllById(Set.of(requestedId));
        verify(collectionRepository, never()).findAll();
    }

    @Test
    void searchTreatsAnEmptyCollectionIdsSetTheSameAsNullMeaningSearchEverything() {
        when(collectionRepository.findAll()).thenReturn(List.of());

        serviceWithRerank(false).search("query", 10, Set.of());

        verify(collectionRepository).findAll();
        verify(collectionRepository, never()).findAllById(any());
    }

    // --- Reciprocal Rank Fusion ------------------------------------------------

    @Test
    void aChunkFoundByBothVectorAndKeywordSearchOutranksOneFoundByOnlyOneOfThem() {
        UUID collectionId = UUID.randomUUID();
        KnowledgeCollection fixtureCollection = collection(collectionId, "minilm-l6-v2");
        when(collectionRepository.findAll()).thenReturn(List.of(fixtureCollection));
        when(sidecarClient.embedQuery("query", "minilm-l6-v2")).thenReturn(new float[]{0.1f, 0.2f});

        UUID chunkB = UUID.randomUUID(); // rank 1 in BOTH lists
        UUID chunkA = UUID.randomUUID(); // rank 2 in vector only
        UUID chunkC = UUID.randomUUID(); // rank 2 in keyword only
        stubVectorRows(List.of(row(chunkB, collectionId), row(chunkA, collectionId)));
        stubKeywordRows(List.of(row(chunkB, collectionId), row(chunkC, collectionId)));

        List<SearchResultResponse> results = serviceWithRerank(false).search("query", 10);

        assertEquals(3, results.size());
        assertEquals(chunkB, results.get(0).id());
        // chunkA and chunkC tie (each appears in exactly one list at rank 2) -
        // stable-sort ties preserve insertion order, and chunkA is inserted
        // first (vector results are fused before keyword results).
        assertEquals(chunkA, results.get(1).id());
        assertEquals(chunkC, results.get(2).id());

        double rrfK = 60.0;
        double expectedBothListsScore = 1.0 / (rrfK + 1) + 1.0 / (rrfK + 1);
        double expectedOneListScore = 1.0 / (rrfK + 2);
        assertEquals(-expectedBothListsScore, results.get(0).distance(), 1e-9);
        assertEquals(-expectedOneListScore, results.get(1).distance(), 1e-9);
        assertEquals(-expectedOneListScore, results.get(2).distance(), 1e-9);
    }

    @Test
    void keywordSearchFailingOpenOnADataAccessExceptionStillReturnsTheVectorOnlyResults() {
        // Regression test for the documented "tenant schema predating
        // migration V87's content_tsv backfill" fail-open path - the whole
        // search must degrade gracefully, not throw.
        UUID collectionId = UUID.randomUUID();
        KnowledgeCollection fixtureCollection = collection(collectionId, "minilm-l6-v2");
        when(collectionRepository.findAll()).thenReturn(List.of(fixtureCollection));
        when(sidecarClient.embedQuery("query", "minilm-l6-v2")).thenReturn(new float[]{0.1f});

        UUID vectorHit = UUID.randomUUID();
        stubVectorRows(List.of(row(vectorHit, collectionId)));
        when(jdbcTemplate.query(argThat(sql -> sql != null && sql.contains("ts_rank_cd")), any(RowMapper.class), any(Object[].class)))
            .thenThrow(new DataAccessResourceFailureException("relation \"content_tsv\" does not exist"));

        List<SearchResultResponse> results = serviceWithRerank(false).search("query", 10);

        assertEquals(1, results.size());
        assertEquals(vectorHit, results.get(0).id());
    }

    @Test
    void keywordSearchIsSkippedEntirelyWhenTheQueryIsBlank() {
        UUID collectionId = UUID.randomUUID();
        KnowledgeCollection fixtureCollection = collection(collectionId, "minilm-l6-v2");
        when(collectionRepository.findAll()).thenReturn(List.of(fixtureCollection));
        when(sidecarClient.embedQuery("  ", "minilm-l6-v2")).thenReturn(new float[]{0.1f});
        UUID vectorHit = UUID.randomUUID();
        stubVectorRows(List.of(row(vectorHit, collectionId)));

        serviceWithRerank(false).search("  ", 10);

        verify(jdbcTemplate, never()).query(argThat(sql -> sql != null && sql.contains("ts_rank_cd")), any(RowMapper.class), any(Object[].class));
    }

    // --- candidate pool sizing: min(50, max(20, topK * 4)) --------------------

    @Test
    void candidatePoolSizeIsFloorClampedToTwentyForASmallTopK() {
        assertCandidatePoolSizeFor(1, 20);
        assertCandidatePoolSizeFor(5, 20);
    }

    @Test
    void candidatePoolSizeScalesLinearlyWithTopKInTheMiddleRange() {
        assertCandidatePoolSizeFor(10, 40);
    }

    @Test
    void candidatePoolSizeIsCeilingClampedToFiftyForALargeTopK() {
        assertCandidatePoolSizeFor(100, 50);
    }

    @SuppressWarnings("unchecked")
    private void assertCandidatePoolSizeFor(int topK, int expectedPoolSize) {
        jdbcTemplate = mock(JdbcTemplate.class);
        collectionRepository = mock(KnowledgeCollectionRepository.class);
        sidecarClient = mock(SidecarClient.class);
        UUID collectionId = UUID.randomUUID();
        KnowledgeCollection fixtureCollection = collection(collectionId, "minilm-l6-v2");
        when(collectionRepository.findAll()).thenReturn(List.of(fixtureCollection));
        when(sidecarClient.embedQuery("q", "minilm-l6-v2")).thenReturn(new float[]{0.1f});
        stubVectorRows(List.of());
        stubKeywordRows(List.of());

        serviceWithRerank(false).search("q", topK);

        org.mockito.ArgumentCaptor<Object[]> paramsCaptor = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(
            argThat(sql -> sql != null && sql.contains("ts_rank_cd")), any(RowMapper.class), paramsCaptor.capture()
        );
        Object[] keywordParams = paramsCaptor.getValue();
        assertEquals(expectedPoolSize, keywordParams[keywordParams.length - 1]);
    }

    // --- topK truncation of the fused (and possibly reranked) result -----------

    @Test
    void searchTruncatesTheFusedResultsToTopKEvenWhenMoreCandidatesWereFused() {
        UUID collectionId = UUID.randomUUID();
        KnowledgeCollection fixtureCollection = collection(collectionId, "minilm-l6-v2");
        when(collectionRepository.findAll()).thenReturn(List.of(fixtureCollection));
        when(sidecarClient.embedQuery("query", "minilm-l6-v2")).thenReturn(new float[]{0.1f});
        stubVectorRows(List.of(row(UUID.randomUUID(), collectionId), row(UUID.randomUUID(), collectionId), row(UUID.randomUUID(), collectionId)));
        stubKeywordRows(List.of());

        List<SearchResultResponse> results = serviceWithRerank(false).search("query", 1);

        assertEquals(1, results.size());
    }

    // --- multi-model: one embedQuery + one SQL scan per distinct model ----------

    @Test
    void searchEmbedsTheQueryOncePerDistinctEmbeddingModelAmongTheTargetCollections() {
        UUID fastCollectionId = UUID.randomUUID();
        UUID qualityCollectionId = UUID.randomUUID();
        KnowledgeCollection fastCollection = collection(fastCollectionId, "minilm-l6-v2");
        KnowledgeCollection qualityCollection = collection(qualityCollectionId, "mpnet-base-v2");
        when(collectionRepository.findAll()).thenReturn(List.of(fastCollection, qualityCollection));
        when(sidecarClient.embedQuery("query", "minilm-l6-v2")).thenReturn(new float[]{0.1f});
        when(sidecarClient.embedQuery("query", "mpnet-base-v2")).thenReturn(new float[]{0.2f});
        stubVectorRows(List.of(row(UUID.randomUUID(), fastCollectionId)));
        stubKeywordRows(List.of());

        serviceWithRerank(false).search("query", 10);

        verify(sidecarClient).embedQuery("query", "minilm-l6-v2");
        verify(sidecarClient).embedQuery("query", "mpnet-base-v2");
        // One vector-search SQL scan per distinct model group, plus one shared
        // keyword-search scan across every target collection regardless of model.
        verify(jdbcTemplate, times(2)).query(argThat(sql -> sql != null && sql.contains("embedding OPERATOR(public.<=>)")), any(RowMapper.class), any(Object[].class));
        verify(jdbcTemplate, times(1)).query(argThat(sql -> sql != null && sql.contains("ts_rank_cd")), any(RowMapper.class), any(Object[].class));
    }

    // --- rerankIfEnabled(): off by default, fails open when on ------------------

    @Test
    void rerankIsNeverCalledWhenTheFeatureFlagIsDisabled() {
        UUID collectionId = UUID.randomUUID();
        KnowledgeCollection fixtureCollection = collection(collectionId, "minilm-l6-v2");
        when(collectionRepository.findAll()).thenReturn(List.of(fixtureCollection));
        when(sidecarClient.embedQuery("query", "minilm-l6-v2")).thenReturn(new float[]{0.1f});
        stubVectorRows(List.of(row(UUID.randomUUID(), collectionId)));
        stubKeywordRows(List.of());

        serviceWithRerank(false).search("query", 10);

        verify(sidecarClient, never()).rerank(any(), any());
    }

    @Test
    void rerankReordersTheFusedResultsByCrossEncoderScoreWhenEnabledAndScoresLineUp() {
        UUID collectionId = UUID.randomUUID();
        KnowledgeCollection fixtureCollection = collection(collectionId, "minilm-l6-v2");
        when(collectionRepository.findAll()).thenReturn(List.of(fixtureCollection));
        when(sidecarClient.embedQuery("query", "minilm-l6-v2")).thenReturn(new float[]{0.1f});
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        // Fused (RRF) order would put "first" ahead of "second" (better vector
        // rank) - the cross-encoder disagrees and scores "second" higher.
        stubVectorRows(List.of(row(first, collectionId), row(second, collectionId)));
        stubKeywordRows(List.of());
        when(sidecarClient.rerank(eq("query"), any())).thenReturn(List.of(0.1, 0.9));

        List<SearchResultResponse> results = serviceWithRerank(true).search("query", 10);

        assertEquals(second, results.get(0).id());
        assertEquals(first, results.get(1).id());
    }

    @Test
    void rerankFailingOpenWithANullScoreListKeepsTheOriginalFusedOrder() {
        UUID collectionId = UUID.randomUUID();
        KnowledgeCollection fixtureCollection = collection(collectionId, "minilm-l6-v2");
        when(collectionRepository.findAll()).thenReturn(List.of(fixtureCollection));
        when(sidecarClient.embedQuery("query", "minilm-l6-v2")).thenReturn(new float[]{0.1f});
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        stubVectorRows(List.of(row(first, collectionId), row(second, collectionId)));
        stubKeywordRows(List.of());
        when(sidecarClient.rerank(eq("query"), any())).thenReturn(null);

        List<SearchResultResponse> results = serviceWithRerank(true).search("query", 10);

        assertEquals(first, results.get(0).id());
        assertEquals(second, results.get(1).id());
    }

    @Test
    void rerankFailingOpenWithASizeMismatchedScoreListKeepsTheOriginalFusedOrder() {
        UUID collectionId = UUID.randomUUID();
        KnowledgeCollection fixtureCollection = collection(collectionId, "minilm-l6-v2");
        when(collectionRepository.findAll()).thenReturn(List.of(fixtureCollection));
        when(sidecarClient.embedQuery("query", "minilm-l6-v2")).thenReturn(new float[]{0.1f});
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        stubVectorRows(List.of(row(first, collectionId), row(second, collectionId)));
        stubKeywordRows(List.of());
        // Two fused results, but only one score - a sidecar bug or partial
        // response - must fall back rather than index out of bounds.
        when(sidecarClient.rerank(eq("query"), any())).thenReturn(List.of(0.5));

        List<SearchResultResponse> results = serviceWithRerank(true).search("query", 10);

        assertEquals(first, results.get(0).id());
        assertEquals(second, results.get(1).id());
    }

    @Test
    void rerankIsNeverCalledWhenTheFusedResultSetIsEmptyEvenWithTheFlagEnabled() {
        when(collectionRepository.findAll()).thenReturn(List.of());

        serviceWithRerank(true).search("query", 10);

        verify(sidecarClient, never()).rerank(any(), any());
    }

    // --- excludeImageSourced: grounding-fidelity fix (2026-08-07, migration V190) ---

    @Test
    void excludeImageSourcedAddsTheSourceTypeFilterToBothVectorAndKeywordSql() {
        UUID collectionId = UUID.randomUUID();
        KnowledgeCollection fixtureCollection = collection(collectionId, "minilm-l6-v2");
        when(collectionRepository.findAll()).thenReturn(List.of(fixtureCollection));
        when(sidecarClient.embedQuery("query", "minilm-l6-v2")).thenReturn(new float[]{0.1f});
        stubVectorRows(List.of());
        stubKeywordRows(List.of());

        serviceWithRerank(false).search("query", 10, null, true);

        verify(jdbcTemplate).query(
            argThat(sql -> sql != null && sql.contains("embedding OPERATOR(public.<=>)") && sql.contains("metadata->>'source_type'")),
            any(RowMapper.class), any(Object[].class)
        );
        verify(jdbcTemplate).query(
            argThat(sql -> sql != null && sql.contains("ts_rank_cd") && sql.contains("metadata->>'source_type'")),
            any(RowMapper.class), any(Object[].class)
        );
    }

    @Test
    void excludeImageSourcedFalseOmitsTheSourceTypeFilterFromBothQueries() {
        UUID collectionId = UUID.randomUUID();
        KnowledgeCollection fixtureCollection = collection(collectionId, "minilm-l6-v2");
        when(collectionRepository.findAll()).thenReturn(List.of(fixtureCollection));
        when(sidecarClient.embedQuery("query", "minilm-l6-v2")).thenReturn(new float[]{0.1f});
        stubVectorRows(List.of());
        stubKeywordRows(List.of());

        serviceWithRerank(false).search("query", 10);

        verify(jdbcTemplate).query(
            argThat(sql -> sql != null && sql.contains("embedding OPERATOR(public.<=>)") && !sql.contains("source_type")),
            any(RowMapper.class), any(Object[].class)
        );
        verify(jdbcTemplate).query(
            argThat(sql -> sql != null && sql.contains("ts_rank_cd") && !sql.contains("source_type")),
            any(RowMapper.class), any(Object[].class)
        );
    }

    // --- metadata filter + HNSW index usability (Spring AI gap-fix, 2026-09-28) ---

    @Test
    void metadataFilterIsOneParameterisedContainmentPredicateOnBothQueries() {
        UUID collectionId = UUID.randomUUID();
        KnowledgeCollection fixtureCollection = collection(collectionId, "minilm-l6-v2");
        when(collectionRepository.findAll()).thenReturn(List.of(fixtureCollection));
        when(sidecarClient.embedQuery("query", "minilm-l6-v2")).thenReturn(new float[]{0.1f});
        stubVectorRows(List.of());
        stubKeywordRows(List.of());

        serviceWithRerank(false).search("query", 10, null, false, java.util.Map.of("department", "legal'; DROP TABLE x; --"));

        org.mockito.ArgumentCaptor<String> sqlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<Object[]> paramsCaptor = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, times(2)).query(sqlCaptor.capture(), any(RowMapper.class), paramsCaptor.capture());
        for (int i = 0; i < 2; i++) {
            String sql = sqlCaptor.getAllValues().get(i);
            assertTrue(sql.contains("metadata @> ?::jsonb"), sql);
            // The value never reaches the SQL text - only the bind parameter.
            assertTrue(!sql.contains("legal") && !sql.contains("DROP"), sql);
            assertTrue(java.util.Arrays.asList(paramsCaptor.getAllValues().get(i))
                .contains("{\"department\":\"legal'; DROP TABLE x; --\"}"));
        }
    }

    @Test
    void invalidMetadataFilterKeyIsRejectedBeforeAnySidecarOrDatabaseWork() {
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> serviceWithRerank(false).search("query", 10, null, false, java.util.Map.of("bad key", "x"))
        );
        verify(sidecarClient, never()).embedQuery(anyString(), anyString());
        verify(collectionRepository, never()).findAll();
    }

    @Test
    void vectorQueryOrdersByTheBareDistanceExpressionAndFiltersOnEmbeddingModelSoThePartialHnswIndexApplies() {
        UUID collectionId = UUID.randomUUID();
        KnowledgeCollection fixtureCollection = collection(collectionId, "bge-small-en");
        when(collectionRepository.findAll()).thenReturn(List.of(fixtureCollection));
        when(sidecarClient.embedQuery("query", "bge-small-en")).thenReturn(new float[]{0.5f});
        stubVectorRows(List.of());
        stubKeywordRows(List.of());

        serviceWithRerank(false).search("query", 10);

        org.mockito.ArgumentCaptor<Object[]> paramsCaptor = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(
            argThat(sql -> sql != null && sql.contains("WHERE embedding_model = ?")
                && sql.contains("ORDER BY embedding OPERATOR(public.<=>) ?::public.vector LIMIT ?")
                && !sql.contains("ORDER BY distance")),
            any(RowMapper.class), paramsCaptor.capture()
        );
        Object[] params = paramsCaptor.getValue();
        // [select-vector, model, collectionId, order-by-vector, limit]
        assertEquals("bge-small-en", params[1]);
        assertEquals(params[0], params[3]);
    }

    @Test
    void tunedSearchAppliesSetLocalHnswSettingsInsideATransaction() {
        UUID collectionId = UUID.randomUUID();
        KnowledgeCollection fixtureCollection = collection(collectionId, "minilm-l6-v2");
        when(collectionRepository.findAll()).thenReturn(List.of(fixtureCollection));
        when(sidecarClient.embedQuery("query", "minilm-l6-v2")).thenReturn(new float[]{0.1f});
        stubVectorRows(List.of());
        stubKeywordRows(List.of());
        org.springframework.transaction.PlatformTransactionManager txManager =
            mock(org.springframework.transaction.PlatformTransactionManager.class);

        new KnowledgeSearchService(sidecarClient, jdbcTemplate, collectionRepository, documentRepository, false, txManager, 120, "relaxed_order")
            .search("query", 10);

        verify(txManager).getTransaction(any());
        verify(jdbcTemplate).execute("SET LOCAL plan_cache_mode = force_custom_plan");
        verify(jdbcTemplate).execute("SET LOCAL hnsw.ef_search = 120");
        verify(jdbcTemplate).execute("SET LOCAL hnsw.iterative_scan = relaxed_order");
    }

    @Test
    void rejectedHnswSettingsFallBackToAnUntunedQueryAndAreNotRetried() {
        UUID collectionId = UUID.randomUUID();
        KnowledgeCollection fixtureCollection = collection(collectionId, "minilm-l6-v2");
        when(collectionRepository.findAll()).thenReturn(List.of(fixtureCollection));
        when(sidecarClient.embedQuery("query", "minilm-l6-v2")).thenReturn(new float[]{0.1f});
        stubVectorRows(List.of());
        stubKeywordRows(List.of());
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("unrecognized configuration parameter"))
            .when(jdbcTemplate).execute("SET LOCAL hnsw.iterative_scan = strict_order");
        org.springframework.transaction.PlatformTransactionManager txManager =
            mock(org.springframework.transaction.PlatformTransactionManager.class);
        KnowledgeSearchService service = new KnowledgeSearchService(
            sidecarClient, jdbcTemplate, collectionRepository, documentRepository, false, txManager, 100, "strict_order");

        service.search("query", 10);
        service.search("query", 10);

        // One attempt only; the vector query still ran both times (untuned).
        verify(jdbcTemplate, times(1)).execute("SET LOCAL hnsw.iterative_scan = strict_order");
        verify(jdbcTemplate, times(2)).query(argThat(sql -> sql != null && sql.contains("embedding OPERATOR(public.<=>)")), any(RowMapper.class), any(Object[].class));
    }

    @Test
    void unknownIterativeScanModeIsRejectedAtConstruction() {
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new KnowledgeSearchService(sidecarClient, jdbcTemplate, collectionRepository, documentRepository, false, null, 100, "sideways")
        );
    }
}
