package com.agenticai.core.quality.dataset;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.quality.AggregatedEvaluationResult;
import com.agenticai.core.quality.EvalEngine;
import com.agenticai.core.quality.EvaluationContext;
import com.agenticai.core.quality.EvaluationResult;
import com.agenticai.core.quality.EvaluationType;
import com.agenticai.core.quality.grader.CodeGrader;
import com.agenticai.core.quality.trajectory.TrajectoryCaseGrader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Executes ONE dataset case of an eval run: {@code repeats} (replay -> judge) repetitions, the
 * strict-majority resolution into one persisted result row, and the per-repetition code/trajectory
 * grading. Extracted from {@link EvalRunService}, which keeps trigger, cancel, the run loop, budget
 * and the verdict. Not a Spring bean: {@link EvalRunService} owns it, so its public constructor and
 * the feature flags it reads stay where tests and config set them.
 */
final class EvalCaseExecutor {

    private static final Logger log = LoggerFactory.getLogger(EvalCaseExecutor.class);

    /**
     * Per-case replay budget. Generous because a tool-enabled replay can legitimately chain several
     * tool calls; bounded because one wedged provider must not hold a worker thread hostage for a
     * whole run.
     */
    private static final Duration CASE_REPLAY_TIMEOUT = Duration.ofSeconds(180);

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final AgentTurnReplayer replayer;
    private final EvalEngine evalEngine;
    private final EvalRunCaseResultRepository caseResultRepository;
    private final BooleanSupplier codeGradersEnabled;
    private final BooleanSupplier trajectoryEvalEnabled;

    EvalCaseExecutor(
            AgentTurnReplayer replayer,
            EvalEngine evalEngine,
            EvalRunCaseResultRepository caseResultRepository,
            BooleanSupplier codeGradersEnabled,
            BooleanSupplier trajectoryEvalEnabled) {
        this.replayer = replayer;
        this.evalEngine = evalEngine;
        this.caseResultRepository = caseResultRepository;
        this.codeGradersEnabled = codeGradersEnabled;
        this.trajectoryEvalEnabled = trajectoryEvalEnabled;
    }

