package com.agenticai.core.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.knowledge.dto.SearchResultResponse;
import com.agenticai.core.knowledge.rag.ParentChunkStore;
import com.agenticai.core.knowledge.rag.ParentExpander.ParentLink;
import com.agenticai.core.knowledge.rag.QueryRewriter;
import com.agenticai.core.knowledge.rag.RagRetrievalConfig;
import com.agenticai.core.knowledge.rag.RetrievalOptions;
import com.agenticai.core.knowledge.rag.RetrievalOptions.RewriteMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * The RAG-quality stages inside {@link KnowledgeSearchService}: with every switch off (or the
 * collaborators unwired) search runs the legacy path untouched, and each opt-in stage does what its
 * options say.
 */
class KnowledgeSearchRagQualityTest {

    private JdbcTemplate jdbcTemplate;
    private SidecarClient sidecarClient;
    private KnowledgeCollectionRepository collectionRepository;
    private KnowledgeDocumentRepository documentRepository;
    private UUID collectionId;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        sidecarClient = mock(SidecarClient.class);
        collectionRepository = mock(KnowledgeCollectionRepository.class);
        documentRepository = mock(KnowledgeDocumentRepository.class);
        when(documentRepository.findAllById(any())).thenReturn(List.of());
        collectionId = UUID.randomUUID();
        KnowledgeCollection collection = mock(KnowledgeCollection.class);
        when(collection.getId()).thenReturn(collectionId);
        when(collection.getEmbeddingModel()).thenReturn("minilm-l6-v2");
        when(collectionRepository.findAll()).thenReturn(List.of(collection));
        when(sidecarClient.embedQuery(anyString(), anyString())).thenReturn(new float[] {0.1f, 0.2f});
    }

    private KnowledgeSearchService service() {
        return new KnowledgeSearchService(sidecarClient, jdbcTemplate, collectionRepository, documentRepository, false);
    }

    private SearchResultResponse row(UUID id, String content) {
        return new SearchResultResponse(id, "policy.md", 0, content, collectionId, 0.0, null);
    }

    @SuppressWarnings("unchecked")
    private void stubVector(List<SearchResultResponse> rows) {
        when(jdbcTemplate.query(argThat(sql -> sql != null && sql.contains("embedding OPERATOR(public.<=>)")), any(RowMapper.class), any(Object[].class)))
            .thenReturn(rows);
    }

    @SuppressWarnings("unchecked")
    private void stubKeyword(List<SearchResultResponse> rows) {
        when(jdbcTemplate.query(argThat(sql -> sql != null && sql.contains("ts_rank_cd")), any(RowMapper.class), any(Object[].class)))
            .thenReturn(rows);
    }

    @SuppressWarnings("unchecked")
    private void verifyKeywordQueries(int times) {
        verify(jdbcTemplate, times(times)).query(argThat(sql -> sql != null && sql.contains("ts_rank_cd")), any(RowMapper.class), any(Object[].class));
    }

    private static RagRetrievalConfig allOffConfig() {
        return new RagRetrievalConfig(false, 200, 1000, false, 60, 1.0, 1.0, "", false, "multi", 3);
    }

    private static List<UUID> ids(List<SearchResultResponse> hits) {
        return hits.stream().map(SearchResultResponse::id).toList();
    }

    // --- flag-off identity ---------------------------------------------------------------------

    @Test
    void withEverySwitchOffTheLegacyPathRunsAndNoRagCollaboratorIsTouched() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        stubVector(List.of(row(a, "va"), row(b, "vb")));
        stubKeyword(List.of(row(b, "vb"), row(c, "kc")));
        ParentChunkStore store = mock(ParentChunkStore.class);
        QueryRewriter rewriter = mock(QueryRewriter.class);

        KnowledgeSearchService plain = service();
        KnowledgeSearchService wired = service();
        wired.useRagRetrieval(allOffConfig(), store, rewriter);

        List<SearchResultResponse> expected = plain.search("query", 10);
        List<SearchResultResponse> actual = wired.search("query", 10);

        assertEquals(expected, actual, "every field of every hit is identical");
        assertEquals(List.of(b, a, c), ids(actual), "b is found by both lists and ranks first");
        verifyNoInteractions(store, rewriter);
    }

    @Test
    void anExplicitNullOptionsValueIsTheLegacyPathEvenWhenTheDeploymentHasStagesOn() {
        UUID a = UUID.randomUUID();
        stubVector(List.of(row(a, "va")));
        stubKeyword(List.of());
        ParentChunkStore store = mock(ParentChunkStore.class);
        QueryRewriter rewriter = mock(QueryRewriter.class);
        KnowledgeSearchService s = service();
        s.useRagRetrieval(new RagRetrievalConfig(true, 200, 1000, true, 30, 2.0, 1.0, "0.5", true, "multi", 3), store, rewriter);

        List<SearchResultResponse> out = s.search("query", 5, null, false, null, null);

        assertEquals(List.of(a), ids(out));
        verifyNoInteractions(store, rewriter);
    }

    @Test
    void fusionWithDefaultParametersReproducesTheLegacyRankingAndScores() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        stubVector(List.of(row(a, "va"), row(b, "vb"), row(c, "vc")));
        stubKeyword(List.of(row(c, "vc"), row(b, "vb")));
        KnowledgeSearchService s = service();

        List<SearchResultResponse> legacy = s.search("query", 10);
        List<SearchResultResponse> fusion = s.search("query", 10, null, false, null,
            new RetrievalOptions(true, 60, 1.0, 1.0, null, RewriteMode.OFF, 3, false));

        assertEquals(legacy, fusion);
    }

    // --- fusion parameters ---------------------------------------------------------------------

    @Test
    void aZeroVectorWeightSkipsTheVectorLegEntirely() {
        UUID k1 = UUID.randomUUID();
        stubKeyword(List.of(row(k1, "keyword hit")));

        List<SearchResultResponse> out = service().search("query", 5, null, false, null,
            new RetrievalOptions(true, 60, 0.0, 1.0, null, RewriteMode.OFF, 3, false));

        assertEquals(List.of(k1), ids(out));
        verify(sidecarClient, never()).embedQuery(anyString(), anyString());
    }

    @Test
    void keywordWeightCanFlipTheWinner() {
        UUID v = UUID.randomUUID();
        UUID k = UUID.randomUUID();
        stubVector(List.of(row(v, "vector favourite")));
        stubKeyword(List.of(row(k, "keyword favourite")));
        KnowledgeSearchService s = service();

        List<SearchResultResponse> even = s.search("query", 5, null, false, null,
            new RetrievalOptions(true, 60, 1.0, 1.0, null, RewriteMode.OFF, 3, false));
        List<SearchResultResponse> keywordHeavy = s.search("query", 5, null, false, null,
            new RetrievalOptions(true, 60, 1.0, 4.0, null, RewriteMode.OFF, 3, false));

        assertEquals(v, even.get(0).id());
        assertEquals(k, keywordHeavy.get(0).id());
    }

    @Test
    void mmrDemotesANearDuplicateOfTheTopHit() {
        UUID first = UUID.randomUUID();
        UUID dupe = UUID.randomUUID();
        UUID different = UUID.randomUUID();
        stubVector(List.of(
            row(first, "refund policy fourteen days after delivery"),
            row(dupe, "refund policy fourteen days after delivery date"),
            row(different, "shipping to canada takes five days")));
        stubKeyword(List.of());

        List<SearchResultResponse> out = service().search("query", 3, null, false, null,
            new RetrievalOptions(true, 60, 1.0, 1.0, 0.3, RewriteMode.OFF, 3, false));

        assertEquals(List.of(first, different, dupe), ids(out));
    }

    // --- query rewriting -----------------------------------------------------------------------

    @Test
    void multiQueryVariantsAreSearchedOnBothLegsAndFused() {
        UUID base = UUID.randomUUID();
        UUID viaVariant = UUID.randomUUID();
        // Same stub answers for every vector query: the variant's extra list re-ranks nothing by itself,
        // so assert on the calls instead - each variant costs one embed and one keyword scan.
        stubVector(List.of(row(base, "base hit"), row(viaVariant, "variant hit")));
        stubKeyword(List.of());
        QueryRewriter rewriter = mock(QueryRewriter.class);
        when(rewriter.rewrite("query", RewriteMode.MULTI, 2)).thenReturn(List.of("variant one", "variant two"));
        KnowledgeSearchService s = service();
        s.useRagRetrieval(null, null, rewriter);

        List<SearchResultResponse> out = s.search("query", 5, null, false, null,
            new RetrievalOptions(false, 60, 1.0, 1.0, null, RewriteMode.MULTI, 2, false));

        assertEquals(2, out.size());
        verify(sidecarClient).embedQuery(eq("query"), anyString());
        verify(sidecarClient).embedQuery(eq("variant one"), anyString());
        verify(sidecarClient).embedQuery(eq("variant two"), anyString());
        verifyKeywordQueries(3);
    }

    /** Original query ranks [a]; both rewritten variants rank [b]. Vector scans happen in that order. */
    @SuppressWarnings("unchecked")
    private void stubOriginalThenTwoVariantsVector(UUID a, UUID b) {
        when(jdbcTemplate.query(argThat(sql -> sql != null && sql.contains("embedding OPERATOR(public.<=>)")), any(RowMapper.class), any(Object[].class)))
            .thenReturn(List.of(row(a, "original hit")), List.of(row(b, "variant hit")), List.of(row(b, "variant hit")));
        stubKeyword(List.of());
    }

    @Test
    void rewrittenVariantsShareHalfTheOriginalWeightSoTheyCannotOutvoteTheUsersQuery() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        stubOriginalThenTwoVariantsVector(a, b);
        QueryRewriter rewriter = mock(QueryRewriter.class);
        when(rewriter.rewrite("query", RewriteMode.MULTI, 2)).thenReturn(List.of("variant one", "variant two"));
        KnowledgeSearchService s = service();
        s.useRagRetrieval(null, null, rewriter);

        List<SearchResultResponse> out = s.search("query", 5, null, false, null,
            new RetrievalOptions(false, 60, 1.0, 1.0, null, RewriteMode.MULTI, 2, false));

        // a: 1/61; b: two lists at 0.25/61 each = 0.5/61. With equal weights b would have won (2/61).
        assertEquals(List.of(a, b), ids(out));
    }

    @Test
    void theExpansionWeightIsConfigurableAndZeroSkipsTheRewriteEntirely() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        stubOriginalThenTwoVariantsVector(a, b);
        QueryRewriter rewriter = mock(QueryRewriter.class);
        when(rewriter.rewrite("query", RewriteMode.MULTI, 2)).thenReturn(List.of("variant one", "variant two"));
        KnowledgeSearchService s = service();
        s.useRagRetrieval(null, null, rewriter);
        s.setExpansionWeight(2.0);

        List<SearchResultResponse> boosted = s.search("query", 5, null, false, null,
            new RetrievalOptions(false, 60, 1.0, 1.0, null, RewriteMode.MULTI, 2, false));
        // each variant list now weighs 1.0 (2.0 / 2 variants): b = 2/61 beats a = 1/61
        assertEquals(List.of(b, a), ids(boosted));

        QueryRewriter untouched = mock(QueryRewriter.class);
        KnowledgeSearchService off = service();
        off.useRagRetrieval(null, null, untouched);
        off.setExpansionWeight(0.0);
        off.search("query", 5, null, false, null,
            new RetrievalOptions(false, 60, 1.0, 1.0, null, RewriteMode.MULTI, 2, false));
        verifyNoInteractions(untouched);
    }

    @Test
    void hydeEmbedsTheHypotheticalPassageButOnlyTheOriginalQueryHitsKeywordSearch() {
        stubVector(List.of(row(UUID.randomUUID(), "hit")));
        stubKeyword(List.of());
        QueryRewriter rewriter = mock(QueryRewriter.class);
        when(rewriter.rewrite("query", RewriteMode.HYDE, 3)).thenReturn(List.of("a hypothetical answer passage"));
        KnowledgeSearchService s = service();
        s.useRagRetrieval(null, null, rewriter);

        s.search("query", 5, null, false, null, new RetrievalOptions(false, 60, 1.0, 1.0, null, RewriteMode.HYDE, 3, false));

        verify(sidecarClient).embedQuery(eq("a hypothetical answer passage"), anyString());
        verifyKeywordQueries(1);
    }

    @Test
    void aRewriterThatFindsNothingLeavesTheOriginalQueryResultsUnchanged() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        stubVector(List.of(row(a, "va"), row(b, "vb")));
        stubKeyword(List.of(row(b, "vb")));
        QueryRewriter rewriter = mock(QueryRewriter.class);
        when(rewriter.rewrite(anyString(), any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
        KnowledgeSearchService s = service();
        s.useRagRetrieval(null, null, rewriter);

        List<SearchResultResponse> legacy = s.search("query", 10);
        List<SearchResultResponse> rewritten = s.search("query", 10, null, false, null,
            new RetrievalOptions(false, 60, 1.0, 1.0, null, RewriteMode.MULTI, 3, false));

        assertEquals(legacy, rewritten, "failure or no variants = silently the original query");
    }

    @Test
    void aFailingVariantSearchIsSkippedNotPropagated() {
        UUID a = UUID.randomUUID();
        stubVector(List.of(row(a, "va")));
        stubKeyword(List.of());
        when(sidecarClient.embedQuery(eq("variant"), anyString())).thenThrow(new IllegalStateException("sidecar hiccup"));
        QueryRewriter rewriter = mock(QueryRewriter.class);
        when(rewriter.rewrite(anyString(), any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of("variant"));
        KnowledgeSearchService s = service();
        s.useRagRetrieval(null, null, rewriter);

        List<SearchResultResponse> out = s.search("query", 5, null, false, null,
            new RetrievalOptions(false, 60, 1.0, 1.0, null, RewriteMode.MULTI, 3, false));

        assertEquals(List.of(a), ids(out));
    }

    // --- parent expansion ----------------------------------------------------------------------

    @Test
    void parentExpansionReturnsTheParentPassageDedupedAndKeepsTheChildSpan() {
        UUID child1 = UUID.randomUUID();
        UUID child2 = UUID.randomUUID();
        UUID flat = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        String parentText = "Refunds are issued within 14 days. Contact support first.";
        stubVector(List.of(row(child1, "Refunds are issued within 14 days."), row(child2, "Contact support first."), row(flat, "a flat chunk")));
        stubKeyword(List.of());
        ParentChunkStore store = mock(ParentChunkStore.class);
        when(store.linksFor(any())).thenReturn(Map.of(
            child1, new ParentLink(parentId, parentText, 0, 34),
            child2, new ParentLink(parentId, parentText, 35, parentText.length())));
        KnowledgeSearchService s = service();
        s.useRagRetrieval(null, store, null);
        s.setSpanCitationsEnabled(true);

        List<SearchResultResponse> out = s.search("query", 5, null, false, null,
            new RetrievalOptions(false, 60, 1.0, 1.0, null, RewriteMode.OFF, 3, true));

        assertEquals(List.of(child1, flat), ids(out), "the second child of the same parent is dropped");
        assertEquals(parentText, out.get(0).content());
        assertEquals(0, out.get(0).startChar());
        assertEquals(34, out.get(0).endChar());
        assertEquals("Refunds are issued within 14 days.", out.get(0).quote());
        assertEquals("a flat chunk", out.get(1).content());
        assertNull(out.get(1).startChar());
    }

    @Test
    void parentExpansionWithSpanCitationsOffLeavesTheSpanFieldsNull() {
        UUID child = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        String parentText = "Refunds are issued within 14 days. Contact support first.";
        stubVector(List.of(row(child, "Refunds are issued within 14 days.")));
        stubKeyword(List.of());
        ParentChunkStore store = mock(ParentChunkStore.class);
        when(store.linksFor(any())).thenReturn(Map.of(child, new ParentLink(parentId, parentText, 0, 34)));
        KnowledgeSearchService s = service();
        s.useRagRetrieval(null, store, null);

        List<SearchResultResponse> out = s.search("query", 5, null, false, null,
            new RetrievalOptions(false, 60, 1.0, 1.0, null, RewriteMode.OFF, 3, true));

        assertEquals(parentText, out.get(0).content(), "the parent passage is still returned");
        assertNull(out.get(0).startChar(), "span fields stay null unless app.knowledge.span-citations.enabled");
        assertNull(out.get(0).endChar());
        assertNull(out.get(0).quote());
    }

    @Test
    void parentExpansionWithoutAWiredStoreDegradesToTheChildChunks() {
        UUID a = UUID.randomUUID();
        stubVector(List.of(row(a, "child text")));
        stubKeyword(List.of());

        List<SearchResultResponse> out = service().search("query", 5, null, false, null,
            new RetrievalOptions(false, 60, 1.0, 1.0, null, RewriteMode.OFF, 3, true));

        assertEquals("child text", out.get(0).content());
    }

    @Test
    void aChildSpanSurvivesTheSpanCitationPass() {
        UUID a = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        String parentText = "Refunds are issued within 14 days. Shipping to Canada takes 5 days.";
        stubVector(List.of(row(a, "Refunds are issued within 14 days.")));
        stubKeyword(List.of());
        ParentChunkStore store = mock(ParentChunkStore.class);
        when(store.linksFor(any())).thenReturn(Map.of(a, new ParentLink(parentId, parentText, 0, 34)));
        KnowledgeSearchService s = service();
        s.useRagRetrieval(null, store, null);
        s.setSpanCitationsEnabled(true);

        List<SearchResultResponse> out = s.search("shipping to canada", 5, null, false, null,
            new RetrievalOptions(false, 60, 1.0, 1.0, null, RewriteMode.OFF, 3, true));

        assertEquals(0, out.get(0).startChar(), "the matching CHILD span is kept, not replaced by the best sentence of the parent");
        assertEquals(34, out.get(0).endChar());
        assertTrue(out.get(0).content().contains("Shipping to Canada"));
    }

    @Test
    void searchingWithNoCollectionsStillShortCircuitsBeforeAnyRagWork() {
        when(collectionRepository.findAll()).thenReturn(List.of());
        QueryRewriter rewriter = mock(QueryRewriter.class);
        KnowledgeSearchService s = service();
        s.useRagRetrieval(null, null, rewriter);

        assertEquals(List.of(), s.search("query", 5, null, false, null,
            new RetrievalOptions(true, 60, 1.0, 1.0, null, RewriteMode.MULTI, 3, true)));
        verifyNoInteractions(rewriter);
    }
}
