package com.agenticai.core.knowledge;

import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Ensures the pgvector extension itself exists (once, at startup), and
 * maintains the per-tenant HNSW ANN indexes on {@code knowledge_chunks}
 * (on a schedule). "knowledge" profile only.
 *
 * <p><b>Does not create the knowledge_chunks table</b> - a real
 * cross-tenant leak, found testing Phase 0 Stage 5's signup flow: this class
 * used a plain {@code JdbcTemplate} connected to whatever schema the shared
 * {@code DataSource} defaulted to, entirely bypassing the tenant-scoped
 * Hibernate connection routing every other table goes through. The table
 * is created per-tenant-schema by {@code db/migration/tenant/V10__knowledge_chunks.sql}.
 * The index pass below therefore never relies on the connection's default
 * schema either: it walks tenants with {@link TenantSchemaIterator} and
 * schema-qualifies every statement via {@link KnowledgeSchemaSupport}.
 *
 * <p>{@code CREATE EXTENSION} is genuinely database-wide (not per-schema),
 * so it's still fine - actually necessary - to ensure it here once at
 * startup. Requires a pgvector-enabled Postgres; failure is logged, not fatal.
 *
 * <h2>HNSW indexes</h2>
 *
 * <p>Vector search was a sequential scan; historically deliberately (an
 * ivfflat index with default {@code probes=1} is a recall trap on a handful
 * of rows). HNSW has no training step and no such trap, so this now builds:
 *
 * <ul>
 *   <li><b>One PARTIAL index per embedding model</b> in
 *       {@link EmbeddingModelCatalog}: {@code USING hnsw (embedding
 *       vector_cosine_ops) WHERE embedding_model = '<model>'}. Partial
 *       because vectors from different models share one {@code vector(768)}
 *       column but are never comparable - every search already filters to
 *       one model (see {@link KnowledgeSearchService#vectorSearch}), and a
 *       per-model graph keeps other models' points out of each walk.
 *       {@code vector_cosine_ops} because the query orders by {@code <=>}
 *       (cosine distance). The predicate is a string constant - IMMUTABLE, as
 *       Postgres requires of index predicates - taken from the fixed catalog
 *       and additionally checked against {@link #SAFE_MODEL_KEY}.</li>
 *   <li><b>Gated</b> by {@code app.knowledge.vector-index.enabled} (default
 *       true) and a per-model row threshold
 *       ({@code app.knowledge.vector-index.min-rows}, default 5000): below it
 *       a sequential scan is already fast and exact, and an index is pure
 *       write overhead.</li>
 *   <li><b>{@code CREATE INDEX CONCURRENTLY IF NOT EXISTS}</b> when no
 *       transaction is active (the normal case - the scheduled pass runs on an
 *       autocommit connection), so building never blocks ingestion writes;
 *       plain {@code CREATE INDEX IF NOT EXISTS} otherwise, since CONCURRENTLY
 *       is illegal inside a transaction block.</li>
 *   <li><b>Invalid-index repair</b>: a CONCURRENTLY build that dies midway
 *       leaves an INVALID index that {@code IF NOT EXISTS} would skip
 *       forever; such an index is dropped and rebuilt on the next pass.</li>
 * </ul>
 *
 * <p>Runs on a schedule (not in {@link #run}) because (1) a tenant crosses
 * the threshold long after boot and new tenants appear at runtime, and
 * (2) an HNSW build over a large table takes minutes - inside an
 * {@code ApplicationRunner} it would hold startup (and readiness probes)
 * hostage. Multi-replica: one replica builds at a time under
 * {@link PgAdvisoryJobLock} (session-level lock on an autocommit connection,
 * so it holds no snapshot that CONCURRENTLY would wait on).
 */
@Component
@Profile("knowledge")
public class VectorSchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VectorSchemaInitializer.class);

    private static final String JOB_LOCK_NAME = "knowledge-vector-index-maintenance";

    /** Defense in depth for the model key interpolated into DDL - the catalog keys all match it. */
    static final java.util.regex.Pattern SAFE_MODEL_KEY = java.util.regex.Pattern.compile("[a-z0-9-]{1,48}");

    private final JdbcTemplate jdbcTemplate;
    private final TenantSchemaIterator schemaIterator;
    private final PgAdvisoryJobLock jobLock;
    private final boolean indexEnabled;
    private final long minRows;

    public VectorSchemaInitializer(
        JdbcTemplate jdbcTemplate,
        TenantSchemaIterator schemaIterator,
        PgAdvisoryJobLock jobLock,
        @Value("${app.knowledge.vector-index.enabled:true}") boolean indexEnabled,
        @Value("${app.knowledge.vector-index.min-rows:5000}") long minRows
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.schemaIterator = schemaIterator;
        this.jobLock = jobLock;
        this.indexEnabled = indexEnabled;
        this.minRows = minRows;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
            pinExtensionToPublic();
            log.info("pgvector extension ready. Per-tenant knowledge_chunks tables are created by "
                + "the tenant Flyway migration set; HNSW indexes by the scheduled index pass - see class javadoc.");
        } catch (Exception e) {
            log.warn("Could not create the pgvector extension - is Postgres running the pgvector "
                + "extension? (pgvector/pgvector:pg16 image, not plain postgres:16 - see README Stage 5). "
                + "Knowledge endpoints will fail until this is fixed. Cause: {}", e.getMessage());
        }
    }

    /**
     * Keeps pgvector's objects in {@code public}. The vector type resolves by
     * NAME through each connection's search_path, and tenant-bound connections
     * carry a single-schema path (SchemaMultiTenantConnectionProvider's
     * setSchema = {@code SET search_path TO "<tenant>"}, no public fallback),
     * so the code's schema-qualified {@code ::public.vector} casts only resolve
     * when the type actually lives in public. Unqualified CREATE EXTENSION
     * lands its objects in the first schema on the creator's search_path - if
     * the first run of it on a database happened inside a tenant-scoped Flyway
     * migration (V10), the type sits in that tenant's schema and every other
     * tenant's casts fail.
     *
     * Only issues the ALTER when the extension is misplaced - managed Postgres
     * (Azure Flexible Server) rejects ALTER EXTENSION ... SET SCHEMA outright
     * (SQLSTATE 0A000), and skipping it keeps a needless "Knowledge endpoints
     * will fail" warning out of every healthy prod boot.
     */
    private void pinExtensionToPublic() {
        try {
            String schema = jdbcTemplate.queryForObject(
                "SELECT n.nspname FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace "
                    + "WHERE e.extname = 'vector'",
                String.class
            );
            if (schema == null || "public".equals(schema)) {
                return;
            }
            jdbcTemplate.execute("ALTER EXTENSION vector SET SCHEMA public");
            log.warn("Moved pgvector extension from schema {} to public - required for tenant-scoped connections' ::public.vector casts to resolve.", schema);
        } catch (Exception e) {
            // Self-heal, not a gate: managed Postgres rejects the ALTER (see
            // javadoc) and a non-owner role can't run it either. If casts still
            // fail with "type vector does not exist", the extension lives
            // outside public - move it there manually.
            log.warn("pgvector extension is not in schema public and could not be moved there: {}", e.getMessage());
        }
    }

    /** First pass 3 minutes after boot (off the startup path), then every 6 hours. */
    @Scheduled(
        initialDelayString = "${app.knowledge.vector-index.initial-delay-ms:180000}",
        fixedDelayString = "${app.knowledge.vector-index.interval-ms:21600000}"
    )
    public void maintainVectorIndexes() {
        if (!indexEnabled) {
            return;
        }
        jobLock.tryRunExclusive(JOB_LOCK_NAME, () ->
            schemaIterator.forEachActiveTenantSchema(schema -> ensureIndexesForCurrentTenant())
        );
    }

    /** One tenant (the one bound on this thread by {@link TenantSchemaIterator}). Package-visible for tests. */
    void ensureIndexesForCurrentTenant() {
        String table = KnowledgeSchemaSupport.qualifiedTableName();
        String schema = com.agenticai.core.tenancy.TenantSchemaResolver.currentSchemaName();
        Map<String, Long> rowsByModel = countRowsByModel(table);
        boolean concurrently = !TransactionSynchronizationManager.isActualTransactionActive();
        for (EmbeddingModelCatalog.ModelOption model : EmbeddingModelCatalog.all()) {
            long rows = rowsByModel.getOrDefault(model.key(), 0L);
            if (!shouldIndex(indexEnabled, rows, minRows)) {
                continue;
            }
            String indexName = indexName(model.key());
            try {
                Boolean valid = indexValidity(schema, indexName);
                if (Boolean.FALSE.equals(valid)) {
                    log.warn("HNSW index {}.{} is INVALID (an earlier concurrent build failed) - rebuilding", schema, indexName);
                    jdbcTemplate.execute(dropIndexSql(quote(schema) + "." + quote(indexName), concurrently));
                } else if (Boolean.TRUE.equals(valid)) {
                    continue;
                }
                long started = System.currentTimeMillis();
                jdbcTemplate.execute(createIndexSql(table, indexName, model.key(), concurrently));
                log.info("Built HNSW index {}.{} over {} '{}' chunks in {} ms",
                    schema, indexName, rows, model.key(), System.currentTimeMillis() - started);
            } catch (DataAccessException e) {
                // Per model, not per tenant: one failed build (disk, lock timeout)
                // must not stop the other models' indexes. Retried next pass.
                log.warn("Could not build HNSW index {}.{}: {}", schema, indexName, e.getMessage());
            }
        }
    }

    private Map<String, Long> countRowsByModel(String table) {
        Map<String, Long> counts = new HashMap<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT embedding_model, COUNT(*) AS n FROM " + table + " GROUP BY embedding_model"
        );
        for (Map<String, Object> row : rows) {
            Object model = row.get("embedding_model");
            Object n = row.get("n");
            if (model != null && n instanceof Number number) {
                counts.put(model.toString(), number.longValue());
            }
        }
        return counts;
    }

    /** null = no such index; otherwise pg_index.indisvalid. */
    private Boolean indexValidity(String schema, String indexName) {
        List<Boolean> valid = jdbcTemplate.queryForList(
            "SELECT i.indisvalid FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = ? AND c.relname = ?",
            Boolean.class, schema, indexName
        );
        return valid.isEmpty() ? null : valid.get(0);
    }

    static boolean shouldIndex(boolean enabled, long rowCount, long minRows) {
        return enabled && rowCount >= Math.max(1L, minRows);
    }

    /** e.g. {@code knowledge_chunks_hnsw_minilm_l6_v2} - well under Postgres' 63-byte identifier limit. */
    static String indexName(String modelKey) {
        requireSafeModelKey(modelKey);
        return "knowledge_chunks_hnsw_" + modelKey.replace('-', '_');
    }

    static String createIndexSql(String qualifiedTable, String indexName, String modelKey, boolean concurrently) {
        requireSafeModelKey(modelKey);
        return "CREATE INDEX " + (concurrently ? "CONCURRENTLY " : "") + "IF NOT EXISTS " + quote(indexName)
            + " ON " + qualifiedTable + " USING hnsw (embedding public.vector_cosine_ops)"
            + " WHERE embedding_model = '" + modelKey + "'";
    }

    static String dropIndexSql(String qualifiedIndex, boolean concurrently) {
        return "DROP INDEX " + (concurrently ? "CONCURRENTLY " : "") + "IF EXISTS " + qualifiedIndex;
    }

    private static void requireSafeModelKey(String modelKey) {
        if (modelKey == null || !SAFE_MODEL_KEY.matcher(modelKey).matches()) {
            throw new IllegalArgumentException("Refusing to build DDL for embedding model key " + modelKey);
        }
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
