package com.agenticai.core.quality.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.chat.LlmCallLogService;
import com.agenticai.core.quality.dataset.dto.EvalDatasetDtos.CaseResultResponse;
import com.agenticai.core.quality.dataset.dto.EvalDatasetDtos.DatasetDetailResponse;
import com.agenticai.core.quality.dataset.dto.EvalDatasetDtos.ImportTurnCaseRequest;
import com.agenticai.core.quality.dataset.dto.EvalDatasetDtos.RunDetailResponse;
import com.agenticai.core.quality.dataset.dto.EvalDatasetDtos.TriggerRunRequest;
import com.agenticai.core.quality.dataset.dto.EvalDatasetDtos.UpsertCaseRequest;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The 2026-09 additions to the eval-dataset surface, with every collaborator mocked (no DB, no LLM,
 * no Spring context):
 *
 * <ul>
 *   <li>{@code POST /{datasetId}/cases/import} - body fallback when no persisted turn is reachable
 *       (the module-scoped case: no core/rl on the classpath, which is exactly this test's
 *       classpath), reply pre-filled as the reference answer, 400 with nothing to go on.
 *   <li>{@code POST /seed-golden} - one dataset + one case per golden entry, 409 on a repeat.
 *   <li>Run detail - trace id looked up by replay index and turned into a Langfuse link only when
 *       Langfuse is configured.
 *   <li>The PREMIUM gate on both new endpoints, same as every sibling.
 * </ul>
 */
class EvalDatasetTurnImportAndTraceLinkTest {

    private static final String TENANT_ID = "tenant-1";
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID DATASET_ID = UUID.randomUUID();
    private static final String AUTH = "Basic cGs6c2s=";

    private EvalDatasetRepository datasetRepository;
    private EvalDatasetCaseRepository caseRepository;
    private EvalRunRepository runRepository;
    private LlmCallLogService llmCallLogService;
    private EvalRunService runService;
    private TenantLimits tenantLimits;

