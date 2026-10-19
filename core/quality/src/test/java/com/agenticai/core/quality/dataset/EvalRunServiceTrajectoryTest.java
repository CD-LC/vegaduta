package com.agenticai.core.quality.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.chat.LlmCallLogService;
import com.agenticai.core.quality.AggregatedEvaluationResult;
import com.agenticai.core.quality.EvalEngine;
import com.agenticai.core.quality.EvaluationResult;
import com.agenticai.core.quality.EvaluationType;
import com.agenticai.core.quality.impl.JudgeChatClientFactory;
import com.agenticai.core.security.TenantContext;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Trajectory scoring inside {@link EvalRunService}: a case whose {@code grader_spec} is a trajectory spec scores
 * the replayed turn's tool calls, joins the grader vote, and blends into the overall score - but only behind
 * {@code app.quality.trajectory-eval.enabled}, and only for tool-executing datasets.
 */
class EvalRunServiceTrajectoryTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID DATASET_ID = UUID.randomUUID();

    private static final String SPEC = "{\"type\":\"trajectory\",\"mode\":\"in_order\",\"steps\":["
        + "{\"tool\":\"search\",\"args\":{\"query\":{\"regex\":\"refund\",\"ignoreCase\":true}}},{\"tool\":\"send_email\"}]}";

    private EvalRunService service;
    private AgentTurnReplayer replayer;
    private EvalEngine evalEngine;
    private EvalRunExecutor runExecutor;
    private EvalDatasetCaseRepository caseRepository;
    private final Map<UUID, EvalRun> runStore = new HashMap<>();
    private final Map<UUID, EvalRunCaseResult> resultStore = new HashMap<>();

    @BeforeEach
    void setUp() {
        EvalDatasetService datasetService = mock(EvalDatasetService.class);
        EvalDatasetRepository datasetRepository = mock(EvalDatasetRepository.class);
        caseRepository = mock(EvalDatasetCaseRepository.class);
        EvalRunRepository runRepository = mock(EvalRunRepository.class);
        EvalRunCaseResultRepository caseResultRepository = mock(EvalRunCaseResultRepository.class);
        runExecutor = mock(EvalRunExecutor.class);
        evalEngine = mock(EvalEngine.class);
        JudgeChatClientFactory judgeChatClientFactory = mock(JudgeChatClientFactory.class);
        AgentDefinitionLookup agentDefinitionLookup = mock(AgentDefinitionLookup.class);
        AgentHeadVersionLookup headVersionLookup = mock(AgentHeadVersionLookup.class);
        replayer = mock(AgentTurnReplayer.class);
        LlmCallLogService llmCallLogService = mock(LlmCallLogService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmCallLogService> llmLogProvider = mock(ObjectProvider.class);
        when(llmLogProvider.getIfAvailable()).thenReturn(llmCallLogService);

        service = new EvalRunService(
            datasetService, datasetRepository, caseRepository, runRepository,
            caseResultRepository, runExecutor, evalEngine, judgeChatClientFactory,
            agentDefinitionLookup, headVersionLookup, List.of(replayer), llmLogProvider);

        useDataset(true);
        when(datasetService.requireDataset(AGENT_ID, DATASET_ID)).thenAnswer(inv -> currentDataset);
        when(datasetService.computeContentHash(DATASET_ID)).thenReturn("hash-abc");
        when(datasetRepository.findById(DATASET_ID)).thenAnswer(inv -> Optional.of(currentDataset));
        when(caseRepository.countByDatasetId(DATASET_ID)).thenReturn(1L);
        when(agentDefinitionLookup.find(AGENT_ID)).thenReturn(
            Optional.of(new AgentDefinitionLookup.AgentDefinition(AGENT_ID, "Target", "prompt", "gpt-agent")));
        when(headVersionLookup.findHeadVersionId(AGENT_ID)).thenReturn(Optional.empty());
        when(judgeChatClientFactory.resolveJudgeModelIdOrNull()).thenReturn("OPENAI/gpt-judge");
        when(judgeChatClientFactory.judgeModelScope(any())).thenReturn(() -> { });
        when(llmCallLogService.forTask(anyString())).thenReturn(List.of());

        when(runRepository.save(any(EvalRun.class))).thenAnswer(inv -> {
            EvalRun r = inv.getArgument(0);
            if (r.getId() == null) {
                ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
            }
            runStore.put(r.getId(), r);
            return r;
        });
        when(runRepository.findById(any())).thenAnswer(inv -> Optional.ofNullable(runStore.get(inv.getArgument(0))));
        when(runRepository.findByDatasetIdAndIdempotencyKey(any(), anyString())).thenReturn(Optional.empty());
        when(caseResultRepository.save(any(EvalRunCaseResult.class))).thenAnswer(inv -> {
            EvalRunCaseResult r = inv.getArgument(0);
            resultStore.put(UUID.randomUUID(), r);
            return r;
        });
        TenantContext.set(TenantContext.of("tenant-1", "tester", Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private EvalDataset currentDataset;

    private void useDataset(boolean toolExecution) {
        currentDataset = new EvalDataset(AGENT_ID, "Regression set", null, 0.7, toolExecution, "user@example.com");
        ReflectionTestUtils.setField(currentDataset, "id", DATASET_ID);
    }

    private void caseWithSpec(String spec) {
        EvalDatasetCase c = new EvalDatasetCase(DATASET_ID, "refund my order", "ok", null, 0);
        c.applyGraderSpec(spec);
        when(caseRepository.findByDatasetIdOrderByPositionAscCreatedAtAsc(DATASET_ID)).thenReturn(List.of(c));
    }

    private void judge(double score) {
        when(evalEngine.evaluate(any(), any())).thenReturn(new AggregatedEvaluationResult(
            Map.of(EvaluationType.ACCURACY, EvaluationResult.of(EvaluationType.ACCURACY, score, "fb")), score));
    }

    private void calls(boolean executeTools, AgentTurnReplayer.ReplayedToolCall... toolCalls) throws Exception {
        when(replayer.replay(eq(AGENT_ID), anyString(), anyString(), anyString(), eq(executeTools), any(Duration.class)))
            .thenReturn(new AgentTurnReplayer.ReplayedTurn("done", List.of(toolCalls)));
    }

    private static AgentTurnReplayer.ReplayedToolCall call(String tool, String input) {
        return new AgentTurnReplayer.ReplayedToolCall(tool, input, "ok");
    }

    private EvalRun runInline() {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        when(runExecutor.trySubmit(captor.capture())).thenReturn(true);
        EvalRun run = service.triggerRun(AGENT_ID, DATASET_ID,
            new EvalRunService.RunOptions(null, null, 1, null, null));
        captor.getValue().run();
        return runStore.get(run.getId());
    }

    @Test
    void aMatchingTrajectoryPassesStoresItsDimensionAndBlendsIntoTheOverallScore() throws Exception {
        service.trajectoryEvalEnabled = true;
        caseWithSpec(SPEC);
        judge(0.8);
        calls(true, call("search", "{\"query\":\"Refund policy\"}"), call("send_email", "{\"to\":\"a@b.c\"}"));

        EvalRun stored = runInline();

        EvalRunCaseResult result = resultStore.values().iterator().next();
        assertEquals(Boolean.TRUE, result.getPassed());
        assertEquals(0, stored.getFailedAssertionCount());
        // Judge 0.8 and trajectory 1.0 count equally.
        assertEquals(0.9, result.getOverallScore(), 1e-6);
        assertTrue(result.getScoresJson().contains("\"TRAJECTORY\""), result.getScoresJson());
        assertTrue(result.getScoresJson().contains("\"report\""), result.getScoresJson());
        assertFalse(result.getScoresJson().contains(EvalRunService.CODE_GRADER_DIMENSION));
    }

    @Test
    void aMissingStepFailsTheCaseEvenWhenTheJudgeScoresHigh() throws Exception {
        service.trajectoryEvalEnabled = true;
        caseWithSpec(SPEC);
        judge(0.95);
        calls(true, call("search", "{\"query\":\"refund\"}"));

        EvalRun stored = runInline();

        EvalRunCaseResult result = resultStore.values().iterator().next();
        assertEquals(Boolean.FALSE, result.getPassed());
        assertEquals(1, stored.getFailedAssertionCount());
        assertTrue(result.getScoresJson().contains("TRAJECTORY"));
    }

    @Test
    void withNoJudgeScoreTheTrajectoryScoreIsTheCaseScore() throws Exception {
        service.trajectoryEvalEnabled = true;
        caseWithSpec("{\"type\":\"trajectory\",\"mode\":\"in_order\",\"minScore\":0.5,\"steps\":["
            + "{\"tool\":\"a\"},{\"tool\":\"b\"}]}");
        when(evalEngine.evaluate(any(), any())).thenReturn(new AggregatedEvaluationResult(Map.of(), null));
        calls(true, call("a", "{}"));

        runInline();

        EvalRunCaseResult result = resultStore.values().iterator().next();
        assertNotNull(result.getOverallScore());
        assertEquals(0.5, result.getOverallScore(), 1e-6);
        // Score 0.5 meets minScore 0.5, so the grader passes; the overall 0.5 is below the dataset bar of 0.7.
        assertEquals(Boolean.FALSE, result.getPassed());
    }

    @Test
    void withTheFlagOffATrajectorySpecIsIgnoredExactlyAsBefore() throws Exception {
        service.trajectoryEvalEnabled = false;
        caseWithSpec(SPEC);
        judge(0.8);
        calls(true, call("search", "{\"query\":\"x\"}"));

        runInline();

        EvalRunCaseResult result = resultStore.values().iterator().next();
        assertEquals(Boolean.TRUE, result.getPassed());
        assertEquals(0.8, result.getOverallScore(), 1e-6);
        assertFalse(result.getScoresJson().contains("TRAJECTORY"));
        assertFalse(result.getScoresJson().contains(EvalRunService.CODE_GRADER_DIMENSION));
    }

    @Test
    void aToollessDatasetCannotEvaluateATrajectoryAndSaysNothing() throws Exception {
        service.trajectoryEvalEnabled = true;
        useDataset(false);
        caseWithSpec(SPEC);
        judge(0.8);
        calls(false);

        runInline();

        EvalRunCaseResult result = resultStore.values().iterator().next();
        assertEquals(Boolean.TRUE, result.getPassed());
        assertEquals(0.8, result.getOverallScore(), 1e-6);
        assertFalse(result.getScoresJson().contains("TRAJECTORY"));
    }

    @Test
    void aCorruptTrajectorySpecDoesNotFailOrPassTheCase() throws Exception {
        service.trajectoryEvalEnabled = true;
        caseWithSpec("{\"type\":\"trajectory\",\"mode\":\"sideways\",\"steps\":[]}");
        judge(0.9);
        calls(true, call("a", "{}"));

        runInline();

        EvalRunCaseResult result = resultStore.values().iterator().next();
        assertEquals(Boolean.TRUE, result.getPassed());
        assertFalse(result.getScoresJson().contains("TRAJECTORY"));
    }
}
