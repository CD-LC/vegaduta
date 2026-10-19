package com.agenticai.core.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.knowledge.KnowledgeCollection;
import com.agenticai.core.knowledge.KnowledgeCollectionRepository;
import com.agenticai.core.knowledge.KnowledgeDocumentRepository;
import com.agenticai.core.knowledge.KnowledgeSearchService;
import com.agenticai.core.knowledge.SidecarClient;
import com.agenticai.core.knowledge.dto.SearchResultResponse;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.TenantSchemaMigrator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The pgvector query path for real: {@link KnowledgeSearchService}'s own SQL
 * (schema-qualified {@code embedding <=> ?::vector} ordering, HNSW
 * {@code SET LOCAL} tuning, the keyword leg) against migrated tenant schemas.
 * Only the embedding call and the JPA collection lookup are stubbed - the
 * query vector is supplied directly, so no sidecar is needed.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class KnowledgeVectorSearchIT {

    private static final String TENANT = "tenant_it_vector";
    private static final String OTHER_TENANT = "tenant_it_vector_other";
    private static final String MODEL = "minilm-l6-v2";
    private static final int DIMS = 768; // knowledge_chunks.embedding is vector(768) since tenant V26

    private static final UUID COLLECTION_ID = UUID.randomUUID();
    private static final UUID CHUNK_A = UUID.randomUUID();
    private static final UUID CHUNK_B = UUID.randomUUID();
    private static final UUID CHUNK_C = UUID.randomUUID();
    private static final UUID OTHER_TENANT_CHUNK = UUID.randomUUID();

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateAndSeed() {
        dataSource = IntegrationTestContainers.freshDatabase("vector_it");
        jdbc = new JdbcTemplate(dataSource);
        PlatformMigrations.migrateBoot(dataSource);
        TenantSchemaMigrator migrator = new TenantSchemaMigrator(dataSource);
        migrator.migrate(TENANT);
        migrator.migrate(OTHER_TENANT);

        for (String schema : List.of(TENANT, OTHER_TENANT)) {
            jdbc.update("INSERT INTO \"" + schema + "\".knowledge_collection (id, name, embedding_model) VALUES (?, ?, ?)",
                COLLECTION_ID, "it-collection", MODEL);
        }
        insertChunk(TENANT, CHUNK_A, 0, vector(1, 0));
        insertChunk(TENANT, CHUNK_B, 1, vector(0, 1));
        insertChunk(TENANT, CHUNK_C, 2, vector(1, 1));
        // Same collection id, exact match for the query - must never leak across schemas.
        insertChunk(OTHER_TENANT, OTHER_TENANT_CHUNK, 0, vector(0.05, 1));
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void cosineDistanceOrdersChunksInTheTenantSchema() {
        List<UUID> ordered = jdbc.queryForList(
            "SELECT id FROM \"" + TENANT + "\".knowledge_chunks WHERE embedding_model = ? "
                + "ORDER BY embedding <=> ?::vector LIMIT 3",
            UUID.class, MODEL, literal(vector(0.05, 1)));
        assertThat(ordered).containsExactly(CHUNK_B, CHUNK_C, CHUNK_A);

        Double identical = jdbc.queryForObject(
            "SELECT embedding <=> ?::vector FROM \"" + TENANT + "\".knowledge_chunks WHERE id = ?",
            Double.class, literal(vector(0, 1)), CHUNK_B);
        assertThat(identical).isCloseTo(0.0, within(1e-6));
    }

    @Test
    void hnswSessionSettingsAreAccepted() {
        // KnowledgeSearchService silently falls back to untuned search if these
        // are rejected (pgvector < 0.8.0), so assert the image supports them.
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL hnsw.ef_search = 100");
            jdbc.execute("SET LOCAL hnsw.iterative_scan = relaxed_order");
        });
    }

    @Test
    void knowledgeSearchServiceReturnsNearestChunksScopedToTenant() {
        SidecarClient sidecar = mock(SidecarClient.class);
        when(sidecar.embedQuery(anyString(), eq(MODEL))).thenReturn(vector(0.05, 1));
        KnowledgeCollectionRepository collections = mock(KnowledgeCollectionRepository.class);
        KnowledgeCollection collection = new KnowledgeCollection("it-collection", null, null, MODEL, "simple");
        ReflectionTestUtils.setField(collection, "id", COLLECTION_ID);
        when(collections.findAllById(Set.of(COLLECTION_ID))).thenReturn(List.of(collection));
        KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);

        KnowledgeSearchService search = new KnowledgeSearchService(
            sidecar, jdbc, collections, documents, false,
            new DataSourceTransactionManager(dataSource), 100, "relaxed_order");

        TenantContext.set(TenantContext.of(TENANT, "integration-test", Set.of()));
        // A query with no lexical overlap keeps the keyword leg empty, so the
        // fused order is the vector order.
        List<SearchResultResponse> results = search.search("zzqx nomatch", 2, Set.of(COLLECTION_ID));

        assertThat(results).extracting(SearchResultResponse::id).containsExactly(CHUNK_B, CHUNK_C);
        assertThat(results).extracting(SearchResultResponse::id).doesNotContain(OTHER_TENANT_CHUNK);
    }

    private static void insertChunk(String schema, UUID id, int index, float[] embedding) {
        jdbc.update("INSERT INTO \"" + schema + "\".knowledge_chunks "
                + "(id, source, chunk_index, content, embedding, embedding_model, collection_id) "
                + "VALUES (?, ?, ?, ?, ?::vector, ?, ?)",
            id, "it-source.md", index, "integration chunk " + index, literal(embedding), MODEL, COLLECTION_ID);
    }

    /** A {@value #DIMS}-d vector with only the first two components set. */
    private static float[] vector(double x, double y) {
        float[] v = new float[DIMS];
        v[0] = (float) x;
        v[1] = (float) y;
        return v;
    }

    private static String literal(float[] v) {
        return IntStream.range(0, v.length).mapToObj(i -> Float.toString(v[i]))
            .collect(Collectors.joining(",", "[", "]"));
    }
}
