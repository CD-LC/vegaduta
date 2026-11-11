package com.agenticai.core.knowledge;

import com.agenticai.core.knowledge.CitationSpans.Span;
import com.agenticai.core.knowledge.dto.SearchResultResponse;
import com.agenticai.core.knowledge.rag.ParentChunkStore;
import com.agenticai.core.knowledge.rag.ParentExpander;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Post-retrieval stages of {@link KnowledgeSearchService} - fusion, rerank, parent expansion,
 * citations and provenance fills - extracted to keep that class under the file-size cap. All
 * methods are static; collaborators and flags arrive as parameters.
 */
final class KnowledgeSearchPost {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchPost.class);

    private KnowledgeSearchPost() {}

    /**
     * Reciprocal Rank Fusion: {@code score(chunk) = sum over every list the chunk appears in of
     * 1/(rrf-k + rank)}, rank being that chunk's 1-indexed position within that specific list. A
     * chunk in both lists accumulates both contributions; a chunk in only one list still scores
     * (just lower, typically) rather than being dropped - hybrid search should never do STRICTLY
     * worse than the weaker of its two inputs for a chunk either one was confident about.
     *
     * <p>The returned list's {@code distance} field is repurposed to carry {@code -score} (negated
     * so the existing "ascending = more relevant" sort convention this class has always used still
     * holds - RRF scores are themselves "higher is better"). This is no longer a literal cosine
     * distance once fusion has run; see {@link SearchResultResponse}'s javadoc.
     */
    static List<SearchResultResponse> fuse(
            List<SearchResultResponse> vectorResults,
            List<SearchResultResponse> keywordResults,
            int limit,
            double rrfK) {
        Map<UUID, SearchResultResponse> byId = new LinkedHashMap<>();
        Map<UUID, Double> rrfScore = new LinkedHashMap<>();
        accumulateRrf(vectorResults, byId, rrfScore, rrfK);
        accumulateRrf(keywordResults, byId, rrfScore, rrfK);

        return rrfScore.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                .limit(limit)
                .map(entry -> withDistance(byId.get(entry.getKey()), -entry.getValue()))
                .toList();
    }

    private static void accumulateRrf(
            List<SearchResultResponse> results,
            Map<UUID, SearchResultResponse> byId,
            Map<UUID, Double> rrfScore,
            double rrfK) {
        for (int i = 0; i < results.size(); i++) {
            SearchResultResponse r = results.get(i);
            byId.putIfAbsent(r.id(), r);
            rrfScore.merge(r.id(), 1.0 / (rrfK + i + 1), Double::sum);
        }
    }

    /**
     * Master-Roadmap-2026.md item #2, optional pass, off by default (see {@code
     * app.knowledge.rerank.enabled}). Runs the cross-encoder over the ALREADY-fused shortlist only
     * (never the full collection) and re-sorts by its scores. Fails open exactly like {@link
     * SidecarClient#rerank}'s own javadoc describes: a null or mis-sized response (sidecar down,
     * unexpected error) returns {@code fused} unchanged rather than throwing - reranking is a
     * quality improvement on top of a search that already works.
     */
    static List<SearchResultResponse> rerankIfEnabled(
            SidecarClient sidecarClient,
            boolean rerankEnabled,
            String query,
            List<SearchResultResponse> fused) {
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
        return reranked.stream()
                .sorted(Comparator.comparingDouble(SearchResultResponse::distance))
                .toList();
    }

    private static SearchResultResponse withDistance(SearchResultResponse r, double distance) {
        return r.withDistance(distance);
    }

    /**
     * Small-to-big: swaps each parent-child hit's text for its parent passage and de-duplicates by
     * parent, before the topK cut. Flat chunks (and any hit whose parent lookup finds nothing) pass
     * through untouched.
     */
    static List<SearchResultResponse> expandParents(
            ParentChunkStore parentChunkStore,
            boolean spanCitationsEnabled,
            List<SearchResultResponse> ranked) {
        if (parentChunkStore == null || ranked.isEmpty()) {
            return ranked;
        }
        Map<UUID, ParentExpander.ParentLink> links =
                parentChunkStore.linksFor(ranked.stream().map(SearchResultResponse::id).toList());
        List<SearchResultResponse> expanded =
                links.isEmpty() ? ranked : ParentExpander.expand(ranked, links);
        if (spanCitationsEnabled) {
            return expanded;
        }
        // The child span ParentExpander reports is a span-citation field: with the flag off the
        // payload
        // must
        // not gain startChar/endChar/quote (SearchResultResponse and application.yml promise they
        // stay
        // null).
        return expanded.stream().map(SearchResultResponse::withoutSpan).toList();
    }

    /**
     * Span-level citation: for each hit, attaches the best-matching sentence of its chunk as {@code
     * [startChar, endChar)} offsets into {@code content} plus the quote. A hit with no lexical
     * overlap keeps its chunk-level citation (span fields stay null). Only runs when {@code
     * app.knowledge.span-citations.enabled} is on.
     */
    static List<SearchResultResponse> attachSpans(
            boolean spanCitationsEnabled, String query, List<SearchResultResponse> results) {
        if (!spanCitationsEnabled || results.isEmpty()) {
            return results;
        }
        List<SearchResultResponse> out = new ArrayList<>(results.size());
        for (SearchResultResponse r : results) {
            if (r.startChar() != null) {
                // Already carries a span - a parent-child hit's child span inside its parent
                // passage.
                out.add(r);
                continue;
            }
            Span span = CitationSpans.best(r.content(), query);
            out.add(span == null ? r : r.withSpan(span.start(), span.end(), span.quote()));
        }
        return out;
    }

    /**
     * Citation fill (V254): resolves each hit's {@code document_id} to its owning {@link
     * KnowledgeDocument#getSourceUrl()} in ONE batch query, after topK truncation - so a
     * connector-sourced chunk answers "where did this come from" with the
     * Notion/Drive/Confluence/crawl link, not just an opaque document id. Uploads (sourceUrl null)
     * pass through unchanged. Runs only on the final result set, never on the candidate pool.
     */
    static List<SearchResultResponse> attachSourceUrls(
            KnowledgeDocumentRepository documentRepository, List<SearchResultResponse> results) {
        List<UUID> documentIds =
                results.stream()
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
        return results.stream().map(r -> r.withSourceUrl(urls.get(r.documentId()))).toList();
    }

    /**
     * Provenance fill: copies each hit's ingestion {@code screen_flags} onto the result (one batch
     * query over the final result set), so the model that receives a chunk also receives the marker
     * that it matched an instruction-shaped phrase when ingested. Fails open.
     */
    static List<SearchResultResponse> attachScreenFlags(
            KnowledgeChunkScanner scanner, List<SearchResultResponse> results) {
        if (results.isEmpty()) {
            return results;
        }
        Map<UUID, List<String>> flags =
                scanner.screenFlags(
                        results.stream().map(SearchResultResponse::id).toList(),
                        KnowledgeSchemaSupport.qualifiedTableName());
        if (flags.isEmpty()) {
            return results;
        }
        return results.stream()
                .map(r -> flags.containsKey(r.id()) ? r.withScreenFlags(flags.get(r.id())) : r)
                .toList();
    }

    static boolean inSharedDemoTenant(com.agenticai.core.security.TenantLimits tenantLimits) {
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
}
