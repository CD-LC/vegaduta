package com.agenticai.core.knowledge.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.knowledge.KnowledgeCollection;
import com.agenticai.core.knowledge.KnowledgeCollectionNotFoundException;
import com.agenticai.core.knowledge.KnowledgeCollectionRepository;
import com.agenticai.core.knowledge.KnowledgeSearchService;
import com.agenticai.core.knowledge.dto.SearchResultResponse;
import com.agenticai.core.knowledge.rag.RagDtos.RagEvalConfig;
import com.agenticai.core.knowledge.rag.RagDtos.RagEvalQuestion;
import com.agenticai.core.knowledge.rag.RagDtos.RagEvalRequest;
import com.agenticai.core.knowledge.rag.RagDtos.RagEvalResponse;
import com.agenticai.core.knowledge.rag.RetrievalOptions.RewriteMode;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** The eval harness: config presets, per-config metrics, validation, plan gate and the time budget. */
class RagEvalServiceTest {

    private KnowledgeSearchService searchService;
    private KnowledgeCollectionRepository collectionRepository;
    private TenantLimits tenantLimits;
    private UUID collectionId;
    private UUID docA;

    private static RagRetrievalConfig config() {
        return new RagRetrievalConfig(false, 200, 1000, false, 40, 1.0, 2.0, "0.6", false, "multi", 3);
    }

    private RagEvalService service(boolean freePlanEnabled, int maxQuestions, int freeMaxQuestions, int maxSeconds) {
        return new RagEvalService(
            searchService, collectionRepository, config(), mock(JdbcTemplate.class), tenantLimits,
            freePlanEnabled, maxQuestions, freeMaxQuestions, maxSeconds);
    }

