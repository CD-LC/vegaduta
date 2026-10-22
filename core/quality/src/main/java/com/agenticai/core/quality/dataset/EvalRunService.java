package com.agenticai.core.quality.dataset;

import com.agenticai.core.chat.LlmCallLogService;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.quality.AggregatedEvaluationResult;
import com.agenticai.core.quality.EvalEngine;
import com.agenticai.core.quality.impl.JudgeChatClientFactory;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Triggers, executes and reads regression runs of an {@link EvalDataset} against its agent, scoring
 * every replayed case with the EXISTING {@code core/quality} judges via {@link EvalEngine#evaluate}
 * - this workstream deliberately writes no new scorers.
 *
 * <h2>Execution model</h2>
 *
 * A run is N sequential (replay -> judge) case executions on one worker thread from {@link
 * EvalRunExecutor}'s bounded pool. Sequential within a run for the same reason {@link
 * EvalEngine#evaluate} is serial across evaluators: {@code JudgeChatClientFactory} (and the whole
 * chat pipeline underneath the replay) reads {@code TenantContext} - a ThreadLocal - so per-case
 * fan-out would need per-thread save/apply/restore ceremony for marginal benefit on what is an
 * offline, patience-tolerant surface.
 *
 * <h2>TenantContext on the worker</h2>
 *
 * Set from the RUN ROW'S snapshot (tenantId/requestedBy/roles) before the first repository read and
 * cleared in a {@code finally} - the exact {@code AgentJobExecution#run} idiom, and the reason
 * those columns exist (V213 header). The requester's subject doubles as the budget key for the
 * replayed turns, so a run can never spend money no human owns.
 *
 * <h2>Verdict rules ({@link #computeVerdict})</h2>
 *
 * <ol>
 *   <li>Any ERRORed case or any violated expected-tools assertion -> {@code FAILED} - an agent that
 *       crashes on (or stops using the required tools for) its golden cases has regressed
 *       regardless of how the surviving cases scored.
 *   <li>Otherwise, nothing measured (every case UNAVAILABLE - e.g. no judge provider configured) ->
 *       {@code INCONCLUSIVE}. Honest absence: "we measured nothing" must never read as "regressed
 *       to zero". Same contract as {@code EvaluationResult}, same reasoning {@code TokenBaseline}
 *       documents at the CI layer.
 *   <li>Otherwise mean measured score vs the run's FROZEN {@code passThreshold} (snapshotted at
 *       trigger so editing the dataset's bar never rewrites history - the never-auto-update
 *       baseline rule).
 * </ol>
 */
@Service
public class EvalRunService {

    private static final Logger log = LoggerFactory.getLogger(EvalRunService.class);

    /**
     * Run-option bounds (V343). {@code MAX_REPEATS} mirrors the column CHECK; {@code
     * DEFAULT_BUDGET_USD} is the spec's per-run default and {@code MAX_BUDGET_USD} stops a typo
     * from buying a thousand-dollar run.
     */
    static final int MIN_REPEATS = 1;

    static final int MAX_REPEATS = 5;
    static final BigDecimal DEFAULT_BUDGET_USD = new BigDecimal("5.00");
    static final BigDecimal MAX_BUDGET_USD = new BigDecimal("500.00");

    /** Shared read/write mapper for the result-row JSON documents. */
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    /**
     * Repetitions per case when the trigger names none. Default 1 keeps today's cost; set {@code
     * app.quality.eval.default-repeats} to 3 so gate-grade runs measure pass^k, not a single lucky
     * sample. Clamped to [MIN_REPEATS, MAX_REPEATS]. Package-private non-final for test override.
     */
    @Value("${app.quality.eval.default-repeats:3}")
    int defaultRepeats = MIN_REPEATS;

    /**
     * Whether a case's deterministic code grader (V375 {@code grader_spec}) is applied during a
     * run. Default true is behaviour-neutral: a case with no grader spec is never graded. Set false
     * to ignore specs (e.g. to compare a run against its pre-grader baseline).
     */
    @Value("${app.quality.eval.code-graders.enabled:true}")
    boolean codeGradersEnabled = true;

    /**
     * Whether trigger records the agent-config snapshot (model, temperature, seed, prompt hash,
     * tool allow-list - V375) on the run row. One cheap read; set false to skip it.
     */
    @Value("${app.quality.eval.snapshot.enabled:true}")
    boolean snapshotEnabled = true;

    /**
     * Whether a case's trajectory spec (a {@code grader_spec} of type "trajectory") is scored
     * during a run. Default false: such specs are then ignored, exactly as before the feature
     * existed.
     */
    @Value("${app.quality.trajectory-eval.enabled:false}")
    boolean trajectoryEvalEnabled = false;

    /**
     * Ask the judge one tiny question before the case loop and fail the run at once - "Judge model
     * unavailable: reason" - when it cannot answer, instead of replaying every case against a dead
     * judge. Default on; the switch exists only so an operator can drop the one extra judge call.
     */
    @Value("${app.quality.eval.judge-preflight.enabled:true}")
    boolean judgePreflightEnabled = true;

    /**
     * Consecutive judge-outage cases, with nothing scored yet, after which a run stops instead of
     * replaying the rest. Covers a judge that passes the preflight and then dies (or the preflight
     * being switched off).
     */
    static final int JUDGE_OUTAGE_ABORT_AFTER = 3;

    private final EvalDatasetService datasetService;
    private final EvalDatasetCaseRepository caseRepository;
    private final EvalRunRepository runRepository;
    private final EvalRunCaseResultRepository caseResultRepository;
    private final EvalRunExecutor runExecutor;
    private final JudgeChatClientFactory judgeChatClientFactory;
    private final AgentDefinitionLookup agentDefinitionLookup;
    private final AgentHeadVersionLookup headVersionLookup;

    /** Runs the body of each accepted run on the executor thread. */
    private final EvalRunWorker worker;

    public EvalRunService(
            EvalDatasetService datasetService,
            EvalDatasetRepository datasetRepository,
            EvalDatasetCaseRepository caseRepository,
            EvalRunRepository runRepository,
            EvalRunCaseResultRepository caseResultRepository,
            EvalRunExecutor runExecutor,
            EvalEngine evalEngine,
            JudgeChatClientFactory judgeChatClientFactory,
            AgentDefinitionLookup agentDefinitionLookup,
            AgentHeadVersionLookup headVersionLookup,
            List<AgentTurnReplayer> replayers,
            org.springframework.beans.factory.ObjectProvider<LlmCallLogService> llmCallLogService) {
        this.datasetService = datasetService;
        this.caseRepository = caseRepository;
        this.runRepository = runRepository;
        this.caseResultRepository = caseResultRepository;
        this.runExecutor = runExecutor;
        this.judgeChatClientFactory = judgeChatClientFactory;
        this.agentDefinitionLookup = agentDefinitionLookup;
        this.headVersionLookup = headVersionLookup;
        AgentTurnReplayer replayer = selectReplayer(replayers);
        EvalCaseExecutor caseExecutor =
                new EvalCaseExecutor(
                        replayer,
                        evalEngine,
                        caseResultRepository,
                        () -> codeGradersEnabled,
                        () -> trajectoryEvalEnabled);
        this.worker =
                new EvalRunWorker(
                        datasetRepository,
                        caseRepository,
                        runRepository,
                        judgeChatClientFactory,
                        agentDefinitionLookup,
                        caseExecutor,
                        llmCallLogService,
                        () -> judgePreflightEnabled);
    }

    /**
     * The trigger-time configuration a caller may attach to a run (V343): all fields nullable =
     * defaults (catalog judge, non-strict, 1 repetition, $5 budget, no idempotency key). {@code
     * idempotencyKey} normally arrives via the {@code Idempotency-Key} header (the CI hook
     * contract), not the body.
     */
    public record RunOptions(
            String judgeModel,
            Boolean strictJudge,
            Integer repeats,
            BigDecimal budgetUsd,
            String idempotencyKey) {
        public static RunOptions defaults() {
            return new RunOptions(null, null, null, null, null);
        }
    }

    /**
     * Prefers any typed implementation over the reflective bridge, so the recorded follow-up (an
     * agents-side {@code AgentTurnReplayer} bean) wins the moment it exists, with no change here -
     * see {@link ReflectiveAgentChatTurnReplayer}'s javadoc.
     */
    private static AgentTurnReplayer selectReplayer(List<AgentTurnReplayer> replayers) {
        return replayers.stream()
                .filter(r -> !(r instanceof ReflectiveAgentChatTurnReplayer))
                .findFirst()
                .orElseGet(
                        () ->
                                replayers.stream()
                                        .findFirst()
                                        .orElseThrow(
                                                () ->
                                                        new IllegalStateException(
                                                                "No AgentTurnReplayer implementation available")));
    }

    /**
     * The judge model id a run with {@code requestedOverride} will actually use: {@code
     * "PROVIDER/override"} when an override was requested (the provider is always the tenant's
     * active BYOK one - overrides swap the model, not the credential), else the factory's default
     * resolution. Null when nothing resolvable exists - the run then records null and its cases
     * surface UNAVAILABLE, as before.
     */
    private String effectiveJudgeModelId(String requestedOverride) {
        if (requestedOverride != null) {
            try {
                return activeJudgeProvider() + "/" + requestedOverride;
            } catch (Exception e) {
                return null;
            }
        }
        return judgeChatClientFactory.resolveJudgeModelIdOrNull();
    }

    /**
     * The active provider name for a requested-override judge id, reusing the factory's own
     * resolution indirectly: resolve the DEFAULT judge id and keep its provider part. Throws when
     * unresolvable.
     */
    private String activeJudgeProvider() {
        String resolved = judgeChatClientFactory.resolveJudgeModelIdOrNull();
        if (resolved == null) {
            throw new IllegalStateException("no resolvable judge provider");
        }
        int slash = resolved.indexOf('/');
        return slash > 0 ? resolved.substring(0, slash) : resolved;
    }

    /** The model part of a {@code "PROVIDER/model"} id (or the whole string when unqualified). */
    static String modelPart(String judgeModelId) {
        int slash = judgeModelId == null ? -1 : judgeModelId.lastIndexOf('/');
        return slash >= 0 ? judgeModelId.substring(slash + 1) : String.valueOf(judgeModelId);
    }

    /** Trim + bound the idempotency key; blank means "no key". */
    private static String normalizeIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String trimmed = key.trim();
        if (trimmed.length() > 128) {
            throw new IllegalArgumentException("'Idempotency-Key' must be at most 128 characters.");
        }
        return trimmed;
    }

    // ------------------------------------------------------------------
    // Trigger / cancel
    // ------------------------------------------------------------------

    /**
     * Inserts the QUEUED run row and hands the body to the executor. The one-live-run-per-dataset
     * invariant is enforced by the DATABASE ({@code uq_eval_run_live_per_dataset}); the {@link
     * DataIntegrityViolationException} catch below just narrates its decision - no check-then-act
     * race is possible.
     */
    /** Trigger with default options - the pre-V343 signature, kept for callers/tests. */
    public EvalRun triggerRun(UUID agentId, UUID datasetId) {
        return triggerRun(agentId, datasetId, RunOptions.defaults());
    }

    /**
     * Inserts the QUEUED run row and hands the body to the executor. The one-live-run-per-dataset
     * invariant is enforced by the DATABASE ({@code uq_eval_run_live_per_dataset}); the {@link
     * DataIntegrityViolationException} catch below just narrates its decision - no check-then-act
     * race is possible.
     *
     * <h2>V343 trigger-time options</h2>
     *
     * <ul>
     *   <li>{@code idempotencyKey} - a run already recorded for this dataset under the same key is
     *       RETURNED as-is (the CI hook's retry-safe contract), never duplicated and never a 409.
     *   <li>{@code repeats} (1..5) - replay+score repetitions per case; a case passes only on a
     *       strict majority (see {@link #executeCase}).
     *   <li>{@code budgetUsd} (default $5) - the worker cancels the run when observed replay spend
     *       exceeds it.
     *   <li>{@code judgeModel} - per-run judge override, installed for the worker's whole case loop
     *       via {@link JudgeChatClientFactory#judgeModelScope}.
     *   <li>{@code strictJudge} - when true a judge model identical to the agent's own model is a
     *       400 (self-grading must be opted out of, not into). When false the same situation is
     *       merely visible in the run row ({@code judgeModel} vs the agent's model).
     * </ul>
     */
    public EvalRun triggerRun(UUID agentId, UUID datasetId, RunOptions options) {
        RunOptions opts = options != null ? options : RunOptions.defaults();
        EvalDataset dataset = datasetService.requireDataset(agentId, datasetId);
        long caseCount = caseRepository.countByDatasetId(datasetId);
        if (caseCount == 0) {
            throw new IllegalArgumentException(
                    "This dataset has no cases yet - add at least one before running it.");
        }

        String idempotencyKey = normalizeIdempotencyKey(opts.idempotencyKey());
        if (idempotencyKey != null) {
            // Retry-safe trigger: the same key returns the same run row.
            // Checked BEFORE the live-run guard so a retried trigger of a
            // still-running run replays the row instead of 409ing.
            Optional<EvalRun> prior =
                    runRepository.findByDatasetIdAndIdempotencyKey(datasetId, idempotencyKey);
            if (prior.isPresent()) {
                return prior.get();
            }
        }

        int repeats =
                opts.repeats() != null
                        ? opts.repeats()
                        : Math.max(MIN_REPEATS, Math.min(defaultRepeats, MAX_REPEATS));
        if (repeats < MIN_REPEATS || repeats > MAX_REPEATS) {
            throw new IllegalArgumentException(
                    "'repeats' must be between " + MIN_REPEATS + " and " + MAX_REPEATS + ".");
        }
        BigDecimal budgetUsd = opts.budgetUsd() != null ? opts.budgetUsd() : DEFAULT_BUDGET_USD;
        if (budgetUsd.compareTo(BigDecimal.ZERO) <= 0 || budgetUsd.compareTo(MAX_BUDGET_USD) > 0) {
            throw new IllegalArgumentException(
                    "'budgetUsd' must be greater than 0 and at most " + MAX_BUDGET_USD + ".");
        }
        String judgeModel =
                opts.judgeModel() != null && !opts.judgeModel().isBlank()
                        ? opts.judgeModel().trim()
                        : null;
        boolean strictJudge = Boolean.TRUE.equals(opts.strictJudge());

        if (strictJudge) {
            // The effective judge for this run: the requested override when
            // present, else whatever the factory resolves by default. Judge
            // ids are "PROVIDER/model"; the agent stores only the model, so
            // compare model parts.
            String effectiveJudge = effectiveJudgeModelId(judgeModel);
            String agentModel =
                    agentDefinitionLookup
                            .find(agentId)
                            .map(AgentDefinitionLookup.AgentDefinition::model)
                            .orElse(null);
            if (effectiveJudge != null
                    && agentModel != null
                    && modelPart(effectiveJudge).equalsIgnoreCase(agentModel.trim())) {
                throw new IllegalArgumentException(
                        "strictJudge: the judge model '"
                                + effectiveJudge
                                + "' is identical to the agent's own "
                                + "model '"
                                + agentModel
                                + "' - an agent grading itself is not an evaluation. Pick a "
                                + "different judge model or retry without strictJudge (the console will warn instead).");
            }
        }

        TenantContext context =
                TenantContext.current()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Eval runs can only be triggered with a tenant context (authenticated request)."));

        // Snapshot the dataset's content hash NOW so the gate can later prove
        // the run replayed the cases as they currently stand (V343).
        String datasetHash = datasetService.computeContentHash(datasetId);

        EvalRun run;
        try {
            EvalRun newRun =
                    new EvalRun(
                            dataset.getId(),
                            dataset.getAgentId(),
                            context.getTenantId(),
                            context.getSubject(),
                            String.join(",", context.getRoles()),
                            dataset.getPassThreshold(),
                            (int) caseCount);
            // Bind the run to the head agent_version row BEFORE the save, so
            // the eval gate can later prove which configuration was tested.
            // Absent head (pre-V212 agent, fail-open snapshot gap) stays null -
            // such runs replay fine but can never satisfy a promote gate.
            headVersionLookup.findHeadVersionId(agentId).ifPresent(newRun::bindAgentVersion);
            newRun.applyRunConfig(
                    datasetHash, judgeModel, strictJudge, repeats, budgetUsd, idempotencyKey);
            if (snapshotEnabled) {
                snapshotQuietly(agentId).ifPresent(newRun::applyAgentSnapshot);
            }
            run = runRepository.save(newRun);
        } catch (DataIntegrityViolationException e) {
            if (idempotencyKey != null) {
                // Lost the insert race on the idempotency unique index - the
                // winner's row is the correct answer.
                Optional<EvalRun> winner =
                        runRepository.findByDatasetIdAndIdempotencyKey(datasetId, idempotencyKey);
                if (winner.isPresent()) {
                    return winner.get();
                }
            }
            throw new EvalRunConflictException(
                    "A run of this dataset is already queued or running - wait for it or cancel it first.");
        }

        // Snapshot the identity NOW, on the request thread, for the worker.
        UUID runId = run.getId();
        String tenantId = context.getTenantId();
        String subject = context.getSubject();
        Set<String> roles = Set.copyOf(context.getRoles());

        boolean accepted =
                runExecutor.trySubmit(() -> worker.executeRun(runId, tenantId, subject, roles));
        if (!accepted) {
            run.fail(
                    "Eval executor is saturated (too many concurrent runs platform-wide). "
                            + "Try again once current runs finish.");
            runRepository.save(run);
        }
        return run;
    }

    /**
     * The V375 agent-config snapshot; provenance must never block a trigger, so any failure yields
     * empty.
     */
    private Optional<AgentConfigSnapshot> snapshotQuietly(UUID agentId) {
        try {
            Optional<AgentConfigSnapshot> snapshot = agentDefinitionLookup.findSnapshot(agentId);
            return snapshot != null ? snapshot : Optional.empty();
        } catch (Exception e) {
            log.warn(
                    "Could not snapshot agent {} config for an eval run ({})",
                    agentId,
                    e.toString());
            return Optional.empty();
        }
    }

    /**
     * Cooperative cancel: flips {@code cancel_requested} only - never status, which belongs
     * exclusively to the owning worker (the writer-separation contract on {@link
     * EvalRun#requestCancel}). The worker observes the flag between cases.
     */
    public EvalRun cancelRun(UUID agentId, UUID datasetId, UUID runId) {
        datasetService.requireDataset(agentId, datasetId);
        EvalRun run = requireRun(datasetId, runId);
        if (run.getStatus().isTerminal()) {
            throw new EvalRunConflictException(
                    "Run " + runId + " is already " + run.getStatus() + " - nothing to cancel.");
        }
        String subject = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        run.requestCancel(subject);
        return runRepository.save(run);
    }

    /**
     * The run's headline score. Without any code-graded case it is the mean measured case score
     * (unchanged behaviour). When at least one case carried a deterministic grader, the run scores
     * the FRACTION of measured cases that passed (each case's majority-resolved pass flag, which
     * already folds in the grader, the tool assertion and the judge bar) so a single grader miss
     * lowers the score proportionally instead of failing the whole run, and {@code passThreshold}
     * applies to that fraction.
     */
    static Double runScore(boolean anyGraded, int scored, int passedScored, double scoreSum) {
        if (scored <= 0) {
            return null;
        }
        return anyGraded ? (double) passedScored / scored : scoreSum / scored;
    }

    /** See the class javadoc for the rules and their order. */
    static EvalRunVerdict computeVerdict(
            Double overallScore,
            double passThreshold,
            int scoredCases,
            int violatedAssertions,
            int erroredCases) {
        if (erroredCases > 0 || violatedAssertions > 0) {
            return EvalRunVerdict.FAILED;
        }
        if (scoredCases == 0 || overallScore == null) {
            return EvalRunVerdict.INCONCLUSIVE;
        }
        // Epsilon so a threshold stored as 0.7 and a mean computed as
        // 0.6999999999 (pure double noise) do not flip a verdict.
        return overallScore >= passThreshold - 1e-9 ? EvalRunVerdict.PASSED : EvalRunVerdict.FAILED;
    }

    /**
     * Pseudo-dimension name the code grader's verdict is stored under (see {@link
     * EvalCaseExecutor}).
     */
    static final String CODE_GRADER_DIMENSION = EvalCaseExecutor.CODE_GRADER_DIMENSION;

    /** See {@link EvalCaseExecutor#judgeOutageReason}. */
    static String judgeOutageReason(AggregatedEvaluationResult result) {
        return EvalCaseExecutor.judgeOutageReason(result);
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    public EvalRun requireRun(UUID datasetId, UUID runId) {
        EvalRun run =
                runRepository
                        .findById(runId)
                        .orElseThrow(() -> new EvalRunNotFoundException(runId));
        if (!run.getDatasetId().equals(datasetId)) {
            throw new EvalRunNotFoundException(runId);
        }
        return run;
    }

    public List<EvalRun> listRuns(UUID datasetId, int limit) {
        return runRepository.findByDatasetIdOrderByCreatedAtDesc(
                datasetId, PageRequest.of(0, Math.max(1, Math.min(limit, 100))));
    }

    /**
     * The diff-vs-previous baseline for one run: the most recent EARLIER SUCCEEDED run of the same
     * dataset that measured something. Empty is a normal answer (first run) - callers render "no
     * baseline", never 0.
     */
    public Optional<EvalRun> findBaseline(EvalRun run) {
        return runRepository
                .findFirstByDatasetIdAndStatusAndOverallScoreIsNotNullAndCreatedAtBeforeOrderByCreatedAtDesc(
                        run.getDatasetId(), EvalRunStatus.SUCCEEDED, run.getCreatedAt());
    }

    public List<EvalRunCaseResult> listCaseResults(UUID runId) {
        return caseResultRepository.findByRunIdOrderByPositionAscCreatedAtAsc(runId);
    }

    // ------------------------------------------------------------------
    // JSON documents on the result row
    // ------------------------------------------------------------------

    /** Stored scores JSON -> DTO map; null-safe, parse-failure-safe. */
    static Map<String, Object> parseScoresJson(String scoresJson) {
        if (scoresJson == null || scoresJson.isBlank()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = MAPPER.readValue(scoresJson, Map.class);
            return parsed;
        } catch (Exception e) {
            return Map.of();
        }
    }
}