    /**
     * One case: {@code repeats} (replay -> judge) repetitions, then ONE persisted result row
     * carrying the majority-resolved outcome (V343). Never throws (per-case isolation - one bad
     * case must not eat the rest of the run, the same per-item try/catch shape as {@code
     * AgentJobRunner}'s ticks).
     *
     * <h2>Majority pass</h2>
     *
     * A repetition passes when it did not error, no expected-tools assertion was violated, and its
     * aggregate score met the run's pass bar. The CASE's recorded answer is the strict majority of
     * its repetitions: {@code passed=TRUE} iff more than half the repetitions passed. Deterministic
     * single-repetition runs are the degenerate k=1 case of exactly the same rule.
     *
     * <p>The persisted row keeps the LAST non-errored repetition's response and per-dimension
     * scores (a representative transcript for review), plus a {@code REPEATS} pseudo-dimension
     * recording {@code passes}/{@code repetitions} so a run detail explains WHY a flaky case
     * resolved the way it did.
     */
    CaseOutcome executeCase(
            EvalRun run,
            EvalDataset dataset,
            AgentDefinitionLookup.AgentDefinition agent,
            EvalDatasetCase datasetCase,
            List<EvaluationType> enabledTypes,
            int index,
            int repeats) {
        List<Repetition> repetitions = new ArrayList<>(repeats);
        for (int r = 0; r < repeats; r++) {
            // Fresh session per repetition so memory never bleeds between
            // them - each replay must be judged cold. Repetition 0 keeps the
            // historical "eval:<runId>:<index>" session id so pre-existing
            // trace-link lookup (EvalDatasetService#traceIdForRunCase) is
            // unaffected; further repetitions get a ":r" suffix.
            String sessionId =
                    r == 0
                            ? "eval:" + run.getId() + ":" + index
                            : "eval:" + run.getId() + ":" + index + ":" + r;
            repetitions.add(
                    replayAndJudge(run, dataset, agent, datasetCase, enabledTypes, sessionId));
        }

        int passVotes = 0;
        int evaluable = 0;
        int toolsTrue = 0;
        int toolsFalse = 0;
        int graderTrue = 0;
        int graderFalse = 0;
        double repScoreSum = 0.0;
        int scoredReps = 0;
        for (Repetition rep : repetitions) {
            if (rep.errored()) {
                continue;
            }
            if (rep.overallScore() != null
                    || rep.toolsSatisfied() != null
                    || rep.graderPassed() != null) {
                evaluable++;
            }
            if (Boolean.TRUE.equals(rep.graderPassed())) {
                graderTrue++;
            }
            if (Boolean.FALSE.equals(rep.graderPassed())) {
                graderFalse++;
            }
            if (rep.overallScore() != null) {
                scoredReps++;
                repScoreSum += rep.overallScore();
            }
            if (Boolean.TRUE.equals(rep.toolsSatisfied())) {
                toolsTrue++;
            }
            if (Boolean.FALSE.equals(rep.toolsSatisfied())) {
                toolsFalse++;
            }
            if (repetitionPassed(rep, run.getPassThreshold())) {
                passVotes++;
            }
        }

        int n = repetitions.size();
        boolean majorityPass = passVotes * 2 > n;
        boolean allErrored = repetitions.stream().allMatch(Repetition::errored);

        if (allErrored) {
            String error = repetitions.get(n - 1).error();
            caseResultRepository.save(EvalRunCaseResult.errored(run.getId(), datasetCase, error));
            return new CaseOutcome(true, null, null, null, null, null);
        }

        // The judge was down for this case when every repetition that replayed
        // at all hit the outage.
        String judgeOutage =
                repetitions.stream()
                                .filter(r -> !r.errored())
                                .allMatch(r -> r.judgeOutage() != null)
                        ? repetitions.stream()
                                .filter(r -> !r.errored())
                                .map(Repetition::judgeOutage)
                                .findFirst()
                                .orElse(null)
                        : null;

        Repetition representative =
                repetitions.stream()
                        .filter(r -> !r.errored())
                        .reduce((first, second) -> second) // last non-errored rep
                        .orElse(repetitions.get(n - 1));

        Double mean = scoredReps > 0 ? repScoreSum / scoredReps : null;
        // Code grader (V375): same strict-majority vote as the tool assertion.
        Boolean graderSatisfied =
                graderFalse * 2 > n ? Boolean.FALSE : (graderTrue * 2 > n ? Boolean.TRUE : null);
        if (mean == null && graderSatisfied != null) {
            // No judge score (e.g. judge unavailable), but a deterministic grader ran: its verdict
            // IS a
            // measurement (1.0 pass / 0.0 fail), so the case is scored rather than UNAVAILABLE.
            mean = graderSatisfied ? 1.0 : 0.0;
        }
        // Majority vote on the tool assertion: violated only when a strict
        // majority of repetitions violated it - a single flaky rep must not
        // mark the case failed, and (symmetric) must not mark it satisfied.
        Boolean toolsSatisfied =
                toolsFalse * 2 > n ? Boolean.FALSE : (toolsTrue * 2 > n ? Boolean.TRUE : null);
        // Three-valued: FALSE only when at least one repetition produced
        // anything evaluable; all-unmeasurable stays NULL, never a fake fail.
        Boolean passed = majorityPass ? Boolean.TRUE : (evaluable > 0 ? Boolean.FALSE : null);

        Map<String, Object> scores = new LinkedHashMap<>(representative.scores());
        if (repeats > 1) {
            Map<String, Object> rep = new HashMap<>();
            rep.put("score", n == 0 ? null : (double) passVotes / n);
            rep.put("status", "EVALUATED");
            rep.put(
                    "feedback",
                    passVotes
                            + " of "
                            + n
                            + " repetitions passed"
                            + (majorityPass ? " - majority pass" : " - majority FAIL"));
            scores.put("REPEATS", rep);
        }

        EvalRunCaseResult saved =
                caseResultRepository.save(
                        EvalRunCaseResult.scored(
                                run.getId(),
                                datasetCase,
                                representative.responseText(),
                                representative.toolCallsJson(),
                                writeJsonQuietly(scores),
                                mean,
                                toolsSatisfied,
                                passed));
        return new CaseOutcome(
                false,
                saved.getOverallScore(),
                toolsSatisfied,
                graderSatisfied,
                passed,
                judgeOutage);
    }