    @BeforeEach
    void setUp() {
        datasetRepository = mock(EvalDatasetRepository.class);
        caseRepository = mock(EvalDatasetCaseRepository.class);
        runRepository = mock(EvalRunRepository.class);
        llmCallLogService = mock(LlmCallLogService.class);
        runService = mock(EvalRunService.class);
        tenantLimits = mock(TenantLimits.class);
        // Repositories echo what they are handed - the entity is the assertion target.
        when(caseRepository.save(any(EvalDatasetCase.class))).thenAnswer(inv -> inv.getArgument(0));
        when(datasetRepository.save(any(EvalDataset.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<LlmCallLogService> providerOf(LlmCallLogService service) {
        ObjectProvider<LlmCallLogService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        return provider;
    }

    /**
     * Service with Langfuse configured (host + auth) or not, and NO application context
     * (module-scoped).
     */
    private EvalDatasetService service(boolean langfuseConfigured) {
        return new EvalDatasetService(
                datasetRepository,
                caseRepository,
                runRepository,
                providerOf(llmCallLogService),
                null,
                "http://localhost:3002",
                "http://langfuse-web:3000/api/public/otel/v1/traces",
                langfuseConfigured ? AUTH : "",
                "");
    }

    private EvalDatasetController controller(EvalDatasetService service) {
        return new EvalDatasetController(service, runService, tenantLimits, false);
    }

    private void asPlan(TenantPlan plan) {
        TenantContext.set(TenantContext.of(TENANT_ID, "user@example.com", Set.of()));
        when(tenantLimits.plan(TENANT_ID)).thenReturn(plan);
    }

    private EvalDataset existingDataset() {
        EvalDataset dataset =
                new EvalDataset(AGENT_ID, "Regression set", null, 0.7, false, "user@example.com");
        when(datasetRepository.findById(DATASET_ID)).thenReturn(Optional.of(dataset));
        when(caseRepository.countByDatasetId(DATASET_ID)).thenReturn(3L);
        return dataset;
    }

    // ------------------------------------------------------------------
    // Import a turn
    // ------------------------------------------------------------------

    @Test
    void importFallsBackToBodyTextsAndPrefillsReplyAsReference() {
        existingDataset();

        EvalDatasetCase saved =
                service(false)
                        .importTurnCase(
                                AGENT_ID,
                                DATASET_ID,
                                "session-1",
                                "turn-9",
                                "1kg basmati kitna?",
                                "1kg Basmati is Rs 180.",
                                null,
                                List.of("lookup_price"));

        assertEquals("1kg basmati kitna?", saved.getUserMessage());
        // The reply a human chose to save is, by that choice, the reference answer.
        assertEquals("1kg Basmati is Rs 180.", saved.getExpectedOutput());
        assertEquals(
                List.of("lookup_price"),
                EvalDatasetService.parseExpectedTools(saved.getExpectedTools()));
        // Appended after the 3 existing cases.
        assertEquals(3, saved.getPosition());
    }

    @Test
    void importHonoursAnExplicitExpectedOutputOverTheReply() {
        existingDataset();

        EvalDatasetCase saved =
                service(false)
                        .importTurnCase(
                                AGENT_ID,
                                DATASET_ID,
                                null,
                                null,
                                "cod hai kya?",
                                "Yes, COD available.",
                                "Must confirm COD is available and mention the limit.",
                                null);

        assertEquals(
                "Must confirm COD is available and mention the limit.", saved.getExpectedOutput());
    }

    @Test
    void importWithNothingToResolveIsA400NotA500() {
        existingDataset();

        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                service(false)
                                        .importTurnCase(
                                                AGENT_ID,
                                                DATASET_ID,
                                                "session-1",
                                                "turn-9",
                                                null,
                                                "reply",
                                                null,
                                                null));

        assertTrue(e.getMessage().contains("userMessage"));
        verify(caseRepository, never()).save(any());
    }

    @Test
    void importEndpointIsPremiumGated() {
        asPlan(TenantPlan.FREE);
        EvalDatasetService service = mock(EvalDatasetService.class);

        assertThrows(
                TenantLimitExceededException.class,
                () ->
                        controller(service)
                                .importTurnCase(
                                        AGENT_ID,
                                        DATASET_ID,
                                        new ImportTurnCaseRequest("s", "t", "u", "a", null, null)));

        verify(service, never())
                .importTurnCase(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void importEndpointDelegatesTheWholeBody() {
        asPlan(TenantPlan.PREMIUM);
        EvalDatasetService service = mock(EvalDatasetService.class);
        when(service.importTurnCase(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new EvalDatasetCase(DATASET_ID, "u", "a", null, 0));

        controller(service)
                .importTurnCase(
                        AGENT_ID,
                        DATASET_ID,
                        new ImportTurnCaseRequest("s", "t", "u", "a", "expected", List.of("tool")));

        verify(service)
                .importTurnCase(
                        AGENT_ID, DATASET_ID, "s", "t", "u", "a", "expected", List.of("tool"));
    }

    // ------------------------------------------------------------------
    // Seed golden
    // ------------------------------------------------------------------

    @Test
    void seedCreatesOneDatasetAndOneCasePerGoldenEntry() throws Exception {
        when(datasetRepository.findByAgentIdOrderByCreatedAtDesc(AGENT_ID)).thenReturn(List.of());
        int goldenCount = EvalDatasetService.loadGoldenCases().size();

        EvalDataset dataset = service(false).seedGoldenDataset(AGENT_ID);

        assertEquals(EvalDatasetService.GOLDEN_DATASET_NAME, dataset.getName());
        assertEquals(AGENT_ID, dataset.getAgentId());
        assertTrue(dataset.getDescription().contains(goldenCount + " cases"));
        // Text-only golden set: tool execution stays off by default.
        assertTrue(!dataset.isToolExecutionEnabled());
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<EvalDatasetCase>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(caseRepository).saveAll(captor.capture());
        List<EvalDatasetCase> cases = captor.getValue();
        assertEquals(goldenCount, cases.size());
        for (int i = 0; i < cases.size(); i++) {
            assertEquals(i, cases.get(i).getPosition());
            assertNotNull(cases.get(i).getUserMessage());
            assertTrue(
                    cases.get(i)
                            .getExpectedOutput()
                            .startsWith("Rubric from golden-set case '"));
            assertNull(cases.get(i).getExpectedTools());
        }
    }

    @Test
    void seedingTwiceForTheSameAgentConflicts() {
        EvalDataset already =
                new EvalDataset(
                        AGENT_ID, EvalDatasetService.GOLDEN_DATASET_NAME, null, 0.7, false, null);
        when(datasetRepository.findByAgentIdOrderByCreatedAtDesc(AGENT_ID))
                .thenReturn(List.of(already));

        assertThrows(
                EvalRunConflictException.class, () -> service(false).seedGoldenDataset(AGENT_ID));
        verify(caseRepository, never()).saveAll(any());
    }

    @Test
    void seedEndpointIsPremiumGatedAndReturnsDatasetWithCases() {
        EvalDatasetService service = mock(EvalDatasetService.class);

        asPlan(TenantPlan.FREE);
        assertThrows(
                TenantLimitExceededException.class,
                () -> controller(service).seedGoldenDataset(AGENT_ID));
        verify(service, never()).seedGoldenDataset(any());

        asPlan(TenantPlan.PREMIUM);
        EvalDataset dataset =
                new EvalDataset(
                        AGENT_ID, EvalDatasetService.GOLDEN_DATASET_NAME, "d", 0.7, false, null);
        when(service.seedGoldenDataset(AGENT_ID)).thenReturn(dataset);
        when(service.listCases(any()))
                .thenReturn(List.of(new EvalDatasetCase(null, "u", "r", null, 0)));
        when(service.countCases(any())).thenReturn(1L);

        DatasetDetailResponse response = controller(service).seedGoldenDataset(AGENT_ID).getBody();

        assertNotNull(response);
        assertEquals(EvalDatasetService.GOLDEN_DATASET_NAME, response.dataset().name());
        assertEquals(1, response.cases().size());
    }

    // ------------------------------------------------------------------
    // Trace links on run detail
    // ------------------------------------------------------------------

    @Test
    void traceIdIsLookedUpByReplaySessionIndex() {
        UUID runId = UUID.randomUUID();
        when(llmCallLogService.mostRecentTraceIdForSession("eval:" + runId + ":1"))
                .thenReturn("0af7651916cd43dd8448eb211c80319c");

        EvalDatasetService service = service(true);

        assertNull(service.traceIdForRunCase(runId, 0));
        assertEquals("0af7651916cd43dd8448eb211c80319c", service.traceIdForRunCase(runId, 1));
        assertEquals(
                "http://localhost:3002/trace/0af7651916cd43dd8448eb211c80319c",
                service.langfuseTraceUrl("0af7651916cd43dd8448eb211c80319c"));
    }

    @Test
    void traceLookupFailureYieldsNullNotAnError() {
        when(llmCallLogService.mostRecentTraceIdForSession(anyString()))
                .thenThrow(new IllegalStateException("db down"));

        assertNull(service(true).traceIdForRunCase(UUID.randomUUID(), 0));
    }

    @Test
    void runDetailAttachesLinksPerResultOnlyWhenLangfuseConfigured() {
        asPlan(TenantPlan.PREMIUM);
        existingDataset();
        UUID runId = UUID.randomUUID();
        EvalRun run = mock(EvalRun.class);
        when(run.getId()).thenReturn(runId);
        when(run.getDatasetId()).thenReturn(DATASET_ID);
        when(run.getAgentId()).thenReturn(AGENT_ID);
        when(run.getStatus()).thenReturn(EvalRunStatus.SUCCEEDED);
        when(run.getCreatedAt()).thenReturn(Instant.now());
        // Explicit: Mockito answers 0.0 (not null) for a wrapper Double, which
        // would read as "measured" and trigger the baseline lookup this test
        // deliberately keeps out of scope.
        when(run.getOverallScore()).thenReturn(null);
        when(runService.requireRun(DATASET_ID, runId)).thenReturn(run);
        EvalDatasetCase first = new EvalDatasetCase(DATASET_ID, "u1", null, null, 0);
        EvalDatasetCase second = new EvalDatasetCase(DATASET_ID, "u2", null, null, 1);
        when(runService.listCaseResults(runId))
                .thenReturn(
                        List.of(
                                EvalRunCaseResult.scored(runId, first, "a1", null, null, 0.9, null),
                                EvalRunCaseResult.errored(runId, second, "provider down")));
        when(llmCallLogService.mostRecentTraceIdForSession("eval:" + runId + ":0"))
                .thenReturn("0af7651916cd43dd8448eb211c80319c");
        when(llmCallLogService.mostRecentTraceIdForSession("eval:" + runId + ":1"))
                .thenReturn(null);

        RunDetailResponse configured =
                controller(service(true)).getRun(AGENT_ID, DATASET_ID, runId).getBody();
        assertNotNull(configured);
        CaseResultResponse scored = configured.caseResults().get(0);
        assertEquals("0af7651916cd43dd8448eb211c80319c", scored.traceId());
        assertEquals(
                "http://localhost:3002/trace/0af7651916cd43dd8448eb211c80319c",
                scored.langfuseTraceUrl());
        // The errored replay never reached the model: no call row, no link.
        assertNull(configured.caseResults().get(1).traceId());
        assertNull(configured.caseResults().get(1).langfuseTraceUrl());

        // Same rows, Langfuse not configured: the id still surfaces (it is
        // real and greppable in logs), the link does not.
        RunDetailResponse unconfigured =
                controller(service(false)).getRun(AGENT_ID, DATASET_ID, runId).getBody();
        assertNotNull(unconfigured);
        assertEquals(
                "0af7651916cd43dd8448eb211c80319c", unconfigured.caseResults().get(0).traceId());
        assertNull(unconfigured.caseResults().get(0).langfuseTraceUrl());
        verify(runService, never()).findBaseline(any());
        verify(llmCallLogService, never()).mostRecentTraceIdForSession(eq("eval:" + runId + ":2"));
        verify(runService, never()).listRuns(any(), anyInt());
    }

    // ------------------------------------------------------------------
    // FREE tier with the evals flag on (2026-10-02 research ungating)
    // ------------------------------------------------------------------

    private EvalDatasetController freeController(EvalDatasetService service) {
        return new EvalDatasetController(service, runService, tenantLimits, true);
    }

    @Test
    void freePlanReachesTheServiceWhenFlagOn() {
        asPlan(TenantPlan.FREE);
        EvalDatasetService service = mock(EvalDatasetService.class);
        when(service.listDatasets(AGENT_ID)).thenReturn(List.of());

        assertNotNull(freeController(service).listDatasets(AGENT_ID).getBody());
        verify(service).listDatasets(AGENT_ID);
    }

    @Test
    void freePlanRejectsTheCaseBeyondTheFreeCap() {
        asPlan(TenantPlan.FREE);
        EvalDatasetService service = mock(EvalDatasetService.class);
        when(service.countCases(DATASET_ID))
                .thenReturn((long) EvalDatasetController.FREE_MAX_CASES_PER_DATASET);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        freeController(service)
                                .addCase(
                                        AGENT_ID,
                                        DATASET_ID,
                                        new UpsertCaseRequest("u", "a", null, null)));

        verify(service, never()).addCase(any(), any(), any(), any(), any(), any());
    }

    @Test
    void freePlanRejectsOverCapRepeatsAndBudget() {
        asPlan(TenantPlan.FREE);
        EvalDatasetService service = mock(EvalDatasetService.class);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        freeController(service)
                                .triggerRun(
                                        AGENT_ID,
                                        DATASET_ID,
                                        new TriggerRunRequest(
                                                null,
                                                null,
                                                EvalDatasetController.FREE_MAX_REPEATS + 1,
                                                null),
                                        null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        freeController(service)
                                .triggerRun(
                                        AGENT_ID,
                                        DATASET_ID,
                                        new TriggerRunRequest(
                                                null,
                                                null,
                                                null,
                                                EvalDatasetController.FREE_MAX_BUDGET_USD.add(
                                                        new java.math.BigDecimal("1"))),
                                        null));
        verify(runService, never()).triggerRun(any(), any(), any());
    }

    @Test
    void freeRunGetsTheDefaultBudgetCap() {
        asPlan(TenantPlan.FREE);
        EvalDatasetService service = mock(EvalDatasetService.class);
        EvalRun run = mock(EvalRun.class);
        when(run.getStatus()).thenReturn(EvalRunStatus.QUEUED);
        when(runService.triggerRun(eq(AGENT_ID), eq(DATASET_ID), any())).thenReturn(run);

        freeController(service).triggerRun(AGENT_ID, DATASET_ID, null, null);

        org.mockito.ArgumentCaptor<EvalRunService.RunOptions> captor =
                org.mockito.ArgumentCaptor.forClass(EvalRunService.RunOptions.class);
        verify(runService).triggerRun(eq(AGENT_ID), eq(DATASET_ID), captor.capture());
        assertEquals(
                0,
                EvalDatasetController.FREE_MAX_BUDGET_USD.compareTo(captor.getValue().budgetUsd()));
    }
}
