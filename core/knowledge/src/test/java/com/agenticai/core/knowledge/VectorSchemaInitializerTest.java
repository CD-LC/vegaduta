package com.agenticai.core.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The HNSW index pass: gating (flag + per-model row threshold), the DDL shape
 * the search query depends on (partial per model, cosine ops, CONCURRENTLY),
 * and invalid-index repair. The pass runs with no TenantContext here, so
 * tables resolve to the default schema - the schema routing itself is
 * TenantSchemaIterator's (tested there).
 */
class VectorSchemaInitializerTest {

    private JdbcTemplate jdbcTemplate;
    private TenantSchemaIterator schemaIterator;
    private PgAdvisoryJobLock jobLock;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        schemaIterator = mock(TenantSchemaIterator.class);
        jobLock = mock(PgAdvisoryJobLock.class);
    }

    private VectorSchemaInitializer initializer(boolean enabled, long minRows) {
        return new VectorSchemaInitializer(jdbcTemplate, schemaIterator, jobLock, enabled, minRows);
    }

    private void stubRowCounts(Map<String, Long> countsByModel) {
        List<Map<String, Object>> rows = countsByModel.entrySet().stream()
            .map(e -> Map.<String, Object>of("embedding_model", e.getKey(), "n", e.getValue()))
            .toList();
        when(jdbcTemplate.queryForList(argThat((String sql) -> sql != null && sql.contains("GROUP BY embedding_model")))).thenReturn(rows);
    }

    @SuppressWarnings("unchecked")
    private void stubIndexValidity(List<Boolean> result) {
        when(jdbcTemplate.queryForList(argThat((String sql) -> sql != null && sql.contains("pg_index")), eq(Boolean.class), any(Object[].class)))
            .thenReturn(result);
    }

    // --- gating ----------------------------------------------------------------------------

    @Test
    void shouldIndexRequiresTheFlagAndTheRowThreshold() {
        assertFalse(VectorSchemaInitializer.shouldIndex(false, 1_000_000, 5000));
        assertFalse(VectorSchemaInitializer.shouldIndex(true, 4999, 5000));
        assertTrue(VectorSchemaInitializer.shouldIndex(true, 5000, 5000));
        // A zero/negative threshold still never indexes an empty model.
        assertFalse(VectorSchemaInitializer.shouldIndex(true, 0, 0));
        assertTrue(VectorSchemaInitializer.shouldIndex(true, 1, 0));
    }

    @Test
    void disabledFlagSkipsTheWholePassWithoutTakingTheLock() {
        initializer(false, 1).maintainVectorIndexes();
        verifyNoInteractions(jobLock, schemaIterator);
    }

    @Test
    void onlyModelsAtOrAboveTheThresholdGetAnIndexBuiltConcurrently() {
        stubRowCounts(Map.of("minilm-l6-v2", 6000L, "bge-small-en", 10L));
        stubIndexValidity(List.of());

        initializer(true, 5000).ensureIndexesForCurrentTenant();

        verify(jdbcTemplate).execute(
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS \"knowledge_chunks_hnsw_minilm_l6_v2\" ON " + KnowledgeSchemaSupport.qualifiedTableName()
                + " USING hnsw (embedding public.vector_cosine_ops) WHERE embedding_model = 'minilm-l6-v2'"
        );
        verify(jdbcTemplate, never()).execute(argThat((String sql) -> sql != null && sql.contains("bge_small_en")));
    }

    @Test
    void anExistingValidIndexIsLeftAlone() {
        stubRowCounts(Map.of("minilm-l6-v2", 6000L));
        stubIndexValidity(List.of(true));

        initializer(true, 5000).ensureIndexesForCurrentTenant();

        verify(jdbcTemplate, never()).execute(anyString());
    }

    @Test
    void anInvalidIndexLeftByAFailedConcurrentBuildIsDroppedAndRebuilt() {
        stubRowCounts(Map.of("minilm-l6-v2", 6000L));
        stubIndexValidity(List.of(false));

        initializer(true, 5000).ensureIndexesForCurrentTenant();

        verify(jdbcTemplate).execute("DROP INDEX CONCURRENTLY IF EXISTS \"" + com.agenticai.core.tenancy.TenantSchemaResolver.currentSchemaName()
            + "\".\"knowledge_chunks_hnsw_minilm_l6_v2\"");
        verify(jdbcTemplate).execute(argThat((String sql) -> sql != null && sql.startsWith("CREATE INDEX CONCURRENTLY IF NOT EXISTS")));
    }

    @Test
    void oneModelsBuildFailureDoesNotStopTheOthers() {
        stubRowCounts(Map.of("minilm-l6-v2", 6000L, "mpnet-base-v2", 6000L));
        stubIndexValidity(List.of());
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("disk full"))
            .when(jdbcTemplate).execute(argThat((String sql) -> sql != null && sql.contains("minilm_l6_v2")));

        initializer(true, 5000).ensureIndexesForCurrentTenant();

        verify(jdbcTemplate).execute(argThat((String sql) -> sql != null && sql.contains("mpnet_base_v2")));
    }

    // --- DDL shape ---------------------------------------------------------------------------

    @Test
    void createIndexSqlIsAPartialCosineHnswIndexAndOmitsConcurrentlyInsideATransaction() {
        assertEquals(
            "CREATE INDEX IF NOT EXISTS \"idx\" ON \"t\".knowledge_chunks USING hnsw (embedding public.vector_cosine_ops)"
                + " WHERE embedding_model = 'bge-small-en'",
            VectorSchemaInitializer.createIndexSql("\"t\".knowledge_chunks", "idx", "bge-small-en", false)
        );
        assertEquals("DROP INDEX IF EXISTS x", VectorSchemaInitializer.dropIndexSql("x", false));
    }

    @Test
    void everyCatalogModelGetsAUniqueLegalIndexName() {
        Set<String> names = new HashSet<>();
        for (EmbeddingModelCatalog.ModelOption model : EmbeddingModelCatalog.all()) {
            String name = VectorSchemaInitializer.indexName(model.key());
            assertTrue(name.length() <= 63, name);
            assertTrue(name.matches("[a-z0-9_]+"), name);
            names.add(name);
        }
        assertEquals(EmbeddingModelCatalog.all().size(), names.size());
    }

    @Test
    void unsafeModelKeysNeverReachDdl() {
        assertThrows(IllegalArgumentException.class,
            () -> VectorSchemaInitializer.createIndexSql("t", "idx", "x'; DROP TABLE knowledge_chunks; --", true));
        assertThrows(IllegalArgumentException.class, () -> VectorSchemaInitializer.indexName("Upper"));
        assertThrows(IllegalArgumentException.class, () -> VectorSchemaInitializer.indexName(null));
    }
}
