package com.agenticai.core.quality;

import java.util.List;
import java.util.UUID;

/**
 * Context provided to evaluators when scoring a response.
 *
 * Contains the full turn data: what the user asked, what the agent responded
 * with, the agent's system prompt, and any other relevant metadata.
 *
 * <p>{@code expectedOutput} (eval-datasets workstream, 2026-08-18) is the
 * OFFLINE-only tenth component: a dataset case's reference answer, handed to
 * the ACCURACY judge as ground truth ({@link
 * com.agenticai.core.quality.impl.AccuracyEvaluator}'s REFERENCE ANSWER
 * block). It is {@code null} on every online-scoring path - a live turn has
 * no known-good answer by definition - which is why the nine-component
 * convenience constructor below exists and every pre-existing call site
 * (including the tests) compiles unchanged. Adding a field here rather than
 * inventing a parallel offline context type keeps ONE judge pipeline: the
 * whole point of the dataset runner is to reuse these evaluators, not fork
 * them.
 *
 * <p>{@code retrievedContext} (2026-09-28 Spring AI gap audit) is the
 * eleventh, also optional, component: the document chunks a RAG step put in
 * front of the model for this turn, in the order they were supplied. It is
 * what {@link com.agenticai.core.quality.impl.FaithfulnessEvaluator} judges
 * the answer against ("is every claim supported by what was retrieved?") -
 * without it no evaluator in this module could tell a grounded answer from a
 * confident hallucination, because ACCURACY judges against the judge model's
 * own world knowledge, not against the tenant's documents. {@code null} or
 * empty means "no retrieval happened / not captured", and the faithfulness
 * judge then reports not-applicable rather than guessing. Both earlier
 * constructor shapes are kept, so no existing call site changes; a caller
 * that has the chunks uses the canonical constructor. Stored as an immutable
 * copy so a caller reusing its list cannot change what an async judge sees.
 */
public record EvaluationContext(
    // Agent metadata
    UUID agentId,
    String agentName,
    String systemPrompt,

    // Turn data
    String userMessage,
    String agentResponse,
    List<String> toolCalls,

    // Metadata
    long inputTokens,
    long outputTokens,
    String model,

    // Offline (dataset replay) only - null on every online path.
    String expectedOutput,

    // RAG chunks shown to the model for this turn - null/empty when none.
    List<String> retrievedContext
) {

    public EvaluationContext {
        retrievedContext = retrievedContext == null
            ? null
            : retrievedContext.stream().filter(java.util.Objects::nonNull).toList();
    }

    /**
     * The original nine-component shape, kept so online callers (and the
     * existing tests) never have to know the offline-only field exists.
     */
    public EvaluationContext(
        UUID agentId,
        String agentName,
        String systemPrompt,
        String userMessage,
        String agentResponse,
        List<String> toolCalls,
        long inputTokens,
        long outputTokens,
        String model
    ) {
        this(agentId, agentName, systemPrompt, userMessage, agentResponse, toolCalls,
            inputTokens, outputTokens, model, null, null);
    }

    /**
     * The ten-component shape (with the offline reference answer, without
     * retrieved context) - what the dataset runner has always constructed.
     */
    public EvaluationContext(
        UUID agentId,
        String agentName,
        String systemPrompt,
        String userMessage,
        String agentResponse,
        List<String> toolCalls,
        long inputTokens,
        long outputTokens,
        String model,
        String expectedOutput
    ) {
        this(agentId, agentName, systemPrompt, userMessage, agentResponse, toolCalls,
            inputTokens, outputTokens, model, expectedOutput, null);
    }

    /** True when a dataset case supplied a non-blank reference answer. */
    public boolean hasExpectedOutput() {
        return expectedOutput != null && !expectedOutput.isBlank();
    }

    /** True when at least one non-blank retrieved chunk was supplied. */
    public boolean hasRetrievedContext() {
        return retrievedContext != null && retrievedContext.stream().anyMatch(c -> !c.isBlank());
    }
}
