package com.agenticai.core.quality;

/**
 * Evaluates a single dimension of response quality.
 *
 * Each Evaluator scores a response on a specific metric (helpfulness,
 * accuracy, tone, etc.) and provides feedback explaining the score.
 *
 * Implementations should be stateless and thread-safe, as they may be
 * called concurrently by multiple evaluation tasks.
 */
public interface Evaluator {
    /**
     * @return the evaluation type this evaluator measures
     */
    EvaluationType getType();

    /**
     * Evaluates the given input/response pair.
     *
     * @param context the evaluation context (user message, agent response, system prompt, etc.)
     * @return an evaluation result with score (0-1) and feedback
     */
    EvaluationResult evaluate(EvaluationContext context);
}
