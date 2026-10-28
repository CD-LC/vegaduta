package com.agenticai.core.knowledge;

import com.agenticai.core.knowledge.dto.SearchResultResponse;
import com.agenticai.core.knowledge.rag.Mmr;
import com.agenticai.core.knowledge.rag.ParentChunkStore;
import com.agenticai.core.knowledge.rag.ParentExpander;
import com.agenticai.core.knowledge.rag.QueryRewriter;
import com.agenticai.core.knowledge.rag.RagRetrievalConfig;
import com.agenticai.core.knowledge.rag.RankFusion;
import com.agenticai.core.knowledge.rag.RetrievalOptions;
import com.agenticai.core.settings.SettingContext;
import com.agenticai.core.settings.SettingsResolver;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Master-Roadmap-2026.md item #2 (hybrid search + reranking), Platform-
 * Tech-Stack-Audit-2026.md P3.2. Originally pure vector-similarity search;
 * now runs a vector search AND a keyword/full-text search independently,
 * fuses the two ranked lists with Reciprocal Rank Fusion (RRF), and
 * optionally reranks the fused shortlist with a cross-encoder. See
 * KnowledgeSchemaSupport's table for {@code content_tsv} (migration V87)
 * and {@link SidecarClient#rerank} for the two new pieces this adds.
 *
 * <p>Why hybrid at all: pure cosine-similarity over an embedding can rank a
 * chunk containing an exact term (a SKU, an error code, a proper noun) below
 * a chunk that's merely "semantically similar" but doesn't actually contain
 * what the user asked for - embeddings are good at meaning, not always good
 * at exact-term recall. Keyword search (Postgres {@code tsvector}/{@code
 * ts_rank_cd}) covers exactly that failure mode; running both and fusing
 * covers cases where either alone would miss the best chunk.
 *
 * <p>Multi-model ingestion means a tenant's collections can now be embedded
 * with different local models (see EmbeddingModelCatalog) - two chunks from
 * different models are never directly comparable by cosine distance even
 * though every embedding is stored in the same {@code vector(768)} column
 * (see that column's own migration for why the padding that makes this
 * possible doesn't corrupt same-model comparisons). So a search spanning
 * collections that used different models can't embed the query once and
 * scan everything in one query - it embeds the query once PER DISTINCT
 * MODEL present among the target collections, runs one SQL scan per model
 * (filtered to that model's chunks). Keyword search has no such constraint
 * (it never touches the embedding column) and runs once across every
 * target collection regardless of model.
 */
@Service
@Profile("knowledge")
public class KnowledgeSearchService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchService.class);

    // The RRF constant (standard 60, Cormack et al.) and the candidate-pool sizing (each underlying search fetches
    // more than topK so fusion has real candidates to combine; fusing two lists already truncated to exactly topK
    // would favor whichever list ranked the true best chunk higher) are app.knowledge.search.* - see
    // KnowledgeSearchProperties for the rationale of each.
    private KnowledgeSearchProperties searchProperties = new KnowledgeSearchProperties();

    /** {@code app.knowledge.search.*}; without it the built-in defaults apply (unit tests, non-Spring callers). */
    @Autowired(required = false)
    void setSearchProperties(KnowledgeSearchProperties searchProperties) {
        this.searchProperties = searchProperties == null ? new KnowledgeSearchProperties() : searchProperties;
    }

    /**
     * The settings catalog ({@code knowledge.search.*}): a workspace's candidate-pool multiplier and ceiling replace
     * the property values. Setter-injected; without it (unit tests, non-Spring callers) the properties apply.
     */
    private SettingsResolver settings;

    @Autowired(required = false)
    void setSettings(SettingsResolver settings) {
        this.settings = settings;
    }

    /** The candidate pool for a search asking {@code topK} chunks, under the calling thread's workspace settings. */
    private int candidatePoolSize(int topK) {
        if (settings != null) {
            try {
                SettingContext ctx = SettingContext.tenant();
                return searchProperties.candidatePoolSize(topK,
                    settings.getInt(KnowledgeSettings.CANDIDATE_POOL_MULTIPLIER, ctx),
                    settings.getInt(KnowledgeSettings.CANDIDATE_POOL_MAX, ctx));
            } catch (RuntimeException e) {
                // The settings are a refinement of the property values, never a reason for a search to fail.
                log.warn("Could not read the knowledge search settings, using the configured pool: {}", e.toString());
            }
        }
        return searchProperties.candidatePoolSize(topK);
    }

    private final SidecarClient sidecarClient;
    private final KnowledgeChunkScanner scanner;
    private final KnowledgeCollectionRepository collectionRepository;
    private final KnowledgeDocumentRepository documentRepository;
    private final boolean rerankEnabled;

    /** Optional (setter-injected so existing construction is unchanged): only used to recognise the shared /try demo tenant. */
    private com.agenticai.core.security.TenantLimits tenantLimits;

    /** Name prefix of the per-agent collections the /try Agent Studio creates. Kept in step with DemoStudioDocumentService. */
    static final String STUDIO_COLLECTION_PREFIX = "studio-";

    /** Span-level citations (quote + char offsets within the chunk). Setter-injected so constructors are unchanged. */
    private boolean spanCitationsEnabled;

    @Autowired(required = false)
    void setSpanCitationsEnabled(@Value("${app.knowledge.span-citations.enabled:false}") boolean enabled) {
        this.spanCitationsEnabled = enabled;
    }

    /**
     * Provenance guard (V550): vector search skips collections whose vectors another embedding source
     * built. Setter-injected so constructors are unchanged; unset = legacy behaviour.
     */
    @Autowired(required = false)
    void setEmbeddingBinding(KnowledgeEmbeddingBinding embeddingBinding) {
        scanner.setEmbeddingBinding(embeddingBinding);
    }

    @Autowired(required = false)
    void setTenantLimits(com.agenticai.core.security.TenantLimits tenantLimits) {
        this.tenantLimits = tenantLimits;
    }

    /**
     * Per-collection access control (V710): every search is narrowed to the collections the caller may
     * read, centrally, so the REST search, the {@code search_knowledge_base} tool, workflow knowledge
     * nodes and the MCP {@code search_knowledge} tool all get it without each repeating the check.
     * Setter-injected so constructors are unchanged; a service built without it (unit tests, non-Spring
     * callers) does not filter. In the application the bean always exists (same profile).
     */
    private KnowledgeAccessService access;

    @Autowired(required = false)
    void setAccess(KnowledgeAccessService access) {
        this.access = access;
    }

    /**
     * RAG-quality stages (parent-child expansion, configurable fusion/MMR, query rewriting). All
     * setter-injected so constructors are unchanged; with none of them wired, or every
     * {@code app.knowledge.*} switch off, {@link #search} runs the exact legacy path.
     */
    private RagRetrievalConfig ragConfig;
    private ParentChunkStore parentChunkStore;
    private QueryRewriter queryRewriter;

    /** Default total RRF mass of the rewritten variants, relative to the original query's own lists. */
    static final double DEFAULT_EXPANSION_WEIGHT = 0.5;

    /**
     * Share of the original query's weight that ALL rewritten variants of one leg split between them
     * (each variant list gets {@code legWeight * expansionWeight / variantCount}), so the user's own
     * query is never outvoted by its rewrites or by a hallucinated HyDE passage. 0 = ignore rewrites.
     */
    private double expansionWeight = DEFAULT_EXPANSION_WEIGHT;

    @Autowired(required = false)
    void setExpansionWeight(@Value("${app.knowledge.query-rewrite.expansion-weight:0.5}") double weight) {
        this.expansionWeight = Double.isNaN(weight) || Double.isInfinite(weight) || weight < 0.0
            ? DEFAULT_EXPANSION_WEIGHT
            : weight;
    }

    @Autowired(required = false)
    void setRagRetrieval(RagRetrievalConfig ragConfig, ParentChunkStore parentChunkStore, QueryRewriter queryRewriter) {
        this.ragConfig = ragConfig;
        this.parentChunkStore = parentChunkStore;
        this.queryRewriter = queryRewriter;
    }

    /** Test seam for the RAG-quality collaborators (any may be null). */
    void useRagRetrieval(RagRetrievalConfig ragConfig, ParentChunkStore parentChunkStore, QueryRewriter queryRewriter) {
        setRagRetrieval(ragConfig, parentChunkStore, queryRewriter);
    }

    /** Untuned construction (no HNSW session settings) - unit tests and any non-Spring caller. */
    public KnowledgeSearchService(
        SidecarClient sidecarClient,
        JdbcTemplate jdbcTemplate,
        KnowledgeCollectionRepository collectionRepository,
        KnowledgeDocumentRepository documentRepository,
        boolean rerankEnabled
    ) {
        this(sidecarClient, jdbcTemplate, collectionRepository, documentRepository, rerankEnabled, null, 0, "off");
    }

    @Autowired
    public KnowledgeSearchService(
        SidecarClient sidecarClient,
        JdbcTemplate jdbcTemplate,
        KnowledgeCollectionRepository collectionRepository,
        KnowledgeDocumentRepository documentRepository,
        @Value("${app.knowledge.rerank.enabled:false}") boolean rerankEnabled,
        PlatformTransactionManager transactionManager,
        // pgvector's default is 40; the candidate pool here is up to 50 rows
        // AND is post-filtered by collection_id, so the default would starve
        // the LIMIT. 100 keeps recall high at modest cost.
        @Value("${app.knowledge.vector-index.ef-search:100}") int hnswEfSearch,
        // pgvector >= 0.8.0: keep scanning the graph when the collection_id /
        // metadata filters discard candidates, instead of returning fewer than
        // LIMIT rows. relaxed_order is safe here because merged results are
        // re-sorted by distance in Java anyway.
        @Value("${app.knowledge.vector-index.iterative-scan:relaxed_order}") String hnswIterativeScan
    ) {
        this.sidecarClient = sidecarClient;
        this.scanner = new KnowledgeChunkScanner(
            sidecarClient, jdbcTemplate, transactionManager, hnswEfSearch, hnswIterativeScan);
        this.collectionRepository = collectionRepository;
        this.documentRepository = documentRepository;
        this.rerankEnabled = rerankEnabled;
    }

    public List<SearchResultResponse> search(String query, int topK) {
        return search(query, topK, null);
    }

    /**
     * Knowledge Stage F1 - {@code collectionIds} null or empty means "search
     * every collection this tenant has" (unchanged default behavior); a
     * non-empty set scopes the scan to just those collections. Stage F2's
     * {@code search_knowledge_base} tool is what will actually let a model
     * pass a specific collection rather than always omitting it.
     */
    public List<SearchResultResponse> search(String query, int topK, Set<UUID> collectionIds) {
        return search(query, topK, collectionIds, false);
    }

    /**
     * Grounding-fidelity fix (2026-08-07, migration V190) - found live
     * against a real tenant (ruf_duta): garbled, short Tesseract-OCR'd
     * product-image chunks were winning both the vector and keyword scans
     * over longer, accurate text catalog docs describing the same products
     * (short chunks packed with the exact query terms score artificially
     * high), and the resulting noisy context led the model to fabricate
     * products absent from every retrieved chunk. {@code excludeImageSourced}
     * lets a caller drop every chunk whose {@code metadata->>'source_type'}
     * is {@code "image"} (see KnowledgeIngestionService) from both the
     * vector and keyword scans before fusion - not merely reranked lower,
     * excluded outright, since a garbled OCR chunk is never MORE trustworthy
     * than a clean text one for the same fact. Chunks ingested before V190
     * have no metadata (defaults to {@code {}}) and are treated as
     * not-image-sourced, i.e. included either way - this can't retroactively
     * exclude pre-migration image uploads, only ones ingested after this fix.
     */
    public List<SearchResultResponse> search(String query, int topK, Set<UUID> collectionIds, boolean excludeImageSourced) {
        return search(query, topK, collectionIds, excludeImageSourced, null);
    }

    /**
     * {@code metadataFilter} (nullable) - jsonb equality over each chunk's
     * {@code metadata}, applied to BOTH the vector and keyword scans before
     * fusion. See {@link KnowledgeMetadataFilter} for the key/value rules and
     * why it is one parameterised {@code @>} predicate.
     */
    public List<SearchResultResponse> search(
        String query, int topK, Set<UUID> collectionIds, boolean excludeImageSourced, Map<String, Object> metadataFilter
    ) {
        return search(query, topK, collectionIds, excludeImageSourced, metadataFilter,
            ragConfig == null ? null : ragConfig.defaultOptions());
    }

    /**
     * {@code options} selects the optional RAG-quality stages (see {@link RetrievalOptions}); {@code null}
     * (or an options value with every stage off) runs exactly the pipeline this class has always run.
     * Normal callers get the deployment's configured options through the overload above; the retrieval
     * eval passes explicit options to compare configurations on the same collection.
     */
    public List<SearchResultResponse> search(
        String query, int topK, Set<UUID> collectionIds, boolean excludeImageSourced, Map<String, Object> metadataFilter,
        RetrievalOptions options
    ) {
        return search(query, topK, collectionIds, excludeImageSourced, metadataFilter, options, null);
    }

    /**
     * Search on behalf of an explicit {@code principal} instead of the calling thread's user - for an
     * unattended run that acts as an agent's owner ({@link KnowledgeAccessService.Principal#ofOwner}).
     */
    public List<SearchResultResponse> searchAs(
        String query, int topK, Set<UUID> collectionIds, boolean excludeImageSourced,
        KnowledgeAccessService.Principal principal
    ) {
        return search(query, topK, collectionIds, excludeImageSourced, null,
            ragConfig == null ? null : ragConfig.defaultOptions(), principal);
    }

    /**
     * @param principal whose access narrows the collections; {@code null} = the calling thread's
     *     {@code TenantContext} user (no resolvable user = nothing readable)
     */
    public List<SearchResultResponse> search(
        String query, int topK, Set<UUID> collectionIds, boolean excludeImageSourced, Map<String, Object> metadataFilter,
        RetrievalOptions options, KnowledgeAccessService.Principal principal
    ) {
        // Validate before any sidecar/DB work so a bad filter is a clean 400.
        KnowledgeMetadataFilter.SqlPredicate vectorMetadataPredicate = KnowledgeMetadataFilter.toSql(metadataFilter, "metadata");
        KnowledgeMetadataFilter.SqlPredicate keywordMetadataPredicate = KnowledgeMetadataFilter.toSql(metadataFilter, "c.metadata");
        boolean unscoped = collectionIds == null || collectionIds.isEmpty();
        List<KnowledgeCollection> targetCollections = unscoped
            ? collectionRepository.findAll()
            : collectionRepository.findAllById(collectionIds);
        // The shared demo schema holds every anonymous visitor's studio documents. An UNSCOPED search there (the
        // legacy seeded chat agent, an agent with no collection allow-list) must never reach them: each studio
        // collection is readable only through its own agent's explicit allow-list.
        if (unscoped && inSharedDemoTenant()) {
            targetCollections = targetCollections.stream()
                .filter(c -> c.getName() == null || !c.getName().startsWith(STUDIO_COLLECTION_PREFIX))
                .toList();
        }

        // Per-collection ACL. The agent's allow-list (collectionIds) only SCOPES the search; what it may
        // actually read is that scope intersected with the caller's own access, so an allow-listed
        // collection the invoking user cannot see is dropped here (and audited when it was named).
        if (access != null) {
            targetCollections = access.filterReadable(
                targetCollections, principal != null ? principal : access.currentPrincipal(), !unscoped);
        }

        if (targetCollections.isEmpty()) {
            return List.of();
        }

        int candidatePoolSize = candidatePoolSize(topK);
        String table = KnowledgeSchemaSupport.qualifiedTableName();

        if (options != null && !options.isLegacy()) {
            return searchEnhanced(
                query, topK, targetCollections, candidatePoolSize, table, excludeImageSourced,
                vectorMetadataPredicate, keywordMetadataPredicate, options
            );
        }

        List<SearchResultResponse> vectorResults =
            scanner.vectorSearch(query, targetCollections, candidatePoolSize, table, excludeImageSourced, vectorMetadataPredicate);
        List<UUID> allCollectionIds = targetCollections.stream().map(KnowledgeCollection::getId).toList();
        List<SearchResultResponse> keywordResults =
            scanner.keywordSearch(query, allCollectionIds, candidatePoolSize, table, excludeImageSourced, keywordMetadataPredicate);

        List<SearchResultResponse> fused = fuse(vectorResults, keywordResults, candidatePoolSize);
        List<SearchResultResponse> reranked = rerankIfEnabled(query, fused);

        List<SearchResultResponse> results =
            attachSpans(query, attachScreenFlags(attachSourceUrls(reranked.stream().limit(topK).toList())));

        log.debug(
            "Knowledge search: query='{}' topK={} collectionIds={} excludeImageSourced={} vectorHits={} keywordHits={} fused={} reranked={} -> {} results",
            query, topK, collectionIds, excludeImageSourced, vectorResults.size(), keywordResults.size(), fused.size(), rerankEnabled, results.size()
        );
        return results;
    }

    /**
     * The opt-in RAG-quality pipeline. Same stages as the legacy path - vector scan, keyword scan,
     * fusion, optional cross-encoder - plus, in order: (1) query expansion legs (multi-query variants on
     * both scans, or one HyDE passage on the vector scan), (2) weighted RRF over every list with the
     * configured k, (3) optional MMR diversity before the reranker, (4) parent expansion + de-dup by
     * parent for parent-child hits. With default-valued options stage 2 is bit-for-bit the legacy fusion.
     *
     * <p>Expansion legs are best-effort: a failed variant search is logged at debug and skipped, so
     * rewriting can only add recall, never turn a working search into an error. The original query's
     * own legs behave exactly as in the legacy path.
     */
    private List<SearchResultResponse> searchEnhanced(
        String query, int topK, List<KnowledgeCollection> targetCollections, int candidatePoolSize, String table,
        boolean excludeImageSourced, KnowledgeMetadataFilter.SqlPredicate vectorPredicate,
        KnowledgeMetadataFilter.SqlPredicate keywordPredicate, RetrievalOptions options
    ) {
        List<UUID> allCollectionIds = targetCollections.stream().map(KnowledgeCollection::getId).toList();
        boolean useVector = options.vectorWeight() > 0.0;
        boolean useKeyword = options.keywordWeight() > 0.0;

        List<RankFusion.RankedList> lists = new ArrayList<>();
        if (useVector) {
            lists.add(new RankFusion.RankedList(
                scanner.vectorSearch(query, targetCollections, candidatePoolSize, table, excludeImageSourced, vectorPredicate),
                options.vectorWeight()));
        }
        if (useKeyword) {
            lists.add(new RankFusion.RankedList(
                scanner.keywordSearch(query, allCollectionIds, candidatePoolSize, table, excludeImageSourced, keywordPredicate),
                options.keywordWeight()));
        }

        int expansionLists = 0;
        if (options.rewriteMode() != RetrievalOptions.RewriteMode.OFF && queryRewriter != null && expansionWeight > 0.0) {
            boolean multi = options.rewriteMode() == RetrievalOptions.RewriteMode.MULTI;
            List<String> extras = queryRewriter.rewrite(query, options.rewriteMode(), options.maxVariants());
            // The variants of a leg share expansionWeight between them: the original query keeps the larger say.
            double perVariant = extras.isEmpty() ? 0.0 : expansionWeight / extras.size();
            for (String extra : extras) {
                try {
                    List<RankFusion.RankedList> legs = new ArrayList<>(2);
                    if (useVector) {
                        legs.add(new RankFusion.RankedList(
                            scanner.vectorSearch(extra, targetCollections, candidatePoolSize, table, excludeImageSourced, vectorPredicate),
                            options.vectorWeight() * perVariant));
                    }
                    if (useKeyword && multi) {
                        legs.add(new RankFusion.RankedList(
                            scanner.keywordSearch(extra, allCollectionIds, candidatePoolSize, table, excludeImageSourced, keywordPredicate),
                            options.keywordWeight() * perVariant));
                    }
                    lists.addAll(legs);
                    expansionLists += legs.size();
                } catch (RuntimeException e) {
                    log.debug("Query-expansion search leg skipped: {}", e.toString());
                }
            }
        }

        List<SearchResultResponse> fused = RankFusion.fuse(lists, options.rrfK(), candidatePoolSize);
        if (options.mmrLambda() != null && !fused.isEmpty()) {
            // With a cross-encoder after us, MMR only decides which diverse candidates make its shortlist.
            int keep = rerankEnabled ? Math.min(fused.size(), Math.max(1, topK * 2)) : fused.size();
            fused = Mmr.rerank(fused, options.mmrLambda(), keep);
        }
        List<SearchResultResponse> ranked = rerankIfEnabled(query, fused);
        List<SearchResultResponse> shaped = options.expandParents() ? expandParents(ranked) : ranked;

        List<SearchResultResponse> results =
            attachSpans(query, attachScreenFlags(attachSourceUrls(shaped.stream().limit(topK).toList())));
        log.debug(
            "Knowledge search (enhanced): query='{}' topK={} lists={} (expansion {}) fused={} mmr={} parentExpand={} -> {} results",
            query, topK, lists.size(), expansionLists, fused.size(), options.mmrLambda(), options.expandParents(), results.size()
        );
        return results;
    }

    /**
     * Small-to-big: swaps each parent-child hit's text for its parent passage and de-duplicates by
     * parent, before the topK cut. Flat chunks (and any hit whose parent lookup finds nothing) pass
     * through untouched.
     */
    private List<SearchResultResponse> expandParents(List<SearchResultResponse> ranked) {
        if (parentChunkStore == null || ranked.isEmpty()) {
            return ranked;
        }
        Map<UUID, ParentExpander.ParentLink> links =
            parentChunkStore.linksFor(ranked.stream().map(SearchResultResponse::id).toList());
        List<SearchResultResponse> expanded = links.isEmpty() ? ranked : ParentExpander.expand(ranked, links);
        if (spanCitationsEnabled) {
            return expanded;
        }
        // The child span ParentExpander reports is a span-citation field: with the flag off the payload must
        // not gain startChar/endChar/quote (SearchResultResponse and application.yml promise they stay null).
        return expanded.stream().map(SearchResultResponse::withoutSpan).toList();
    }

    private boolean inSharedDemoTenant() {
        if (tenantLimits == null) {
            return false;
        }
        try {
            return com.agenticai.core.security.TenantContext.current()
                .map(com.agenticai.core.security.TenantContext::getTenantId)
                .map(tenantLimits::isSharedDemoTenant)
                .orElse(false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Reciprocal Rank Fusion: {@code score(chunk) = sum over every list the
     * chunk appears in of 1/(rrf-k + rank)}, rank being that chunk's
     * 1-indexed position within that specific list. A chunk in both lists
     * accumulates both contributions; a chunk in only one list still scores
     * (just lower, typically) rather than being dropped - hybrid search
     * should never do STRICTLY worse than the weaker of its two inputs for
     * a chunk either one was confident about.
     *
     * <p>The returned list's {@code distance} field is repurposed to carry
     * {@code -score} (negated so the existing "ascending = more relevant"
     * sort convention this class has always used still holds - RRF scores
     * are themselves "higher is better"). This is no longer a literal
     * cosine distance once fusion has run; see {@link SearchResultResponse}'s
     * javadoc.
     */
    private List<SearchResultResponse> fuse(
        List<SearchResultResponse> vectorResults, List<SearchResultResponse> keywordResults, int limit
    ) {
        Map<UUID, SearchResultResponse> byId = new LinkedHashMap<>();
        Map<UUID, Double> rrfScore = new LinkedHashMap<>();
        accumulateRrf(vectorResults, byId, rrfScore);
        accumulateRrf(keywordResults, byId, rrfScore);

        return rrfScore.entrySet().stream()
            .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
            .limit(limit)
            .map(entry -> withDistance(byId.get(entry.getKey()), -entry.getValue()))
            .toList();
    }

    private void accumulateRrf(List<SearchResultResponse> results, Map<UUID, SearchResultResponse> byId, Map<UUID, Double> rrfScore) {
        for (int i = 0; i < results.size(); i++) {
            SearchResultResponse r = results.get(i);
            byId.putIfAbsent(r.id(), r);
            rrfScore.merge(r.id(), 1.0 / (searchProperties.getRrfK() + i + 1), Double::sum);
        }
    }

    /**
     * Master-Roadmap-2026.md item #2, optional pass, off by default (see
     * this class's {@code app.knowledge.rerank.enabled} constructor param).
     * Runs the cross-encoder over the ALREADY-fused shortlist only (never
     * the full collection) and re-sorts by its scores. Fails open exactly
     * like {@link SidecarClient#rerank}'s own javadoc describes: a null or
     * mis-sized response (sidecar down, unexpected error) returns {@code
     * fused} unchanged rather than throwing - reranking is a quality
     * improvement on top of a search that already works.
     */
    private List<SearchResultResponse> rerankIfEnabled(String query, List<SearchResultResponse> fused) {
        if (!rerankEnabled || fused.isEmpty()) {
            return fused;
        }
        List<String> candidateTexts = fused.stream().map(SearchResultResponse::content).toList();
        List<Double> scores = sidecarClient.rerank(query, candidateTexts);
        if (scores == null || scores.size() != fused.size()) {
            log.debug("Rerank unavailable or size-mismatched - keeping RRF-fused order");
            return fused;
        }

        List<SearchResultResponse> reranked = new ArrayList<>(fused.size());
        for (int i = 0; i < fused.size(); i++) {
            // Same negation convention as fuse(): cross-encoder scores are
            // "higher is better", this class's distance field sorts
            // ascending, so store the negated score.
            reranked.add(withDistance(fused.get(i), -scores.get(i)));
        }
        return reranked.stream().sorted(Comparator.comparingDouble(SearchResultResponse::distance)).toList();
    }

    private static SearchResultResponse withDistance(SearchResultResponse r, double distance) {
        return r.withDistance(distance);
    }

    /**
     * Span-level citation: for each hit, attaches the best-matching sentence of its chunk as
     * {@code [startChar, endChar)} offsets into {@code content} plus the quote. A hit with no
     * lexical overlap keeps its chunk-level citation (span fields stay null). Only runs when
     * {@code app.knowledge.span-citations.enabled} is on.
     */
    List<SearchResultResponse> attachSpans(String query, List<SearchResultResponse> results) {
        if (!spanCitationsEnabled || results.isEmpty()) {
            return results;
        }
        List<SearchResultResponse> out = new ArrayList<>(results.size());
        for (SearchResultResponse r : results) {
            if (r.startChar() != null) {
                // Already carries a span - a parent-child hit's child span inside its parent passage.
                out.add(r);
                continue;
            }
            CitationSpans.Span span = CitationSpans.best(r.content(), query);
            out.add(span == null ? r : r.withSpan(span.start(), span.end(), span.quote()));
        }
        return out;
    }

    /**
     * Citation fill (V254): resolves each hit's {@code document_id} to its
     * owning {@link KnowledgeDocument#getSourceUrl()} in ONE batch query,
     * after topK truncation - so a connector-sourced chunk answers "where
     * did this come from" with the Notion/Drive/Confluence/crawl link, not
     * just an opaque document id. Uploads (sourceUrl null) pass through
     * unchanged. Runs only on the final result set, never on the
     * candidate pool.
     */
    private List<SearchResultResponse> attachSourceUrls(List<SearchResultResponse> results) {
        List<UUID> documentIds = results.stream()
            .map(SearchResultResponse::documentId)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();
        if (documentIds.isEmpty()) {
            return results;
        }
        Map<UUID, String> urls = new HashMap<>();
        try {
            for (KnowledgeDocument doc : documentRepository.findAllById(documentIds)) {
                if (doc.getSourceUrl() != null) {
                    urls.put(doc.getId(), doc.getSourceUrl());
                }
            }
        } catch (RuntimeException e) {
            // A pre-V254 tenant schema has no source_url column - citations
            // degrade to null rather than failing the search.
            log.debug("source_url lookup skipped (schema likely pending V254): {}", e.getMessage());
            return results;
        }
        if (urls.isEmpty()) {
            return results;
        }
        return results.stream()
            .map(r -> r.withSourceUrl(urls.get(r.documentId())))
            .toList();
    }

    /**
     * Provenance fill: copies each hit's ingestion {@code screen_flags} onto the result (one batch
     * query over the final result set), so the model that receives a chunk also receives the marker
     * that it matched an instruction-shaped phrase when ingested. Fails open.
     */
    List<SearchResultResponse> attachScreenFlags(List<SearchResultResponse> results) {
        if (results.isEmpty()) {
            return results;
        }
        Map<UUID, List<String>> flags = scanner.screenFlags(
            results.stream().map(SearchResultResponse::id).toList(), KnowledgeSchemaSupport.qualifiedTableName());
        if (flags.isEmpty()) {
            return results;
        }
        return results.stream().map(r -> flags.containsKey(r.id()) ? r.withScreenFlags(flags.get(r.id())) : r).toList();
    }
}
