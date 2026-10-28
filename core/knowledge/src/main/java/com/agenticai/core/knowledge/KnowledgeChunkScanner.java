package com.agenticai.core.knowledge;

import com.agenticai.core.knowledge.dto.SearchResultResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The two SQL candidate scans behind {@link KnowledgeSearchService}: the per-embedding-model
 * pgvector scan (with optional HNSW session tuning) and the per-collection-config full-text scan.
 * Each returns a ranked candidate pool; fusion, reranking and citation shaping stay in the search
 * service. Not a Spring bean - the service builds one from its own constructor arguments so its
 * constructors and configuration keys are unchanged.
 */
final class KnowledgeChunkScanner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeChunkScanner.class);

    static final Set<String> ITERATIVE_SCAN_MODES = Set.of("off", "strict_order", "relaxed_order");

    private final SidecarClient sidecarClient;
    private final JdbcTemplate jdbcTemplate;

    // HNSW query tuning (see VectorSchemaInitializer for the partial indexes
    // themselves). Applied with SET LOCAL inside a short transaction wrapped
    // around each per-model vector query - SET LOCAL dies with the
    // transaction, so a pooled connection never carries these settings into
    // someone else's query. Null transactionTemplate / efSearch <= 0 = no
    // tuning (plain query, pgvector defaults).
    private final TransactionTemplate transactionTemplate;
    private final int hnswEfSearch;
    private final String hnswIterativeScan;
    // Flipped off (once, with a WARN) if the database rejects the SETs - e.g.
    // a pgvector older than 0.8.0 has no hnsw.iterative_scan. Search then
    // keeps working untuned rather than failing every query.
    private final AtomicBoolean hnswTuningSupported = new AtomicBoolean(true);

    /** Optional provenance guard (V550); null = no filtering, the legacy behaviour. */
    private volatile KnowledgeEmbeddingBinding embeddingBinding;

    void setEmbeddingBinding(KnowledgeEmbeddingBinding embeddingBinding) {
        this.embeddingBinding = embeddingBinding;
    }

    private static final RowMapper<SearchResultResponse> VECTOR_ROW_MAPPER = (rs, rowNum) -> new SearchResultResponse(
        UUID.fromString(rs.getString("id")),
        rs.getString("source"),
        rs.getInt("chunk_index"),
        rs.getString("content"),
        UUID.fromString(rs.getString("collection_id")),
        rs.getDouble("distance"),
        rs.getString("document_id") != null ? UUID.fromString(rs.getString("document_id")) : null
    );

    KnowledgeChunkScanner(
        SidecarClient sidecarClient,
        JdbcTemplate jdbcTemplate,
        PlatformTransactionManager transactionManager,
        int hnswEfSearch,
        String hnswIterativeScan
    ) {
        this.sidecarClient = sidecarClient;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionManager != null ? new TransactionTemplate(transactionManager) : null;
        this.hnswEfSearch = hnswEfSearch;
        String mode = hnswIterativeScan == null ? "off" : hnswIterativeScan.trim().toLowerCase(java.util.Locale.ROOT);
        if (!ITERATIVE_SCAN_MODES.contains(mode)) {
            throw new IllegalArgumentException(
                "app.knowledge.vector-index.iterative-scan must be one of " + ITERATIVE_SCAN_MODES + " (got " + hnswIterativeScan + ")"
            );
        }
        this.hnswIterativeScan = mode;
    }

    /**
     * Unchanged vector-search mechanics from the pre-hybrid implementation
     * (one embed + one {@code <=>} scan per distinct embedding model among
     * the target collections), except each model group's SQL now fetches
     * {@code candidatePoolSize} rows instead of the final {@code topK} - see
     * KnowledgeSearchService's javadoc on why fusion needs real candidates, not
     * pre-truncated ones.
     */
    List<SearchResultResponse> vectorSearch(
        String query, List<KnowledgeCollection> targetCollections, int candidatePoolSize, String table, boolean excludeImageSourced,
        KnowledgeMetadataFilter.SqlPredicate metadataPredicate
    ) {
        KnowledgeEmbeddingBinding binding = embeddingBinding;
        if (binding != null) {
            // Never compare a query vector with vectors another embedding source built (V550); those
            // collections still answer through the keyword scan.
            targetCollections = binding.searchable(targetCollections);
            if (targetCollections.isEmpty()) {
                return List.of();
            }
        }
        Map<String, List<UUID>> collectionIdsByModel = targetCollections.stream()
            .collect(Collectors.groupingBy(
                KnowledgeCollection::getEmbeddingModel,
                Collectors.mapping(KnowledgeCollection::getId, Collectors.toList())
            ));

        List<SearchResultResponse> merged = new ArrayList<>();

        for (Map.Entry<String, List<UUID>> entry : collectionIdsByModel.entrySet()) {
            String model = entry.getKey();
            List<UUID> idsForModel = entry.getValue();

            float[] queryVector = sidecarClient.embedQuery(query, model);
            // Straight from float[]: the old List<Double> detour boxed ~768 values per query and per model.
            String literal = KnowledgeIngestionService.toVectorLiteral(queryVector);

            List<Object> params = new ArrayList<>();
            params.add(literal);
            params.add(model);

            String placeholders = idsForModel.stream().map(id -> "?").collect(Collectors.joining(","));
            // Schema-qualified - see KnowledgeSchemaSupport's javadoc. Without
            // this, a search scanned one table shared by every tenant with no
            // filter at all, returning every other tenant's uploaded documents
            // alongside the caller's own. embedding_model filter is what keeps
            // this comparison mathematically meaningful (see KnowledgeSearchService's javadoc).
            //
            // Index shape (see VectorSchemaInitializer): one PARTIAL HNSW index
            // per model, "WHERE embedding_model = '<model>'", vector_cosine_ops.
            // The planner can only use it when (a) this query's
            // "embedding_model = ?" predicate proves the index predicate - it
            // does whenever the plan is built with the bound value, which the
            // tuned path guarantees via plan_cache_mode = force_custom_plan -
            // and (b) ORDER BY is the index opclass's own operator - here
            // expression with the index's own operator, so it's spelled out
            // here rather than ordering by the "distance" alias.
            //
            // `::public.vector` is deliberately schema-qualified: this query
            // can run on a connection whose search_path is the bare tenant
            // schema (runVectorQuery's transaction joins the JPA-bound
            // connection, which SchemaMultiTenantConnectionProvider scopes
            // via setSchema -> single-schema SET search_path, no public
            // fallback). Unqualified `::vector` then fails to resolve -
            // "type vector does not exist" - because pgvector's type lives
            // in public (pinned there by VectorSchemaInitializer).
            String sql = "SELECT id, source, chunk_index, content, collection_id, document_id, embedding OPERATOR(public.<=>) ?::public.vector AS distance FROM "
                + table + " WHERE embedding_model = ? AND collection_id IN (" + placeholders + ")"
                + (excludeImageSourced ? " AND COALESCE(metadata->>'source_type', '') <> 'image'" : "")
                + (metadataPredicate != null ? metadataPredicate.sql() : "")
                + " ORDER BY embedding OPERATOR(public.<=>) ?::public.vector LIMIT ?";
            params.addAll(idsForModel);
            if (metadataPredicate != null) {
                params.add(metadataPredicate.jsonParam());
            }
            params.add(literal);
            params.add(candidatePoolSize);

            merged.addAll(runVectorQuery(sql, params.toArray()));
        }

        // <=> is pgvector's cosine distance operator - served by the per-model
        // partial HNSW index once VectorSchemaInitializer has built it (a
        // sequential scan until then, or below its row threshold). Re-sort and
        // re-truncate here since each group above only limited itself to
        // candidatePoolSize independently - the true top-of-pool overall can
        // be skewed toward one model if it wasn't re-applied after merging
        // (and relaxed_order iterative scans may return slightly unordered rows).
        return merged.stream()
            .sorted(Comparator.comparingDouble(SearchResultResponse::distance))
            .limit(candidatePoolSize)
            .toList();
    }

    /**
     * Runs one per-model vector query, wrapped in a short transaction that
     * applies the HNSW session settings with {@code SET LOCAL} when tuning is
     * configured and supported. Falls back to the plain query when there's no
     * transaction manager, tuning is off, the caller is already inside a
     * transaction (a failing SET there would poison the caller's transaction,
     * and SET LOCAL would outlive this query), or the database has rejected
     * the settings once before.
     */
    private List<SearchResultResponse> runVectorQuery(String sql, Object[] params) {
        if (transactionTemplate == null || hnswEfSearch <= 0 || !hnswTuningSupported.get()
            || TransactionSynchronizationManager.isActualTransactionActive()) {
            return jdbcTemplate.query(sql, VECTOR_ROW_MAPPER, params);
        }
        try {
            return transactionTemplate.execute(status -> {
                applyHnswSessionSettings();
                return jdbcTemplate.query(sql, VECTOR_ROW_MAPPER, params);
            });
        } catch (HnswTuningRejectedException e) {
            if (hnswTuningSupported.compareAndSet(true, false)) {
                log.warn(
                    "Database rejected HNSW session settings (ef_search={}, iterative_scan={}) - vector search continues "
                        + "untuned. Check the pgvector version (iterative_scan needs >= 0.8.0) or set "
                        + "app.knowledge.vector-index.iterative-scan=off. Cause: {}",
                    hnswEfSearch, hnswIterativeScan, e.getCause() != null ? e.getCause().getMessage() : e.getMessage()
                );
            }
            return jdbcTemplate.query(sql, VECTOR_ROW_MAPPER, params);
        }
    }

    /** Values are validated config (an int and a whitelisted enum word), never request input. */
    private void applyHnswSessionSettings() {
        try {
            jdbcTemplate.execute("SET LOCAL plan_cache_mode = force_custom_plan");
            jdbcTemplate.execute("SET LOCAL hnsw.ef_search = " + hnswEfSearch);
            if (!"off".equals(hnswIterativeScan)) {
                jdbcTemplate.execute("SET LOCAL hnsw.iterative_scan = " + hnswIterativeScan);
            }
        } catch (DataAccessException e) {
            throw new HnswTuningRejectedException(e);
        }
    }

    /** Marks a SET failure (vs. a failure of the query itself) so only the former disables tuning. */
    private static final class HnswTuningRejectedException extends RuntimeException {
        HnswTuningRejectedException(DataAccessException cause) {
            super(cause);
        }
    }

    /**
     * Master-Roadmap-2026.md item #2. {@code ts_rank_cd} against the {@code
     * content_tsv} column (V87, reworked per-collection in V154), scoped to
     * every target collection at once (unlike vector search, keyword search
     * doesn't depend on which embedding model a collection used).
     *
     * <p>RAG audit fix (2026-07-29): the tsquery side no longer hardcodes
     * {@code 'english'} - it joins {@code knowledge_collection} and parses
     * the query with EACH row's own {@code text_search_config}, matching
     * however that collection's stored tsvectors were built (see migration
     * V154 and {@link TextSearchConfigCatalog} for why 'english' actively
     * destroyed keyword recall on Indic/mixed content, not just failed to
     * help it). One SQL statement still covers every target collection -
     * the config resolves per matched row inside the join, so no per-config
     * query grouping is needed the way vector search groups by embedding
     * model. {@code COALESCE(..., 'simple')} covers rows read mid-rollout
     * from a schema where V154 hasn't run (column exists on the chunk side
     * but not the collection side is impossible - V154 changes both in one
     * migration - so the coalesce is pure defense).
     *
     * <p>Fails open (empty list, not an exception) on any {@link
     * DataAccessException} - originally for tenant schemas predating
     * migration V87's {@code content_tsv} (see V87's javadoc, mirroring
     * V10's same documented gap), now also covering a schema predating
     * V154's {@code text_search_config} column: either way this search
     * degrades to vector-only rather than breaking knowledge search
     * entirely for that tenant.
     */
    List<SearchResultResponse> keywordSearch(
        String query, List<UUID> collectionIds, int limit, String table, boolean excludeImageSourced,
        KnowledgeMetadataFilter.SqlPredicate metadataPredicate
    ) {
        if (collectionIds.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }
        String collectionTable = KnowledgeSchemaSupport.qualifiedTableName("knowledge_collection");
        String placeholders = collectionIds.stream().map(id -> "?").collect(Collectors.joining(","));
        String sql = "SELECT c.id, c.source, c.chunk_index, c.content, c.collection_id, c.document_id, "
            + "ts_rank_cd(c.content_tsv, plainto_tsquery(COALESCE(kc.text_search_config, 'simple')::regconfig, ?)) AS rank"
            + " FROM " + table + " c JOIN " + collectionTable + " kc ON kc.id = c.collection_id"
            + " WHERE c.content_tsv @@ plainto_tsquery(COALESCE(kc.text_search_config, 'simple')::regconfig, ?)"
            + " AND c.collection_id IN (" + placeholders + ")"
            + (excludeImageSourced ? " AND COALESCE(c.metadata->>'source_type', '') <> 'image'" : "")
            + (metadataPredicate != null ? metadataPredicate.sql() : "")
            + " ORDER BY rank DESC LIMIT ?";

        List<Object> params = new ArrayList<>();
        params.add(query);
        params.add(query);
        params.addAll(collectionIds);
        if (metadataPredicate != null) {
            params.add(metadataPredicate.jsonParam());
        }
        params.add(limit);

        try {
            return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new SearchResultResponse(
                    UUID.fromString(rs.getString("id")),
                    rs.getString("source"),
                    rs.getInt("chunk_index"),
                    rs.getString("content"),
                    UUID.fromString(rs.getString("collection_id")),
                    // Raw ts_rank_cd score - never surfaced to a caller as-is,
                    // only used to compute this list's rank position for RRF
                    // fusion (see KnowledgeSearchService#fuse). Not comparable to a vector
                    // search's cosine distance.
                    rs.getDouble("rank"),
                    rs.getString("document_id") != null ? UUID.fromString(rs.getString("document_id")) : null
                ),
                params.toArray()
            );
        } catch (DataAccessException e) {
            log.warn(
                "Keyword search failed (falling back to vector-only results) - likely a tenant schema pending "
                    + "migration V87's content_tsv backfill: {}",
                e.getMessage()
            );
            return List.of();
        }
    }

    /**
     * The ingestion write-screen's {@code screen_flags} marker (see {@link KnowledgeIngestionService})
     * for each of {@code ids} that has one, in one query. Chunks without a marker are absent from the
     * map. Fails open to an empty map: a lookup problem must never fail a search.
     */
    Map<UUID, List<String>> screenFlags(List<UUID> ids, String table) {
        Map<UUID, List<String>> out = new java.util.HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        String idArray = ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
        com.fasterxml.jackson.databind.ObjectMapper mapper = com.agenticai.core.json.JsonMappers.plain();
        try {
            jdbcTemplate.query(
                "SELECT id, metadata->'screen_flags' AS flags FROM " + table
                    + " WHERE id = ANY(?::uuid[]) AND metadata->'screen_flags' IS NOT NULL",
                rs -> {
                    try {
                        List<String> flags = mapper.readValue(
                            rs.getString("flags"), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { });
                        out.put(UUID.fromString(rs.getString("id")), flags);
                    } catch (Exception ignored) {
                        // a malformed marker is skipped, never fails the search
                    }
                },
                idArray
            );
        } catch (RuntimeException e) {
            log.debug("screen_flags lookup skipped: {}", e.getMessage());
            out.clear();
        }
        return out;
    }
}
