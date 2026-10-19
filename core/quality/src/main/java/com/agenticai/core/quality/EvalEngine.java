package com.agenticai.core.quality;

import com.agenticai.core.security.TenantScope;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Orchestrates quality evaluations on agent responses.
 *
 * <p>Coordinates running configured evaluators asynchronously (so they don't slow down the
 * user-facing chat) and aggregates their scores into an overall quality metric.
 *
 * <p>Score aggregation uses weighted average: overallScore = sum(score * weight) / sum(weight)
 *
 * <p>Each evaluator has a default weight (all 0.5 currently, treating them equally).
 *
 * <h2>Why a virtual-thread-per-task executor (JVM memory/thread-pool audit, 2026-07-29)</h2>
 *
 * {@link #evaluate} runs up to five {@link Evaluator}s per task, each a blocking LLM-as-judge call
 * ({@code AbstractLlmJudgeEvaluator#evaluate}) - exactly the "blocking I/O wait" case virtual
 * threads (stable since Java 21 - see {@code core/pom.xml}'s {@code java.version}) exist for. This
 * used to run on a fixed 4-thread platform pool ({@code Executors.newFixedThreadPool(4)}), which
 * meant every {@code evaluateResponseAsync} call on the post-response path (see {@link
 * com.agenticai.core.quality.QualityEvaluationService#evaluateResponseAsync}) competed for only 4
 * threads platform-wide - under concurrent chat load this became a hard 4-wide bottleneck that
 * silently queued (and, past the caller's patience, effectively dropped) quality evaluations. A
 * blocked virtual thread parks its carrier at near-zero platform-thread cost, so there is no pool
 * to size and no ceiling to starve under concurrent turns - same reasoning and same fix shape as
 * {@code AgentChatService#preTurnExecutor}, the reference implementation this mirrors (see that
 * field's javadoc).
 */
@Component
public class EvalEngine {

    private static final Logger log = LoggerFactory.getLogger(EvalEngine.class);

    private final List<Evaluator> evaluators;
    private final ExecutorService executorService;

    public EvalEngine(List<Evaluator> evaluators) {
        this.evaluators = evaluators;
        this.executorService = TenantAwareExecutors.newVirtualThreadPerTaskExecutor("eval-engine-");
    }

    /**
     * Run all configured evaluations asynchronously for the given context.
     *
     * <p>Returns a CompletableFuture that completes with aggregated eval results. Does not block
     * the caller.
     *
     * @param context the evaluation context (response data, agent info, etc.)
     * @param enabledTypes which evaluation types to run (based on plan tier)
     * @return future that completes with the aggregated evaluation result
     */
    public CompletableFuture<AggregatedEvaluationResult> evaluateAsync(
            EvaluationContext context, List<EvaluationType> enabledTypes) {
        // The judge clients resolve the tenant's judge provider from TenantContext, a ThreadLocal
        // the worker thread does not have: carry the caller's onto it.
        TenantScope tenant = TenantScope.capture();
        return CompletableFuture.supplyAsync(
                tenant.wrapSupplier(() -> evaluate(context, enabledTypes)), executorService);
    }

    /**
     * Run all enabled evaluations synchronously.
     *
     * <p>Deliberately still SERIAL across the (up to five) evaluators, not fanned out further onto
     * their own virtual threads, even though threads are now cheap. Every {@code
     * AbstractLlmJudgeEvaluator} resolves its judge client through {@code
     * JudgeChatClientFactory#buildJudgeClient}, which reads {@code TenantContext.current()} - a
     * {@code ThreadLocal} - to pick the tenant's active BYOK provider. That context is set once by
     * {@code TenantContextFilter} on the ORIGINAL request thread and is not inherited by a task
     * fanned out onto a different thread (a fresh virtual thread most of all): a naive
     * per-evaluator fan-out would run every judge call with an EMPTY tenant context and silently
     * resolve the wrong (or no) provider. Doing this safely would need the same explicit
     * save/apply/restore ceremony {@code AgentChatService#supplyPreTurn} uses for its own fan-out,
     * applied per evaluator here - a real change in shape, not just an executor swap, and one this
     * pass intentionally does NOT make: the actual reported bottleneck was the 4-platform-thread
     * ceiling on {@link #evaluateAsync} (fixed above), not this method's already-modest total
     * latency (the UI/DB never wait on it - see {@link
     * com.agenticai.core.quality.QualityEvaluationService#evaluateResponseAsync}). Revisit only
     * alongside an explicit TenantContext-propagation change, with its own test coverage for the
     * propagation itself.
     *
     * @param context the evaluation context
     * @param enabledTypes which evaluation types to run
     * @return aggregated evaluation result with overall score
     */
    public AggregatedEvaluationResult evaluate(
            EvaluationContext context, List<EvaluationType> enabledTypes) {
        Map<EvaluationType, EvaluationResult> resultsByType = new HashMap<>();

        for (Evaluator evaluator : evaluators) {
            EvaluationType type = evaluator.getType();

            if (!enabledTypes.contains(type)) {
                continue; // Skip disabled evaluations
            }

            try {
                log.debug("Running {} evaluation for agent {}", type, context.agentId());
                EvaluationResult result = evaluator.evaluate(context);
                resultsByType.put(type, result);
            } catch (Exception e) {
                log.error("Evaluator {} failed", type, e);
                // An evaluator that threw did NOT measure anything - record
                // that explicitly instead of silently dropping the dimension.
                resultsByType.put(
                        type,
                        EvaluationResult.unavailable(
                                type, "Not evaluated - evaluator failed: " + e.getMessage()));
            }
        }

        // Aggregate over the results that are real measurements; null - not
        // a fabricated neutral - when nothing was actually measured.
        Double overallScore = aggregateScores(resultsByType, enabledTypes);

        return new AggregatedEvaluationResult(resultsByType, overallScore);
    }

    /**
     * Aggregate individual evaluation scores into an overall quality score.
     *
     * <p>Uses weighted average over the {@link EvaluationStatus#EVALUATED} results only: sum(score
     * * weight) / sum(weight). {@code UNAVAILABLE} results carry no score and contribute nothing -
     * and when NO result is a real measurement this returns {@code null}, never a made-up 0.5 (the
     * previous behaviour, which made "we measured this as mediocre" and "we measured nothing"
     * indistinguishable to every caller).
     *
     * @param results evaluation results by type
     * @param enabledTypes enabled evaluation types
     * @return overall score (0-1), or null when nothing was measured
     */
    private Double aggregateScores(
            Map<EvaluationType, EvaluationResult> results, List<EvaluationType> enabledTypes) {
        double sumWeightedScores = 0.0;
        double sumWeights = 0.0;

        for (EvaluationType type : enabledTypes) {
            EvaluationResult result = results.get(type);
            if (result != null && result.isEvaluated()) {
                double weight = type.getDefaultWeight();
                sumWeightedScores += result.score() * weight;
                sumWeights += weight;
            }
        }

        if (sumWeights == 0) {
            return null; // Nothing measured - absent, not neutral
        }

        double overall = sumWeightedScores / sumWeights;
        return Math.min(1.0, Math.max(0.0, overall)); // Clamp to 0-1
    }

    /**
     * Get list of evaluation types enabled for a given plan tier.
     *
     * @param isPremium whether the tenant has premium/paid plan
     * @return list of enabled evaluation types
     */
    public static List<EvaluationType> getEnabledForTier(boolean isPremium) {
        if (isPremium) {
            // Premium gets all evaluations
            return List.of(EvaluationType.values());
        } else {
            // FREE tier gets only basic evals
            return List.of(EvaluationType.HELPFULNESS, EvaluationType.SAFETY);
        }
    }

    /**
     * Record aggregated evaluation result, storing it in the database.
     *
     * <p>Only real measurements are persisted: {@code UNAVAILABLE} results never write a score
     * (their reason still lands in the feedback map for transparency), and when NOTHING was
     * measured no record is stored at all - an all-unavailable run must not appear in
     * dashboards/history as a data point.
     *
     * @param agentId the agent that was evaluated
     * @param turnNumber which turn in the conversation
     * @param context the evaluation context
     * @param result the aggregated evaluation result
     * @return the persisted quality record, or {@code null} when nothing was measured and therefore
     *     nothing was stored
     */
    public AgentQualityRecord recordEvaluation(
            String agentId,
            int turnNumber,
            EvaluationContext context,
            AggregatedEvaluationResult result,
            AgentQualityRecordRepository repository) {
        java.util.UUID agentUuid = java.util.UUID.fromString(agentId);

        if (!result.hasMeasuredScore()) {
            log.info(
                    "No quality record stored for agent {} turn {} - no evaluator produced a real"
                            + " measurement",
                    agentId,
                    turnNumber);
            return null;
        }

        Map<String, Double> scores = new HashMap<>();
        Map<String, String> feedback = new HashMap<>();

        for (Map.Entry<EvaluationType, EvaluationResult> entry :
                result.resultsByType().entrySet()) {
            if (entry.getValue().isEvaluated()) {
                scores.put(entry.getKey().name(), entry.getValue().score());
            }
            feedback.put(entry.getKey().name(), entry.getValue().feedback());
        }

        AgentQualityRecord record =
                new AgentQualityRecord(
                        agentUuid,
                        turnNumber,
                        result.overallScore(),
                        scores,
                        feedback,
                        context.userMessage(),
                        context.agentResponse(),
                        context.inputTokens(),
                        context.outputTokens());

        return repository.save(record);
    }

    /**
     * Shutdown the executor service.
     *
     * <p>{@code @PreDestroy} so Spring actually calls this on context shutdown (JVM
     * memory/thread-pool audit, 2026-07-29 - previously this method existed but was never invoked
     * from any main-source code, only from tests constructing their own {@code EvalEngine}
     * directly; the bean Spring wires into {@link
     * com.agenticai.core.quality.QualityEvaluationService} never had its executor closed). Virtual
     * threads spun up by {@link Executors#newVirtualThreadPerTaskExecutor()} are themselves daemon
     * threads that die with the JVM on their own, so this was never a hard hang - but the {@link
     * ExecutorService} wrapper itself is a resource Spring should close deterministically like any
     * other, rather than leaving it to GC/JVM exit.
     */
    @PreDestroy
    public void shutdown() {
        executorService.shutdown();
    }
}
