package com.agenticai.core.agents.core;

/**
 * Simple vs Manual generation tuning - direct feedback: "in agents there
 * should be simple and manual tuning selection - in simple it automatically
 * selects the temp, tokens, p based on the given system prompt - manual will
 * allow users to select the generation control". {@code MANUAL} is the
 * default (see migration V64's backfill) - every pre-existing agent's
 * temperature/topP/maxTokens keep meaning exactly what they already meant: a
 * value the customer set directly, either at creation or via
 * {@code PATCH /api/agents/{id}/generation-params}.
 *
 * <p>Switching an agent to {@code SIMPLE} (via
 * {@code PATCH /api/agents/{id}/tuning-mode}, or by requesting it at
 * creation) triggers one background LLM call ({@link
 * GenerationParamsInferenceService}) that reads the agent's system prompt
 * and picks temperature/topP/maxTokens for it - see that class's javadoc.
 * Switching back to {@code MANUAL} leaves whatever values were last set
 * (inferred or otherwise) in place as an editable starting point rather than
 * resetting them; {@code Agent.systemPrompt} is immutable after creation, so
 * SIMPLE mode's inference never needs to be re-run automatically - only when
 * a customer explicitly (re-)opts in.
 */
public enum TuningMode {
    SIMPLE,
    MANUAL
}
