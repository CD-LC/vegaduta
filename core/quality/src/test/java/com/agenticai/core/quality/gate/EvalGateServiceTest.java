package com.agenticai.core.quality.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.quality.dataset.AgentHeadVersionLookup;
import com.agenticai.core.quality.dataset.EvalDataset;
import com.agenticai.core.quality.dataset.EvalDatasetService;
import com.agenticai.core.quality.gate.GateEvaluation.Reason;
import com.agenticai.core.security.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link EvalGateService} with every collaborator mocked (no DB, no Spring
 * context) - the decision matrix the promote hook depends on:
 * <ul>
 *   <li>No gate row / disabled gate - promote unconstrained.</li>
 *   <li>Enabled gate - the run table decides: no run, FAILED, INCONCLUSIVE and
 *       stale-PASSED all block with distinct reasons; only a fresh PASSED run
 *       bound to this exact configuration allows.</li>
 *   <li>Version binding - a run against a DIFFERENT configuration must not
 *       satisfy the promote (the qualifying-run SQL is config-equality
 *       scoped; here "no matching row" models the mismatch).</li>
 *   <li>upsertGate - creates, replaces, validates dataset ownership through
 *       {@code EvalDatasetService#requireDataset}.</li>
 * </ul>
 */
class EvalGateServiceTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID VERSION_ID = UUID.randomUUID();
    private static final UUID DATASET_ID = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();

    private AgentEvalGateRepository gateRepository;
    private EvalDatasetService datasetService;
    private AgentHeadVersionLookup headVersionLookup;
    private EntityManager entityManager;
    private EvalGateService service;

    private Query versionQuery;
    private Query runQuery;
    private Query caseQuery;
    private Query simQuery;

    @BeforeEach
    void setUp() {
        gateRepository = mock(AgentEvalGateRepository.class);
        datasetService = mock(EvalDatasetService.class);
        headVersionLookup = mock(AgentHeadVersionLookup.class);
        entityManager = mock(EntityManager.class);
        service = new EvalGateService(gateRepository, datasetService, headVersionLookup);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);

        // versionBelongsToAgent's SELECT agent_id query - default: version exists
        // and belongs to AGENT_ID. Routing is one thenAnswer, not two argThat
        // stubbings: two argThat matchers on the same method interact with
        // Mockito's last-wins stubbing in ways that made the run query return
        // the version list - a single Answer keeps dispatch explicit.
        versionQuery = mock(Query.class);
        when(versionQuery.setParameter(anyString(), any())).thenReturn(versionQuery);
        when(versionQuery.getResultList()).thenReturn(List.of(AGENT_ID));

        // caseRollouts' eval_run_case_result query - default: no rows.
        caseQuery = mock(Query.class);
        when(caseQuery.setParameter(anyString(), any())).thenReturn(caseQuery);
        when(caseQuery.getResultList()).thenReturn(List.of());

        // latestQualifyingRun's eval_run query - default: no rows.
        runQuery = mock(Query.class);
        when(runQuery.setParameter(anyString(), any())).thenReturn(runQuery);
        when(runQuery.getResultList()).thenReturn(List.of());

        // latestQualifyingSimulation's session aggregate - default: no rows.
        simQuery = mock(Query.class);
        when(simQuery.setParameter(anyString(), any())).thenReturn(simQuery);
        when(simQuery.getResultList()).thenReturn(List.of());

        when(entityManager.createNativeQuery(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.contains("FROM eval_run_case_result")) {
                return caseQuery;
            }
            if (sql.contains("FROM eval_run")) {
                return runQuery;
            }
            if (sql.contains("FROM simulation_session")) {
                return simQuery;
            }
            if (sql.contains("agent_id FROM agent_version")) {
                return versionQuery;
            }
            throw new IllegalArgumentException("unexpected native SQL in test: " + sql);
        });

        // datasetName()'s em.find
        when(entityManager.find(eq(EvalDataset.class), eq(DATASET_ID)))
            .thenReturn(dataset("Regression set"));

        when(gateRepository.save(any(AgentEvalGate.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private static EvalDataset dataset(String name) {
        return new EvalDataset(AGENT_ID, name, null, 0.7, false, "user@example.com");
    }

    private AgentEvalGate enabledGate(Integer maxRunAgeHours) {
        AgentEvalGate gate = new AgentEvalGate(AGENT_ID, DATASET_ID, true, maxRunAgeHours, "user@example.com");
        when(gateRepository.findByAgentId(AGENT_ID)).thenReturn(Optional.of(gate));
        return gate;
    }

    /** A gate that also requires staging: latest judged sim session must be all-PASSED. */
    private AgentEvalGate simGate(Integer maxSimulationAgeHours) {
        AgentEvalGate gate = new AgentEvalGate(
            AGENT_ID, DATASET_ID, true, null, true, maxSimulationAgeHours, "user@example.com");
        when(gateRepository.findByAgentId(AGENT_ID)).thenReturn(Optional.of(gate));
        return gate;
    }

    /** Latest judged sim session for the candidate config: {sessionId, passed, failed, inconclusive, lastCompletedAt}. */
    private void latestSim(int passed, int failed, int inconclusive, Instant lastCompletedAt) {
        when(simQuery.getResultList()).thenReturn(List.<Object[]>of(
            new Object[]{UUID.randomUUID(), (long) passed, (long) failed, (long) inconclusive,
                lastCompletedAt == null ? null : Timestamp.from(lastCompletedAt)}));
    }

    private void latestRun(String verdict, Instant finishedAt) {
        // List.<Object[]>of: the explicit type witness matters - bare
        // List.of(array) spreads the array as the varargs and returns a
        // 4-element List<Object>, which is exactly the CCE this helper had.
        when(runQuery.getResultList()).thenReturn(List.<Object[]>of(
            new Object[]{RUN_ID, verdict, new BigDecimal("0.82"),
                finishedAt == null ? null : Timestamp.from(finishedAt)}));
    }

    // ------------------------------------------------------------------
    // The two "no gate" answers
    // ------------------------------------------------------------------

    @Test
    void noGateRowAllowsPromotionUnconstrained() {
        when(gateRepository.findByAgentId(AGENT_ID)).thenReturn(Optional.empty());

        GateEvaluation eval = service.evaluate(AGENT_ID, VERSION_ID);

        assertTrue(eval.allowed());
        assertFalse(eval.configured());
        assertEquals(Reason.NO_GATE, eval.reason());
        // Short-circuit: no version or run queries should have run at all.
        verify(entityManager, never()).createNativeQuery(anyString());
    }

    @Test
    void disabledGateAllowsPromotion() {
        AgentEvalGate gate = enabledGate(null);
        gate.update(DATASET_ID, false, null, "user@example.com");

        GateEvaluation eval = service.evaluate(AGENT_ID, VERSION_ID);

        assertTrue(eval.allowed());
        assertTrue(eval.configured());
        assertFalse(eval.enabled());
        assertEquals(Reason.GATE_DISABLED, eval.reason());
        verify(entityManager, never()).createNativeQuery(anyString());
    }

    // ------------------------------------------------------------------
    // Enabled gate - every block reason
    // ------------------------------------------------------------------

    @Test
    void foreignVersionIsBlocked() {
        enabledGate(null);
        when(versionQuery.getResultList()).thenReturn(List.of(UUID.randomUUID()));

        GateEvaluation eval = service.evaluate(AGENT_ID, VERSION_ID);

        assertFalse(eval.allowed());
        assertEquals(Reason.VERSION_NOT_FOUND, eval.reason());
    }

    @Test
    void noRunAtAllIsBlocked() {
        enabledGate(null);

        GateEvaluation eval = service.evaluate(AGENT_ID, VERSION_ID);

        assertFalse(eval.allowed());
        assertEquals(Reason.NO_QUALIFYING_RUN, eval.reason());
        assertEquals(DATASET_ID, eval.datasetId());
    }

    @Test
    void failedVerdictBlocks() {
        enabledGate(null);
        latestRun("FAILED", Instant.now().minus(Duration.ofMinutes(5)));

        GateEvaluation eval = service.evaluate(AGENT_ID, VERSION_ID);

        assertFalse(eval.allowed());
        assertEquals(Reason.RUN_FAILED, eval.reason());
        assertEquals(RUN_ID, eval.qualifyingRunId());
        assertEquals(0.82, eval.overallScore());
    }

    @Test
    void inconclusiveVerdictBlocks() {
        enabledGate(null);
        latestRun("INCONCLUSIVE", Instant.now().minus(Duration.ofMinutes(5)));

        GateEvaluation eval = service.evaluate(AGENT_ID, VERSION_ID);

        assertFalse(eval.allowed());
        assertEquals(Reason.RUN_INCONCLUSIVE, eval.reason());
    }

    @Test
    void stalePassBlocksWhenFreshnessWindowSet() {
        enabledGate(24);
        latestRun("PASSED", Instant.now().minus(Duration.ofHours(48)));

        GateEvaluation eval = service.evaluate(AGENT_ID, VERSION_ID);

        assertFalse(eval.allowed());
        assertEquals(Reason.RUN_STALE, eval.reason());
        assertEquals("PASSED", eval.verdict());
    }

    // ------------------------------------------------------------------
    // The one "yes"
    // ------------------------------------------------------------------

    @Test
    void freshPassedRunOnThisConfigAllows() {
        enabledGate(24);
        latestRun("PASSED", Instant.now().minus(Duration.ofHours(1)));

        GateEvaluation eval = service.evaluate(AGENT_ID, VERSION_ID);

        assertTrue(eval.allowed());
        assertEquals(Reason.PASSING_RUN, eval.reason());
        assertEquals(RUN_ID, eval.qualifyingRunId());
        assertEquals("PASSED", eval.verdict());
        assertEquals("Regression set", eval.datasetName());
    }

    @Test
    void passedRunOfAnyAgeAllowsWhenNoWindow() {
        enabledGate(null);
        latestRun("PASSED", Instant.now().minus(Duration.ofDays(90)));

        GateEvaluation eval = service.evaluate(AGENT_ID, VERSION_ID);

        assertTrue(eval.allowed());
        assertEquals(Reason.PASSING_RUN, eval.reason());
    }

    /**
     * A PASSED run that tested a DIFFERENT configuration cannot promote this
     * version - the qualifying-run SQL scopes by config_json equality, so a
     * mismatched run simply yields no row (same result as "no run at all").
     */
    @Test
    void passOnDifferentConfigDoesNotQualify() {
        enabledGate(null);
        // runQuery stays empty - models "runs exist but none bound to a
        // version whose config_json equals this version's".
        GateEvaluation eval = service.evaluate(AGENT_ID, VERSION_ID);

        assertFalse(eval.allowed());
        assertEquals(Reason.NO_QUALIFYING_RUN, eval.reason());
    }

    // ------------------------------------------------------------------
    // Dataset-hash staleness (V343)
    // ------------------------------------------------------------------

    /**
     * A SUCCEEDED run on this config whose {@code dataset_hash} no longer
     * matches the dataset's CURRENT hash is not qualifying evidence - the
     * dataset was edited after the run, so the block reason is
     * DATASET_STALE (distinct from NO_QUALIFYING_RUN: same remedy - re-run -
     * but a different diagnosis). Modeled by the qualifying query returning
     * no row while {@code anyRunForConfig}'s COUNT(*) returns 1.
     */
    @Test
    void editedDatasetMakesTheLastRunStale() {
        enabledGate(null);
        when(runQuery.getSingleResult()).thenReturn(1L); // a run for this config exists
        when(datasetService.computeContentHash(DATASET_ID)).thenReturn("hash-after-edit");

        GateEvaluation eval = service.evaluate(AGENT_ID, VERSION_ID);

        assertFalse(eval.allowed());
        assertEquals(Reason.DATASET_STALE, eval.reason());
        // The qualifying query was asked to match the CURRENT hash.
        verify(runQuery).setParameter("datasetHash", "hash-after-edit");
    }

    @Test
    void noRunsAtAllOnThisConfigIsNotStale() {
        enabledGate(null);
        when(runQuery.getSingleResult()).thenReturn(0L);

        GateEvaluation eval = service.evaluate(AGENT_ID, VERSION_ID);

        assertFalse(eval.allowed());
        assertEquals(Reason.NO_QUALIFYING_RUN, eval.reason());
    }

    // ------------------------------------------------------------------
    // Simulation staging leg (V394)
    // ------------------------------------------------------------------

    @Test
    void simRequirementIsOffByDefault() {
        // requireSimulation=false: a passing eval run alone promotes, and the
        // sim query is never asked.
        enabledGate(null);
        latestRun("PASSED", Instant.now());

        GateEvaluation eval = service.evaluate(AGENT_ID, VERSION_ID);

        assertTrue(eval.allowed());
        assertFalse(eval.simulationRequired());
        verify(simQuery, never()).getResultList();
    }

    @Test
    void simRequiredButNoSessionBlocks() {
        simGate(null);
        latestRun("PASSED", Instant.now());

        GateEvaluation eval = service.evaluate(AGENT_ID, VERSION_ID);

        assertFalse(eval.allowed());
        assertEquals(Reason.SIM_NO_QUALIFYING_RUN, eval.reason());
        assertTrue(eval.simulationRequired());
        // The eval leg's evidence stays visible in the report.
        assertEquals(RUN_ID, eval.qualifyingRunId());
    }

    @Test
    void simRequiredAndAllPassedAllows() {
        simGate(null);
        latestRun("PASSED", Instant.now());
        Instant simEnd = Instant.now().minus(Duration.ofMinutes(10));
        latestSim(3, 0, 0, simEnd);

        GateEvaluation eval = service.evaluate(AGENT_ID, VERSION_ID);

        assertTrue(eval.allowed());
        assertEquals(Reason.PASSING_RUN, eval.reason());
        assertEquals("PASSED", eval.simulationVerdict());
        assertEquals(simEnd, eval.simulationFinishedAt());
    }

    @Test
    void simSessionWithAnyFailedPersonaBlocks() {
        simGate(null);
        latestRun("PASSED", Instant.now());
        latestSim(2, 1, 0, Instant.now());

        GateEvaluation eval = service.evaluate(AGENT_ID, VERSION_ID);

        assertFalse(eval.allowed());
        assertEquals(Reason.SIM_FAILED, eval.reason());
        assertEquals("FAILED", eval.simulationVerdict());
    }

    @Test
    void simSessionWithInconclusivePersonaBlocks() {
        // Honest absence: no failures but one persona judged INCONCLUSIVE -
        // the session failed to confirm the candidate held up.
        simGate(null);
        latestRun("PASSED", Instant.now());
        latestSim(2, 0, 1, Instant.now());

        GateEvaluation eval = service.evaluate(AGENT_ID, VERSION_ID);

        assertFalse(eval.allowed());
        assertEquals(Reason.SIM_INCONCLUSIVE, eval.reason());
    }

    @Test
    void staleSimSessionBlocksWhenFreshnessWindowSet() {
        simGate(24);
        latestRun("PASSED", Instant.now());
        latestSim(3, 0, 0, Instant.now().minus(Duration.ofHours(48)));

        GateEvaluation eval = service.evaluate(AGENT_ID, VERSION_ID);

        assertFalse(eval.allowed());
        assertEquals(Reason.SIM_STALE, eval.reason());
    }

    @Test
    void freshSimInsideWindowPasses() {
        simGate(24);
        latestRun("PASSED", Instant.now());
        latestSim(1, 0, 0, Instant.now().minus(Duration.ofHours(2)));

        GateEvaluation eval = service.evaluate(AGENT_ID, VERSION_ID);

        assertTrue(eval.allowed());
    }

    // ------------------------------------------------------------------
    // evaluateHead
    // ------------------------------------------------------------------

    @Test
    void evaluateHeadIsEmptyWhenUnversioned() {
        when(headVersionLookup.findHeadVersionId(AGENT_ID)).thenReturn(Optional.empty());
        assertTrue(service.evaluateHead(AGENT_ID).isEmpty());
    }

    @Test
    void evaluateHeadEvaluatesTheHeadRow() {
        enabledGate(null);
        latestRun("PASSED", Instant.now());
        when(headVersionLookup.findHeadVersionId(AGENT_ID)).thenReturn(Optional.of(VERSION_ID));

        Optional<GateEvaluation> eval = service.evaluateHead(AGENT_ID);

        assertTrue(eval.isPresent());
        assertEquals(VERSION_ID, eval.get().versionId());
        assertTrue(eval.get().allowed());
    }

    // ------------------------------------------------------------------
    // upsertGate
    // ------------------------------------------------------------------

    @Test
    void upsertCreatesGateForOwnedDataset() {
        TenantContext.set(TenantContext.of("tenant-1", "admin@example.com", Set.of()));
        EvalDataset ds = mock(EvalDataset.class);
        when(ds.getId()).thenReturn(DATASET_ID);
        when(datasetService.requireDataset(AGENT_ID, DATASET_ID)).thenReturn(ds);
        when(gateRepository.findByAgentId(AGENT_ID)).thenReturn(Optional.empty());

        AgentEvalGate gate = service.upsertGate(AGENT_ID, DATASET_ID, true, 12);

        assertEquals(AGENT_ID, gate.getAgentId());
        assertEquals(DATASET_ID, gate.getDatasetId());
        assertTrue(gate.isEnabled());
        assertEquals(12, gate.getMaxRunAgeHours());
        verify(datasetService).requireDataset(AGENT_ID, DATASET_ID);
        verify(gateRepository).save(any(AgentEvalGate.class));
    }

    @Test
    void upsertReplacesExistingGateKeepingRow() {
        TenantContext.set(TenantContext.of("tenant-1", "admin@example.com", Set.of()));
        AgentEvalGate existing = enabledGate(6);
        EvalDataset ds = mock(EvalDataset.class);
        when(ds.getId()).thenReturn(DATASET_ID);
        when(datasetService.requireDataset(AGENT_ID, DATASET_ID)).thenReturn(ds);

        AgentEvalGate gate = service.upsertGate(AGENT_ID, DATASET_ID, false, null);

        // Same row mutated, not a second row.
        assertEquals(existing, gate);
        assertFalse(gate.isEnabled());
        assertEquals(null, gate.getMaxRunAgeHours());
    }

    @Test
    void upsertRejectsNonPositiveWindow() {
        assertThrows(IllegalArgumentException.class,
            () -> service.upsertGate(AGENT_ID, DATASET_ID, true, 0));
    }

    @Test
    void deleteReportsWhetherAGateExisted() {
        when(gateRepository.findByAgentId(AGENT_ID)).thenReturn(Optional.empty());
        assertFalse(service.deleteGate(AGENT_ID));

        AgentEvalGate gate = enabledGate(null);
        assertTrue(service.deleteGate(AGENT_ID));
        verify(gateRepository).delete(gate);
    }

    // ------------------------------------------------------------------
    // pass^k-aware promotion (repeats > 1)
    // ------------------------------------------------------------------

    private void repeatedPassedRun(int repeats, List<Object> scoresJsonPerCase) {
        when(runQuery.getResultList()).thenReturn(List.<Object[]>of(
            new Object[]{RUN_ID, "PASSED", new BigDecimal("0.90"), Timestamp.from(Instant.now()), repeats}));
        List<Object> rows = new java.util.ArrayList<>();
        for (Object json : scoresJsonPerCase) {
            rows.add(new Object[]{"SCORED", json});
        }
        when(caseQuery.getResultList()).thenReturn(rows);
    }

    private static String repeatsJson(double passFraction) {
        return "{\"REPEATS\":{\"score\":" + passFraction + ",\"status\":\"EVALUATED\"}}";
    }

    @Test
    void passHatKDisabledByDefaultKeepsMeanVerdictForRepeatedRuns() {
        enabledGate(null);
        repeatedPassedRun(3, List.of(repeatsJson(0.3333333333333333), repeatsJson(0.0)));

        assertTrue(service.evaluate(AGENT_ID, VERSION_ID).allowed());
    }

    @Test
    void passHatKEnabledBlocksFlakyRepeatedRun() {
        ReflectionTestUtils.setField(service, "passHatKEnabled", true);
        enabledGate(null);
        // 4 cases x 3 repeats; only one case passed every repetition.
        repeatedPassedRun(3, List.of(
            repeatsJson(1.0), repeatsJson(0.6666666666666666),
            repeatsJson(0.6666666666666666), repeatsJson(0.3333333333333333)));

        GateEvaluation eval = service.evaluate(AGENT_ID, VERSION_ID);

        assertFalse(eval.allowed());
        assertEquals(Reason.RUN_FAILED, eval.reason());
        assertTrue(eval.message().contains("1 of 4 cases passed all 3 repetitions"));
    }

    @Test
    void passHatKEnabledAllowsFullyReliableRepeatedRun() {
        ReflectionTestUtils.setField(service, "passHatKEnabled", true);
        enabledGate(null);
        // 10 of 10 cases all-pass: Wilson lower bound ~0.72 >= 0.5.
        repeatedPassedRun(3, java.util.Collections.nCopies(10, (Object) repeatsJson(1.0)));

        assertTrue(service.evaluate(AGENT_ID, VERSION_ID).allowed());
    }

    @Test
    void passHatKIgnoredForSingleRepetitionRuns() {
        ReflectionTestUtils.setField(service, "passHatKEnabled", true);
        enabledGate(null);
        latestRun("PASSED", Instant.now()); // 4-column row => repeats defaults to 1

        assertTrue(service.evaluate(AGENT_ID, VERSION_ID).allowed());
        verify(caseQuery, never()).getResultList();
    }

    @Test
    void passHatKCountsErroredAndUnparseableCasesAsFailures() {
        ReflectionTestUtils.setField(service, "passHatKEnabled", true);
        enabledGate(null);
        when(runQuery.getResultList()).thenReturn(List.<Object[]>of(
            new Object[]{RUN_ID, "PASSED", new BigDecimal("0.90"), Timestamp.from(Instant.now()), 3}));
        when(caseQuery.getResultList()).thenReturn(List.<Object>of(
            new Object[]{"SCORED", repeatsJson(1.0)},
            new Object[]{"ERROR", null},
            new Object[]{"SCORED", "{not json"},
            new Object[]{"SCORED", "{\"OTHER\":{}}"}));

        List<PassHatKGate.CaseRollouts> rollouts = service.caseRollouts(RUN_ID, 3);

        assertEquals(4, rollouts.size());
        assertEquals(3, rollouts.get(0).passes());
        assertEquals(0, rollouts.get(1).passes());
        assertEquals(0, rollouts.get(2).passes());
        assertEquals(0, rollouts.get(3).passes());
        assertFalse(service.evaluate(AGENT_ID, VERSION_ID).allowed());
    }

    @Test
    void passHatKBlocksWhenNoCaseRowsExistForARepeatedRun() {
        ReflectionTestUtils.setField(service, "passHatKEnabled", true);
        enabledGate(null);
        repeatedPassedRun(3, List.of());

        GateEvaluation eval = service.evaluate(AGENT_ID, VERSION_ID);

        assertFalse(eval.allowed());
        assertEquals(Reason.RUN_FAILED, eval.reason());
    }
}