    @BeforeEach
    void setUp() {
        searchService = mock(KnowledgeSearchService.class);
        collectionRepository = mock(KnowledgeCollectionRepository.class);
        tenantLimits = mock(TenantLimits.class);
        collectionId = UUID.randomUUID();
        docA = UUID.randomUUID();
        when(collectionRepository.findById(collectionId)).thenReturn(Optional.of(mock(KnowledgeCollection.class)));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private SearchResultResponse hit(UUID doc, String content) {
        return new SearchResultResponse(UUID.randomUUID(), "f", 0, content, collectionId, 0.0, doc);
    }

    private static RagEvalQuestion question(String q, UUID doc) {
        return new RagEvalQuestion(q, Set.of(doc), null);
    }

    @Test
    void comparesBaselineAndFusionAndPassesLegacyNullOptionsForTheBaseline() {
        List<SearchResultResponse> baselineHits = List.of(hit(UUID.randomUUID(), "noise"), hit(docA, "answer"));
        List<SearchResultResponse> fusionHits = List.of(hit(docA, "answer"), hit(UUID.randomUUID(), "noise"));
        // Baseline = the legacy path = null options; any non-null options = a tuned config.
        when(searchService.search(anyString(), anyInt(), any(), anyBoolean(), any(), isNull())).thenReturn(baselineHits);
        when(searchService.search(anyString(), anyInt(), any(), anyBoolean(), any(), any(RetrievalOptions.class))).thenReturn(fusionHits);

        RagEvalResponse r = service(true, 200, 25, 120).run(collectionId, new RagEvalRequest(
            List.of(question("q1", docA), question("q2", docA)),
            List.of(new RagEvalConfig("base", "baseline", null, null, null, null), new RagEvalConfig("tuned", "fusion", null, null, null, null)),
            3));

        assertEquals(2, r.questions());
        assertEquals(3, r.k());
        assertFalse(r.truncated());
        assertEquals(2, r.results().size());
        var baseline = r.results().get(0);
        var fusion = r.results().get(1);
        assertEquals("base", baseline.name());
        assertEquals(1.0, baseline.hitRate());
        assertEquals(0.5, baseline.mrr(), 1e-12, "relevant hit at rank 2");
        assertEquals(1.0, fusion.mrr(), 1e-12, "relevant hit at rank 1");
        assertEquals(2, baseline.perQuestion().size());
        assertTrue(baseline.hitRateCi().low() < 1.0 && baseline.hitRateCi().high() <= 1.0);
        verify(searchService).search(eq("q1"), eq(3), eq(Set.of(collectionId)), eq(false), isNull(), isNull());
    }

    @Test
    void aFailingSearchIsCountedAsAMissNotAnError() {
        when(searchService.search(anyString(), anyInt(), any(), anyBoolean(), any(), any()))
            .thenThrow(new IllegalStateException("sidecar down"));

        RagEvalResponse r = service(true, 200, 25, 120).run(collectionId, new RagEvalRequest(
            List.of(question("q1", docA)), List.of(new RagEvalConfig(null, "baseline", null, null, null, null)), null));

        assertEquals(5, r.k(), "default k");
        assertEquals("baseline", r.results().get(0).name(), "a missing name defaults to the preset");
        assertEquals(1, r.results().get(0).failedQuestions());
        assertEquals(0.0, r.results().get(0).hitRate());
    }

    @Test
    void defaultConfigsAreBaselineAndFusion() {
        when(searchService.search(anyString(), anyInt(), any(), anyBoolean(), any(), any())).thenReturn(List.of());

        RagEvalResponse r = service(true, 200, 25, 120).run(collectionId, new RagEvalRequest(List.of(question("q", docA)), null, 99));

        assertEquals(List.of("baseline", "fusion"), r.results().stream().map(c -> c.preset()).toList());
        assertEquals(20, r.k(), "k is clamped");
    }

    @Test
    void presetsResolveToTheirOptions() {
        RagRetrievalConfig cfg = config();

        assertNull(RagEvalService.resolveOptions(new RagEvalConfig("b", "baseline", null, null, null, null), cfg));

        RetrievalOptions fusion = RagEvalService.resolveOptions(new RagEvalConfig("f", "fusion", null, null, null, null), cfg);
        assertTrue(fusion.fusion());
        assertEquals(40, fusion.rrfK());
        assertEquals(2.0, fusion.keywordWeight());
        assertEquals(0.6, fusion.mmrLambda());
        assertFalse(fusion.expandParents());

        RetrievalOptions overridden = RagEvalService.resolveOptions(new RagEvalConfig("f", "fusion", 10, 3.0, null, 0.2), cfg);
        assertEquals(10, overridden.rrfK());
        assertEquals(3.0, overridden.vectorWeight());
        assertEquals(2.0, overridden.keywordWeight(), "unset override keeps the configured value");
        assertEquals(0.2, overridden.mmrLambda());

        RetrievalOptions parents = RagEvalService.resolveOptions(new RagEvalConfig("p", "parent-child", null, null, null, null), cfg);
        assertTrue(parents.expandParents());
        assertFalse(parents.fusion());
        assertEquals(RetrievalOptions.LEGACY_RRF_K, parents.rrfK(), "parent-child alone keeps the legacy fusion");

        RetrievalOptions both = RagEvalService.resolveOptions(new RagEvalConfig("fp", "Fusion+Parent-Child", null, null, null, null), cfg);
        assertTrue(both.fusion() && both.expandParents());

        assertEquals(RewriteMode.MULTI, RagEvalService.resolveOptions(new RagEvalConfig("m", "rewrite-multi", null, null, null, null), cfg).rewriteMode());
        RetrievalOptions hyde = RagEvalService.resolveOptions(new RagEvalConfig("h", "rewrite-hyde", 20, null, null, null), cfg);
        assertEquals(RewriteMode.HYDE, hyde.rewriteMode());
        assertTrue(hyde.fusion(), "an override on a rewrite preset switches the tuned fusion on");
    }

    @Test
    void badPresetsAndMisplacedOverridesAreRejected() {
        RagRetrievalConfig cfg = config();
        assertThrows(IllegalArgumentException.class, () -> RagEvalService.resolveOptions(new RagEvalConfig("x", "nonsense", null, null, null, null), cfg));
        assertThrows(IllegalArgumentException.class, () -> RagEvalService.resolveOptions(new RagEvalConfig("x", "baseline", 10, null, null, null), cfg));
        assertThrows(IllegalArgumentException.class, () -> RagEvalService.resolveOptions(new RagEvalConfig("x", "parent-child", null, null, null, 0.5), cfg));
        assertThrows(IllegalArgumentException.class, () -> RagEvalService.resolveOptions(new RagEvalConfig("x", "fusion", 0, null, null, null), cfg));
    }

    @Test
    void validatesTheDataset() {
        RagEvalService svc = service(true, 2, 2, 120);
        assertThrows(IllegalArgumentException.class, () -> svc.run(collectionId, new RagEvalRequest(List.of(), null, null)));
        assertThrows(IllegalArgumentException.class, () -> svc.run(collectionId, null));
        assertThrows(IllegalArgumentException.class, () -> svc.run(collectionId, new RagEvalRequest(
            List.of(new RagEvalQuestion("q", null, null)), null, null)), "no expected target");
        assertThrows(IllegalArgumentException.class, () -> svc.run(collectionId, new RagEvalRequest(
            List.of(new RagEvalQuestion("  ", Set.of(docA), null)), null, null)), "blank question");
        assertThrows(IllegalArgumentException.class, () -> svc.run(collectionId, new RagEvalRequest(
            List.of(question("a", docA), question("b", docA), question("c", docA)), null, null)), "over the cap");
        assertThrows(IllegalArgumentException.class, () -> svc.run(collectionId, new RagEvalRequest(
            List.of(question("a", docA)), List.of(
                new RagEvalConfig("1", "baseline", null, null, null, null), new RagEvalConfig("2", "fusion", null, null, null, null),
                new RagEvalConfig("3", "fusion", null, null, null, null), new RagEvalConfig("4", "fusion", null, null, null, null),
                new RagEvalConfig("5", "fusion", null, null, null, null), new RagEvalConfig("6", "fusion", null, null, null, null),
                new RagEvalConfig("7", "fusion", null, null, null, null)), null)), "too many configs");
    }

    @Test
    void aNullConfigEntryAndOversizedTargetListsAreBadRequestsNotServerErrors() {
        RagEvalService svc = service(true, 200, 25, 120);
        java.util.ArrayList<RagEvalConfig> withNull = new java.util.ArrayList<>();
        withNull.add(null);
        assertThrows(IllegalArgumentException.class, () -> svc.run(collectionId, new RagEvalRequest(
            List.of(question("q", docA)), withNull, null)), "null configs element");

        List<String> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i < RagEvalService.MAX_EXPECTED_SUBSTRINGS + 1; i++) {
            tooMany.add("s" + i);
        }
        assertThrows(IllegalArgumentException.class, () -> svc.run(collectionId, new RagEvalRequest(
            List.of(new RagEvalQuestion("q", null, tooMany)), null, null)), "too many expectedSubstrings");

        String longSubstring = "x".repeat(RagEvalService.MAX_EXPECTED_SUBSTRING_CHARS + 1);
        assertThrows(IllegalArgumentException.class, () -> svc.run(collectionId, new RagEvalRequest(
            List.of(new RagEvalQuestion("q", null, List.of(longSubstring))), null, null)), "substring too long");

        Set<UUID> manyIds = new java.util.HashSet<>();
        for (int i = 0; i < RagEvalService.MAX_EXPECTED_DOCUMENT_IDS + 1; i++) {
            manyIds.add(UUID.randomUUID());
        }
        assertThrows(IllegalArgumentException.class, () -> svc.run(collectionId, new RagEvalRequest(
            List.of(new RagEvalQuestion("q", manyIds, null)), null, null)), "too many expectedDocumentIds");

        Set<UUID> nullId = new java.util.HashSet<>();
        nullId.add(null);
        assertThrows(IllegalArgumentException.class, () -> svc.run(collectionId, new RagEvalRequest(
            List.of(new RagEvalQuestion("q", nullId, null)), null, null)), "null document id");
    }

