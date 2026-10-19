package com.agenticai.core.quality;

/**
 * Types of evaluations that can run on agent responses. Each type measures
 * a specific dimension of response quality.
 *
 * FREE tier gets: SAFETY, HELPFULNESS (basic only)
 * PREMIUM tier gets: all evaluations
 */
public enum EvaluationType {
    /**
     * Does the response help answer the user's question?
     * Runs on every tier.
     */
    HELPFULNESS("Helpfulness", 0.5, true),

    /**
     * Is the response factually correct? Uses LLM-as-judge.
     * Premium only.
     */
    ACCURACY("Accuracy", 0.5, false),

    /**
     * Is the tone appropriate for the agent's configured persona?
     * Premium only.
     */
    TONE("Tone", 0.5, false),

    /**
     * Is the response length appropriate? Not too short, not too long.
     * Premium only.
     */
    LENGTH("Length", 0.5, false),

    /**
     * Does the response stay on topic and address the user's query?
     * Premium only.
     */
    RELEVANCE("Relevance", 0.5, false),

    /**
     * Does the response follow guardrails and safety policies?
     * Runs on every tier.
     */
    SAFETY("Safety", 0.5, true),

    /**
     * Are the response's claims supported by the documents that were
     * retrieved for this turn (RAG groundedness)? LLM-as-judge against
     * {@link EvaluationContext#retrievedContext()}; reports UNAVAILABLE
     * ("not applicable"), without calling the judge, when the turn had no
     * retrieved context - so it never contributes a score to a non-RAG turn.
     * Premium only.
     */
    FAITHFULNESS("Faithfulness", 0.5, false);

    private final String displayName;
    private final double defaultWeight;
    private final boolean freeAvailable;

    EvaluationType(String displayName, double defaultWeight, boolean freeAvailable) {
        this.displayName = displayName;
        this.defaultWeight = defaultWeight;
        this.freeAvailable = freeAvailable;
    }

    public String getDisplayName() {
        return displayName;
    }

    public double getDefaultWeight() {
        return defaultWeight;
    }

    public boolean isFreeAvailable() {
        return freeAvailable;
    }

    /**
     * Whether this dimension is scored by the LLM judge. {@link #LENGTH} is
     * the one local heuristic (word counts, no model call), so it still
     * produces a number when the judge is down - which is exactly why an
     * all-judges-failed case must not be reported with that number alone.
     */
    public boolean isLlmJudged() {
        return this != LENGTH;
    }
}
