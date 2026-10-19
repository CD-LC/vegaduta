package com.agenticai.core.quality.dataset;

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
import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Shared fixture for the {@link EvalRunService} unit tests: every collaborator mocked (no DB, no Spring
 * context, no real LLM), in-memory run and result stores so {@code findById} sees whatever {@code save}
 * last wrote, and the "capture the submitted Runnable and run it inline" helpers
 * {@code SimulationRunServiceTest} documents. Subclasses are split by behaviour area.
 */
abstract class EvalRunServiceFixture {

    static final UUID AGENT_ID = UUID.randomUUID();
    static final UUID DATASET_ID = UUID.randomUUID();

    EvalDatasetService datasetService;
    EvalDatasetRepository datasetRepository;
    EvalDatasetCaseRepository caseRepository;
    EvalRunRepository runRepository;
    EvalRunCaseResultRepository caseResultRepository;
    EvalRunExecutor runExecutor;
    EvalEngine evalEngine;
    JudgeChatClientFactory judgeChatClientFactory;
    AgentDefinitionLookup agentDefinitionLookup;
    AgentHeadVersionLookup headVersionLookup;
    AgentTurnReplayer replayer;
    LlmCallLogService llmCallLogService;
    EvalRunService service;

    /** In-memory fakes so findById sees whatever save most recently wrote. */
    final Map<UUID, EvalRun> runStore = new HashMap<>();
    final Map<UUID, EvalRunCaseResult> resultStore = new HashMap<>();

    EvalDataset dataset;

    @BeforeEach
    void setUp() {
        datasetService = mock(EvalDatasetService.class);
        datasetRepository = mock(EvalDatasetRepository.class);
        caseRepository = mock(EvalDatasetCaseRepository.class);
        runRepository = mock(EvalRunRepository.class);
        caseResultRepository = mock(EvalRunCaseResultRepository.class);
        runExecutor = mock(EvalRunExecutor.class);
        evalEngine = mock(EvalEngine.class);
        judgeChatClientFactory = mock(JudgeChatClientFactory.class);
        agentDefinitionLookup = mock(AgentDefinitionLookup.class);
        headVersionLookup = mock(AgentHeadVersionLookup.class);
        replayer = mock(AgentTurnReplayer.class);
        llmCallLogService = mock(LlmCallLogService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmCallLogService> llmLogProvider = mock(ObjectProvider.class);
        when(llmLogProvider.getIfAvailable()).thenReturn(llmCallLogService);

        service = new EvalRunService(
            datasetService, datasetRepository, caseRepository, runRepository,
            caseResultRepository, runExecutor, evalEngine, judgeChatClientFactory,
            agentDefinitionLookup, headVersionLookup, List.of(replayer), llmLogProvider);

        dataset = new EvalDataset(AGENT_ID, "Regression set", null, 0.7, false, "user@example.com");
        ReflectionTestUtils.setField(dataset, "id", DATASET_ID);
        when(datasetService.requireDataset(AGENT_ID, DATASET_ID)).thenReturn(dataset);
        when(datasetService.computeContentHash(DATASET_ID)).thenReturn("hash-abc");
        when(datasetRepository.findById(DATASET_ID)).thenReturn(Optional.of(dataset));
        when(caseRepository.countByDatasetId(DATASET_ID)).thenReturn(1L);
        when(caseRepository.findByDatasetIdOrderByPositionAscCreatedAtAsc(DATASET_ID))
            .thenReturn(List.of(new EvalDatasetCase(DATASET_ID, "hi", "hello", null, 0)));
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

    EvalRunService.RunOptions options(
        String judgeModel, Boolean strictJudge, Integer repeats, BigDecimal budgetUsd, String idempotencyKey
    ) {
        return new EvalRunService.RunOptions(judgeModel, strictJudge, repeats, budgetUsd, idempotencyKey);
    }

    /** Stubs the judge to emit the given overall scores, one per call. */
    void judgeScores(double... scores) {
        org.mockito.stubbing.OngoingStubbing<AggregatedEvaluationResult> stub =
            when(evalEngine.evaluate(any(), any()));
        for (double s : scores) {
            AggregatedEvaluationResult r = new AggregatedEvaluationResult(
                Map.of(EvaluationType.ACCURACY, EvaluationResult.of(EvaluationType.ACCURACY, s, "fb")), s);
            stub = stub.thenReturn(r);
        }
    }

    /** Triggers a run; the CALLER must have stubbed trySubmit (typically with a captor). */
    EvalRun triggerAndCapture(int repeats) {
        return service.triggerRun(AGENT_ID, DATASET_ID, options(null, null, repeats, null, null));
    }

    void graderCase(String spec) {
        EvalDatasetCase c = new EvalDatasetCase(DATASET_ID, "hi", "hello", null, 0);
        c.applyGraderSpec(spec);
        when(caseRepository.findByDatasetIdOrderByPositionAscCreatedAtAsc(DATASET_ID)).thenReturn(List.of(c));
    }

    void replies(String first, String... more) throws Exception {
        org.mockito.stubbing.OngoingStubbing<AgentTurnReplayer.ReplayedTurn> stub =
            when(replayer.replay(eq(AGENT_ID), anyString(), anyString(), anyString(), eq(false), any(Duration.class)))
                .thenReturn(new AgentTurnReplayer.ReplayedTurn(first, List.of()));
        for (String m : more) {
            stub = stub.thenReturn(new AgentTurnReplayer.ReplayedTurn(m, List.of()));
        }
    }

    EvalRun runInline(int repeats) {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        when(runExecutor.trySubmit(captor.capture())).thenReturn(true);
        EvalRun run = triggerAndCapture(repeats);
        captor.getValue().run();
        return runStore.get(run.getId());
    }

    static final String REJECTED =
        "The AI provider rejected our credentials. Check the API key for this provider in Providers settings.";

    /** What EvalEngine returns when every LLM judge call fails: only the local Length heuristic has a number. */
    static AggregatedEvaluationResult judgeDownLengthOnly() {
        Map<EvaluationType, EvaluationResult> results = new HashMap<>();
        results.put(EvaluationType.LENGTH,
            EvaluationResult.of(EvaluationType.LENGTH, 0.25, "Response too brief for the question asked"));
        for (EvaluationType type : EvaluationType.values()) {
            if (type.isLlmJudged()) {
                results.put(type, EvaluationResult.judgeUnavailable(type, REJECTED));
            }
        }
        return new AggregatedEvaluationResult(results, 0.25);
    }
}