    @Test
    void aFreeTenantMayNotRunTheRewritePresetsButAPaidTenantMay() {
        when(searchService.search(anyString(), anyInt(), any(), anyBoolean(), any(), any())).thenReturn(List.of());
        RagEvalRequest rewrite = new RagEvalRequest(
            List.of(question("a", docA)), List.of(new RagEvalConfig("r", "rewrite-multi", null, null, null, null)), null);

        TenantContext.set(TenantContext.of("tenant-free", "user", Set.of()));
        when(tenantLimits.plan("tenant-free")).thenReturn(TenantPlan.FREE);
        assertThrows(TenantLimitExceededException.class, () -> service(true, 200, 25, 120).run(collectionId, rewrite));

        TenantContext.set(TenantContext.of("tenant-paid", "user", Set.of()));
        when(tenantLimits.plan("tenant-paid")).thenReturn(TenantPlan.PREMIUM);
        assertEquals(1, service(true, 200, 25, 120).run(collectionId, rewrite).questions());
    }

    @Test
    void aSecondEvalForTheSameTenantWhileOneIsRunningIsRefusedWith429() throws Exception {
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        when(searchService.search(anyString(), anyInt(), any(), anyBoolean(), any(), any())).thenAnswer(inv -> {
            started.countDown();
            release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            return List.of();
        });
        when(tenantLimits.plan("tenant-busy")).thenReturn(TenantPlan.PREMIUM);
        RagEvalService svc = service(true, 200, 25, 120);
        RagEvalRequest req = new RagEvalRequest(
            List.of(question("a", docA)), List.of(new RagEvalConfig("b", "baseline", null, null, null, null)), null);

        Thread first = new Thread(() -> {
            TenantContext.set(TenantContext.of("tenant-busy", "user", Set.of()));
            try {
                svc.run(collectionId, req);
            } finally {
                TenantContext.clear();
            }
        });
        first.start();
        try {
            assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS), "first run reached the search");
            TenantContext.set(TenantContext.of("tenant-busy", "user", Set.of()));
            org.springframework.web.server.ResponseStatusException e = assertThrows(
                org.springframework.web.server.ResponseStatusException.class, () -> svc.run(collectionId, req));
            assertEquals(429, e.getStatusCode().value());
        } finally {
            release.countDown();
            first.join(10_000);
        }

        // the slot is free again once the first run finished
        TenantContext.set(TenantContext.of("tenant-busy", "user", Set.of()));
        assertEquals(1, svc.run(collectionId, req).questions());
    }

    @Test
    void unknownCollectionIsNotFound() {
        UUID other = UUID.randomUUID();
        assertThrows(KnowledgeCollectionNotFoundException.class, () -> service(true, 200, 25, 120)
            .run(other, new RagEvalRequest(List.of(question("q", docA)), null, null)));
    }

    @Test
    void aFreeTenantIsRefusedUnlessTheEvalSwitchIsOnAndThenGetsTheSmallerCap() {
        TenantContext.set(TenantContext.of("tenant-free", "user", Set.of()));
        when(tenantLimits.plan("tenant-free")).thenReturn(TenantPlan.FREE);
        when(searchService.search(anyString(), anyInt(), any(), anyBoolean(), any(), any())).thenReturn(List.of());
        RagEvalRequest two = new RagEvalRequest(List.of(question("a", docA), question("b", docA)), null, null);

        assertThrows(TenantLimitExceededException.class, () -> service(false, 200, 1, 120).run(collectionId, two));
        assertThrows(IllegalArgumentException.class, () -> service(true, 200, 1, 120).run(collectionId, two), "FREE cap of 1 question");
        assertEquals(2, service(true, 200, 2, 120).run(collectionId, two).questions());
    }

    @Test
    void aPaidTenantUsesTheFullCapAndTheSharedDemoTenantIsRefused() {
        TenantContext.set(TenantContext.of("tenant-paid", "user", Set.of()));
        when(tenantLimits.plan("tenant-paid")).thenReturn(TenantPlan.PREMIUM);
        when(searchService.search(anyString(), anyInt(), any(), anyBoolean(), any(), any())).thenReturn(List.of());
        RagEvalRequest two = new RagEvalRequest(List.of(question("a", docA), question("b", docA)), null, null);
        assertEquals(2, service(false, 200, 1, 120).run(collectionId, two).questions());

        TenantContext.set(TenantContext.of("demo", "user", Set.of()));
        when(tenantLimits.isSharedDemoTenant("demo")).thenReturn(true);
        assertThrows(TenantLimitExceededException.class, () -> service(true, 200, 25, 120).run(collectionId, two));
    }

    @Test
    void theTimeBudgetStopsEveryConfigAtTheSameQuestionCount() {
        when(searchService.search(anyString(), anyInt(), any(), anyBoolean(), any(), any())).thenAnswer(inv -> {
            Thread.sleep(600);
            return List.of();
        });
        List<RagEvalQuestion> five = List.of(
            question("1", docA), question("2", docA), question("3", docA), question("4", docA), question("5", docA));

        RagEvalResponse r = service(true, 200, 25, 1).run(collectionId, new RagEvalRequest(
            five, List.of(new RagEvalConfig("b", "baseline", null, null, null, null)), null));

        assertTrue(r.truncated());
        assertTrue(r.questions() >= 1 && r.questions() < 5, "evaluated " + r.questions());
        assertEquals(r.questions(), r.results().get(0).questions());
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("time budget")));
    }

    @Test
    void notesExplainWhenParentChildConfigsHaveNoParentChildDocuments() {
        when(searchService.search(anyString(), anyInt(), any(), anyBoolean(), any(), any())).thenReturn(List.of());

        RagEvalResponse r = service(true, 200, 25, 120).run(collectionId, new RagEvalRequest(
            List.of(question("q", docA)), List.of(new RagEvalConfig("p", "parent-child", null, null, null, null)), null));

        assertTrue(r.notes().stream().anyMatch(n -> n.contains("no parent-child documents")));
        assertEquals(0L, r.parentChunksInCollection());
    }
}
