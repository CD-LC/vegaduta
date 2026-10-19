package com.agenticai.core.guardrails.moderation;

/**
 * Phase P6. Which of the three layers reached a given decision - recorded on
 * every audit entry and every review-queue row so a reviewer/auditor can see
 * WHY something fired, not just that it did. The layers run in this order and
 * short-circuit on the first block (see {@link ModerationService}).
 */
public enum ModerationLayer {

    /** Deterministic regex/keyword rules ({@link PolicyPackPatternMatcher}). */
    PATTERN,

    /** LLM-judge confidence scoring ({@link PolicyPackSemanticClassifier}). */
    SEMANTIC,

    /**
     * The pluggable external safety classifier ({@link ExternalSafetyClassifier})
     * for the highest-stakes categories. A match here routes to the RESTRICTED
     * audit path, never the general audit log.
     */
    EXTERNAL_CLASSIFIER
}