    /**
     * One replay+judge repetition - the pre-V343 single-shot body. Never throws: a replay failure
     * is returned as an errored {@link Repetition} and only becomes a case ERROR when EVERY
     * repetition errored.
     */
    private Repetition replayAndJudge(
            EvalRun run,
            EvalDataset dataset,
            AgentDefinitionLookup.AgentDefinition agent,
            EvalDatasetCase datasetCase,
            List<EvaluationType> enabledTypes,
            String sessionId) {
        try {
            AgentTurnReplayer.ReplayedTurn turn =
                    replayer.replay(
                            run.getAgentId(),
                            sessionId,
                            run.getRequestedBy(),
                            datasetCase.getUserMessage(),
                            dataset.isToolExecutionEnabled(),
                            CASE_REPLAY_TIMEOUT);

            List<String> toolNames =
                    turn.toolCalls().stream()
                            .map(AgentTurnReplayer.ReplayedToolCall::toolName)
                            .toList();

            EvaluationContext context =
                    new EvaluationContext(
                            run.getAgentId(),
                            agent.name(),
                            agent.systemPrompt(),
                            datasetCase.getUserMessage(),
                            turn.responseText(),
                            toolNames,
                            0,
                            0,
                            agent.model(),
                            datasetCase.getExpectedOutput());

            AggregatedEvaluationResult result = evalEngine.evaluate(context, enabledTypes);
            Boolean toolsSatisfied = evaluateExpectedTools(dataset, datasetCase, toolNames);

            Map<String, Object> scores = scoreMap(result);
            Boolean graderPassed = null;
            // Judge down for this repetition: the aggregate would be whatever
            // the non-LLM dimension (Length) says on its own, which is not a
            // judgement of the answer. No judge score, and the reason is kept.
            String judgeOutage = judgeOutageReason(result);
            Double overall = judgeOutage != null ? null : result.overallScore();
            // Trajectory eval (app.quality.trajectory-eval.enabled, default off): a case whose
            // grader_spec is a
            // trajectory spec scores the replayed turn's tool calls. Its verdict joins the grader
            // vote and its
            // score is blended into the case's overall score, so pass^k and paired comparison see
            // it.
            if (trajectoryEvalEnabled.getAsBoolean() && dataset.isToolExecutionEnabled()) {
                TrajectoryCaseGrader.Graded trajectory =
                        TrajectoryCaseGrader.grade(datasetCase.getGraderSpec(), turn.toolCalls());
                if (trajectory != null) {
                    graderPassed = trajectory.passed();
                    overall = TrajectoryCaseGrader.blend(overall, trajectory.score());
                    scores.put(TrajectoryCaseGrader.DIMENSION, trajectory.dimension());
                }
            }
            CodeGrader.Result graded = gradeWithCodeGrader(datasetCase, turn.responseText());
            if (graded != null) {
                graderPassed = graded.passed();
                Map<String, Object> dimension = new HashMap<>();
                dimension.put("score", graded.passed() ? 1.0 : 0.0);
                dimension.put("status", "EVALUATED");
                dimension.put("feedback", graded.detail());
                scores.put(CODE_GRADER_DIMENSION, dimension);
            }

            return new Repetition(
                    false,
                    overall,
                    toolsSatisfied,
                    graderPassed,
                    turn.responseText(),
                    serializeToolCalls(turn.toolCalls()),
                    scores,
                    null,
                    judgeOutage);
        } catch (Exception e) {
            log.warn("Eval run {} case {} repetition errored", run.getId(), datasetCase.getId(), e);
            return new Repetition(
                    true,
                    null,
                    null,
                    null,
                    null,
                    null,
                    Map.of(),
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                    null);
        }
    }

    /**
     * The judge's own failure reason when the LLM judge produced NOTHING for this turn: at least
     * one dimension came back {@link EvaluationResult#isJudgeUnavailable judge-unavailable} and no
     * LLM-judged dimension was actually evaluated. Null otherwise - a turn where some judge calls
     * worked is an ordinary partial measurement.
     */
    static String judgeOutageReason(AggregatedEvaluationResult result) {
        if (result == null || result.resultsByType() == null) {
            return null;
        }
        String reason = null;
        for (Map.Entry<EvaluationType, EvaluationResult> entry :
                result.resultsByType().entrySet()) {
            EvaluationResult dimension = entry.getValue();
            if (dimension == null) {
                continue;
            }
            if (entry.getKey().isLlmJudged() && dimension.isEvaluated()) {
                return null;
            }
            if (reason == null && EvaluationResult.isJudgeUnavailable(dimension)) {
                reason =
                        dimension
                                .feedback()
                                .substring(EvaluationResult.JUDGE_UNAVAILABLE_PREFIX.length());
            }
        }
        return reason;
    }

