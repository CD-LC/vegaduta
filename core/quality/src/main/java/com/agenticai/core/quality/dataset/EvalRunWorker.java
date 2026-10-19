package com.agenticai.core.quality.dataset;

import com.agenticai.core.chat.LlmCallLogService;
import com.agenticai.core.quality.EvalEngine;
import com.agenticai.core.quality.EvaluationType;
import com.agenticai.core.quality.impl.JudgeChatClientFactory;
import com.agenticai.core.security.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The worker half of {@link EvalRunService}: the body of one regression run on an {@link EvalRunExecutor}
 * thread - judge preflight, the sequential case loop with its cooperative-cancel checkpoints, the budget cap,
 * the judge-outage abort and the terminal write. The service keeps the trigger, cancel, the reads and the
 * verdict rules ({@link EvalRunService#computeVerdict}, {@link EvalRunService#runScore}), which this class
 * applies. Not a bean: the service builds it so its test-overridable flags stay on the service.
 */
final class EvalRunWorker {

    private static final Logger log = LoggerFactory.getLogger(EvalRunWorker.class);

    private final EvalDatasetRepository datasetRepository;
    private final EvalDatasetCaseRepository caseRepository;
    private final EvalRunRepository runRepository;
    private final JudgeChatClientFactory judgeChatClientFactory;
    private final AgentDefinitionLookup agentDefinitionLookup;
    private final EvalCaseExecutor caseExecutor;
    private final ObjectProvider<LlmCallLogService> llmCallLogService;
    private final BooleanSupplier judgePreflightEnabled;

    EvalRunWorker(
        EvalDatasetRepository datasetRepository,
        EvalDatasetCaseRepository caseRepository,
        EvalRunRepository runRepository,
        JudgeChatClientFactory judgeChatClientFactory,
        AgentDefinitionLookup agentDefinitionLookup,
        EvalCaseExecutor caseExecutor,
        ObjectProvider<LlmCallLogService> llmCallLogService,
        BooleanSupplier judgePreflightEnabled
    ) {
        this.datasetRepository = datasetRepository;
        this.caseRepository = caseRepository;
        this.runRepository = runRepository;
        this.judgeChatClientFactory = judgeChatClientFactory;
        this.agentDefinitionLookup = agentDefinitionLookup;
        this.caseExecutor = caseExecutor;
        this.llmCallLogService = llmCallLogService;
        this.judgePreflightEnabled = judgePreflightEnabled;
    }

    /**
     * The whole run, on an executor worker thread. Deliberately NOT
     * {@code @Transactional}: a run is minutes of LLM calls, and one long
     * transaction would pin a Hikari connection (of ~15) for its entire
     * duration. Each repository call runs in its own short transaction;
     * durability between them is what the boot orphan sweep is for.
     */
    void executeRun(UUID runId, String tenantId, String subject, Set<String> roles) {
        TenantContext.set(TenantContext.of(tenantId, subject, roles));
        try {
            EvalRun run = runRepository.findById(runId).orElse(null);
            if (run == null) {
                return; // Dataset deleted between trigger and pickup - cascade took the row.
            }
            if (run.isCancelRequested()) {
                run.markCancelled();
                runRepository.save(run);
                return;
            }

            // The per-run judge override (V343) applies to every judge call
            // this worker makes for this run - installed for the WHOLE case
            // loop, so EvalEngine's per-evaluator buildJudgeClient calls see
            // it. resolveJudgeModelIdOrNull() consulted AFTER scoping reflects
            // the override, so judge_model records what actually judged.
            try (AutoCloseable judgeScope =
                judgeChatClientFactory.judgeModelScope(run.getRequestedJudgeModel())) {
                executeRunBody(run, runId);
            }
        } catch (Exception e) {
            log.error("Eval run {} failed unexpectedly", runId, e);
            try {
                runRepository.findById(runId).ifPresent(r -> {
                    if (!r.getStatus().isTerminal()) {
                        failRun(r, "Unexpected failure: " + e.getMessage());
                    }
                });
            } catch (Exception persistFailure) {
                log.error("Could not even persist the failure of eval run {}", runId, persistFailure);
            }
        } finally {
            TenantContext.clear();
        }
    }

    /** The run body, with the judge scope already installed by {@link #executeRun}. */
    private void executeRunBody(EvalRun run, UUID runId) throws Exception {
        {
            run.markRunning(judgeChatClientFactory.resolveJudgeModelIdOrNull());
            runRepository.save(run);

            EvalDataset dataset = datasetRepository.findById(run.getDatasetId()).orElse(null);
            if (dataset == null) {
                failRun(run, "Dataset vanished while the run was queued.");
                return;
            }
            Optional<AgentDefinitionLookup.AgentDefinition> agent =
                agentDefinitionLookup.find(run.getAgentId());
            if (agent.isEmpty()) {
                // Same posture as agent_job's AGENT_NOT_FOUND: a real,
                // visible outcome instead of a hidden FK constraint.
                failRun(run, "Agent " + run.getAgentId() + " no longer exists in this workspace.");
                return;
            }

            String judgeId = run.getJudgeModel();
            if (judgeId != null && agent.get().model() != null
                && EvalRunService.modelPart(judgeId).equalsIgnoreCase(agent.get().model().trim())) {
                log.warn("Eval run {}: judge model '{}' is the agent's own model - self-preference bias "
                    + "inflates these scores; use a different judge.", runId, judgeId);
            }

            // Fail fast on a judge that cannot answer (2026-10-05: a run
            // against a judge whose credentials the provider refused went
            // through every case and reported the Length heuristic's 25% as
            // its score). One tiny call, then either a clear FAILED run or
            // the real work.
            if (judgePreflightEnabled.getAsBoolean()) {
                Optional<String> judgeDown = judgeChatClientFactory.probeUnavailableReason();
                if (judgeDown != null && judgeDown.isPresent()) {
                    failRun(run, judgeUnavailableMessage(judgeId, judgeDown.get()));
                    return;
                }
            }

            // Trigger is PREMIUM-gated (controller), so the full judge set
            // applies - same resolution QualityController#getConfig echoes.
            List<EvaluationType> enabledTypes = EvalEngine.getEnabledForTier(true);

            List<EvalDatasetCase> cases =
                caseRepository.findByDatasetIdOrderByPositionAscCreatedAtAsc(dataset.getId());

            int scored = 0;
            int passedScored = 0;
            int toolViolations = 0;
            boolean anyGraded = false;
            int violatedAssertions = 0;
            int errored = 0;
            int judgeOutageCases = 0;
            int leadingJudgeOutages = 0;
            String judgeOutageReason = null;
            double scoreSum = 0.0;
            BigDecimal costUsd = BigDecimal.ZERO;
            int repeats = Math.max(EvalRunService.MIN_REPEATS, Math.min(run.getRepeats(), EvalRunService.MAX_REPEATS));
            BigDecimal budget = run.getBudgetUsd() != null ? run.getBudgetUsd() : EvalRunService.DEFAULT_BUDGET_USD;

            int index = 0;
            for (EvalDatasetCase datasetCase : cases) {
                // Cooperative cancel checkpoint - re-read the row, because
                // the cancel writer only ever touches cancel_requested.
                EvalRun fresh = runRepository.findById(runId).orElse(null);
                if (fresh == null) {
                    return;
                }
                if (fresh.isCancelRequested()) {
                    fresh.markCancelled();
                    runRepository.save(fresh);
                    return;
                }

                EvalCaseExecutor.CaseOutcome outcome = caseExecutor.executeCase(
                    run, dataset, agent.get(), datasetCase, enabledTypes, index, repeats);
                costUsd = costUsd.add(observedCaseCost(runId, index, repeats));
                persistCost(runId, costUsd);
                if (outcome.judgeOutage() != null) {
                    judgeOutageCases++;
                    if (judgeOutageReason == null) {
                        judgeOutageReason = outcome.judgeOutage();
                    }
                }
                if (outcome.judgeOutage() != null && scored == 0 && outcome.overallScore() == null) {
                    leadingJudgeOutages++;
                    if (leadingJudgeOutages >= EvalRunService.JUDGE_OUTAGE_ABORT_AFTER && index + 1 < cases.size()) {
                        EvalRun aborted = runRepository.findById(runId).orElse(run);
                        aborted.recordCost(costUsd);
                        failRun(aborted, noUsableVerdictsMessage(judgeId, judgeOutageReason, leadingJudgeOutages,
                            index + 1, "stopped early")
                            + " Stopped after " + (index + 1) + " of " + cases.size()
                            + " cases - the remaining cases were not run.");
                        return;
                    }
                }
                if (outcome.errored()) {
                    errored++;
                } else {
                    if (outcome.overallScore() != null) {
                        scored++;
                        scoreSum += outcome.overallScore();
                        if (Boolean.TRUE.equals(outcome.passed())) {
                            passedScored++;
                        }
                    }
                    if (outcome.graderSatisfied() != null) {
                        anyGraded = true;
                    }
                    // Both kinds are COUNTED (failedAssertionCount), but only the tool
                    // assertion is a hard run failure. A code-grader miss is an ordinary
                    // failed case: it lowers the run's pass fraction (see runScore) so
                    // passThreshold stays meaningful.
                    if (Boolean.FALSE.equals(outcome.expectedToolsSatisfied())) {
                        toolViolations++;
                    }
                    if (Boolean.FALSE.equals(outcome.expectedToolsSatisfied())
                        || Boolean.FALSE.equals(outcome.graderSatisfied())) {
                        violatedAssertions++;
                    }
                }
                index++;

                // Budget cap (V343): once observed replay spend exceeds the
                // run's budget the run CANCELS - remaining cases never
                // replay. A cancelled run is not SUCCEEDED, so a truncated
                // run can never satisfy a promote gate.
                if (costUsd.compareTo(budget) > 0 && index < cases.size()) {
                    EvalRun capped = runRepository.findById(runId).orElse(null);
                    if (capped == null) {
                        return;
                    }
                    capped.cancelWithNote("Run budget of $" + budget + " exceeded after " + index
                        + " of " + cases.size() + " cases (observed spend $" + costUsd
                        + ") - remaining cases were not run.");
                    runRepository.save(capped);
                    return;
                }
            }

            // Nothing was measured AND the judge was down for at least one
            // case: that is a failed run with a reason, not an INCONCLUSIVE
            // (or, before this guard, a heuristic-only number).
            if (scored == 0 && judgeOutageCases > 0) {
                EvalRun failed = runRepository.findById(runId).orElse(run);
                failed.recordCost(costUsd);
                failRun(failed, noUsableVerdictsMessage(judgeId, judgeOutageReason, judgeOutageCases,
                    cases.size(), null));
                log.warn("Eval run {} failed: judge unavailable for {} of {} cases, nothing scored",
                    runId, judgeOutageCases, cases.size());
                return;
            }

            Double overall = EvalRunService.runScore(anyGraded, scored, passedScored, scoreSum);
            EvalRunVerdict verdict = EvalRunService.computeVerdict(
                overall, run.getPassThreshold(), scored, toolViolations, errored);

            // Reload before the terminal write so a cancel flag set after
            // the last checkpoint is not clobbered back to false (harmless
            // either way - the run genuinely finished - but never lie in a
            // column another writer owns).
            EvalRun terminal = runRepository.findById(runId).orElse(run);
            terminal.complete(overall, scored, violatedAssertions, errored, verdict);
            terminal.recordCost(costUsd);
            runRepository.save(terminal);
            log.info("Eval run {} finished: verdict={} overall={} ({} scored / {} violated / {} errored of {})",
                runId, verdict, overall, scored, violatedAssertions, errored, cases.size());
        }
    }

    /**
     * Run-level text for a judge that answered the preflight and then produced nothing usable for the
     * cases it was given: 'Judge model X returned no usable verdicts (N of M cases failed: reason)'.
     */
    static String noUsableVerdictsMessage(String judgeId, String reason, int failedCases, int casesTried, String note) {
        String why = reason != null && !reason.isBlank() ? reason.trim() : "the judge did not answer";
        while (why.endsWith(".")) {
            why = why.substring(0, why.length() - 1);
        }
        return "Judge model" + (judgeId != null && !judgeId.isBlank() ? " " + judgeId : "")
            + " returned no usable verdicts (" + failedCases + " of " + Math.max(casesTried, failedCases)
            + " cases failed" + (note == null ? "" : ", " + note) + ": " + why + "). No score was recorded. "
            + "Fix that provider in Providers settings, or pick another judge model in Run options, then run again.";
    }

    /** The run-level error text for a judge that cannot answer. */
    static String judgeUnavailableMessage(String judgeId, String reason) {
        String why = reason != null && !reason.isBlank() ? reason.trim() : "the judge did not answer";
        if (!why.endsWith(".") && !why.endsWith("!") && !why.endsWith("?")) {
            why = why + ".";
        }
        return "Judge model unavailable" + (judgeId != null && !judgeId.isBlank() ? " (" + judgeId + ")" : "")
            + ": " + why
            + " No score was recorded. Fix that provider in Providers settings, or pick another judge "
            + "model in Run options, then run again.";
    }

    /**
     * Observed spend of one case's repetitions, from {@code llm_call_log}'s
     * per-session {@code estimated_cost_usd} rows (the same durable log the
     * trace-link lookup already reads). Covers the replayed turns; judge
     * calls are not session-tagged today, so the budget bounds OBSERVED
     * replay spend - documented honestly rather than padded with an
     * estimate. Zero when the log service is absent or nothing was logged.
     */
    private BigDecimal observedCaseCost(UUID runId, int caseIndex, int repeats) {
        LlmCallLogService logService = llmCallLogService.getIfAvailable();
        if (logService == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (int r = 0; r < repeats; r++) {
            String sessionId = r == 0
                ? "eval:" + runId + ":" + caseIndex
                : "eval:" + runId + ":" + caseIndex + ":" + r;
            try {
                for (var call : logService.forTask(sessionId)) {
                    if (call.getEstimatedCostUsd() != null) {
                        total = total.add(call.getEstimatedCostUsd());
                    }
                }
            } catch (Exception e) {
                log.debug("Cost lookup failed for eval session {} - budget check sees less ({})",
                    sessionId, e.toString());
            }
        }
        return total;
    }

    /** Best-effort cost persist between cases - never fails the run over accounting. */
    private void persistCost(UUID runId, BigDecimal costUsd) {
        try {
            runRepository.findById(runId).ifPresent(r -> {
                r.recordCost(costUsd);
                runRepository.save(r);
            });
        } catch (Exception e) {
            log.debug("Could not persist eval run {} cost ({})", runId, e.toString());
        }
    }

    private void failRun(EvalRun run, String message) {
        run.fail(message);
        runRepository.save(run);
    }
}
