package com.agenticai.core.common.improve;

/**
 * What kind of fix an {@link AgentImprovementSuggestion} proposes. The two
 * kinds map to the two governance layers the platform can actually change
 * automatically on accept: the agent's own system prompt, and the
 * procedures layer (deterministic business rules between prompt and graph).
 */
public enum ImprovementKind {
    /** Replace the agent's system prompt with {@code suggestedText}. */
    PROMPT_REVISION,
    /** Create a new procedure from {@code suggestedText} and attach it. */
    NEW_PROCEDURE
}