    /**
     * A repetition passes: no error, no violated assertion (tools or code grader), and either its
     * judge score is at/above the bar (epsilon-safe) or - with no judge score at all - its code
     * grader passed.
     */
    static boolean repetitionPassed(Repetition rep, double passThreshold) {
        if (rep.errored()
                || Boolean.FALSE.equals(rep.toolsSatisfied())
                || Boolean.FALSE.equals(rep.graderPassed())) {
            return false;
        }
        if (rep.overallScore() != null) {
            return rep.overallScore() >= passThreshold - 1e-9;
        }
        return Boolean.TRUE.equals(rep.graderPassed());
    }

    /**
     * Pseudo-dimension name the code grader's verdict is stored under in the case's scores
     * document.
     */
    static final String CODE_GRADER_DIMENSION = "CODE_GRADER";

    /**
     * Applies the case's {@code grader_spec}, or null when graders are off, the case has none, or
     * the stored spec no longer parses (logged: a corrupt spec must not fail or silently pass a
     * case).
     */
    private CodeGrader.Result gradeWithCodeGrader(
            EvalDatasetCase datasetCase, String responseText) {
        if (!codeGradersEnabled.getAsBoolean()
                || datasetCase.getGraderSpec() == null
                || TrajectoryCaseGrader.isTrajectorySpec(datasetCase.getGraderSpec())) {
            return null;
        }
        try {
            return CodeGrader.parse(datasetCase.getGraderSpec()).grade(responseText);
        } catch (IllegalArgumentException e) {
            log.warn(
                    "Case {} has an unusable grader_spec - not graded ({})",
                    datasetCase.getId(),
                    e.getMessage());
            return null;
        }
    }

    record Repetition(
            boolean errored,
            Double overallScore,
            Boolean toolsSatisfied,
            Boolean graderPassed,
            String responseText,
            String toolCallsJson,
            Map<String, Object> scores,
            String error,
            /**
             * Non-null when the LLM judge produced nothing for this repetition: its failure reason.
             */
            String judgeOutage) {}

    record CaseOutcome(
            boolean errored,
            Double overallScore,
            Boolean expectedToolsSatisfied,
            Boolean graderSatisfied,
            Boolean passed,
            /** Non-null when the judge was down for every repetition of this case that replayed. */
            String judgeOutage) {}

    /**
     * Three-valued expected-tools assertion. NULL when the case asserts nothing OR the dataset
     * replays tool-less (the assertion is structurally unevaluable there - reporting it as failed
     * would punish the SAFE default); otherwise subset check: every expected tool name must appear
     * among the turn's actual calls.
     */
    static Boolean evaluateExpectedTools(
            EvalDataset dataset, EvalDatasetCase datasetCase, List<String> actualToolNames) {
        List<String> expected =
                EvalDatasetService.parseExpectedTools(datasetCase.getExpectedTools());
        if (expected.isEmpty() || !dataset.isToolExecutionEnabled()) {
            return null;
        }
        return new HashSet<>(actualToolNames).containsAll(expected);
    }

    private static String serializeToolCalls(List<AgentTurnReplayer.ReplayedToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return null;
        }
        try {
            List<Map<String, String>> shaped = new ArrayList<>();
            for (AgentTurnReplayer.ReplayedToolCall call : toolCalls) {
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("toolName", call.toolName());
                entry.put("input", call.input());
                entry.put("output", call.output());
                shaped.add(entry);
            }
            return MAPPER.writeValueAsString(shaped);
        } catch (Exception e) {
            return null; // Provenance only - never fail a case over its trace encoding.
        }
    }

    /** The dimension-name -> {score,status,feedback} map form of one judge pass. */
    static Map<String, Object> scoreMap(AggregatedEvaluationResult result) {
        Map<String, Object> shaped = new LinkedHashMap<>();
        for (Map.Entry<EvaluationType, EvaluationResult> entry :
                result.resultsByType().entrySet()) {
            Map<String, Object> dimension = new HashMap<>();
            dimension.put("score", entry.getValue().score());
            dimension.put("status", entry.getValue().status().name());
            dimension.put("feedback", entry.getValue().feedback());
            shaped.put(entry.getKey().name(), dimension);
        }
        return shaped;
    }

    /** Map -> stored JSON text; null on failure (provenance must not kill a case). */
    static String writeJsonQuietly(Map<String, ?> value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }
}
