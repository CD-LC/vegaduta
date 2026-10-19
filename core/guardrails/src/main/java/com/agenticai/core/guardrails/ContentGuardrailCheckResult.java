package com.agenticai.core.guardrails;

/**
 * Result of running content through the guardrail engine. Indicates whether
 * the content passed all checks or was blocked by a specific guardrail.
 */
public class ContentGuardrailCheckResult {

    private final boolean passed;
    private final ContentGuardrailType blockedByGuardrail;
    private final String blockReason;
    private final int confidence;
    // The content callers should actually use going forward: the original text
    // unless a guardrail redacted part of it in place (see
    // ContentGuardrailResult#redact). Null only on the no-content pass()
    // shorthand, which pre-dates redaction and is kept for callers that have
    // nothing to hand back.
    private final String sanitizedContent;

    private ContentGuardrailCheckResult(
        boolean passed,
        ContentGuardrailType blockedByGuardrail,
        String blockReason,
        int confidence,
        String sanitizedContent
    ) {
        this.passed = passed;
        this.blockedByGuardrail = blockedByGuardrail;
        this.blockReason = blockReason;
        this.confidence = confidence;
        this.sanitizedContent = sanitizedContent;
    }

    /**
     * Creates a passing result - content passed all guardrail checks.
     */
    public static ContentGuardrailCheckResult pass() {
        return new ContentGuardrailCheckResult(true, null, null, 0, null);
    }

    /**
     * Creates a passing result carrying the content to use going forward -
     * identical to what was passed in unless a guardrail redacted part of it.
     * Callers that can act on a rewrite (the agent-output path) should read
     * {@link #getSanitizedContent()} rather than reusing their own copy.
     */
    public static ContentGuardrailCheckResult pass(String sanitizedContent) {
        return new ContentGuardrailCheckResult(true, null, null, 0, sanitizedContent);
    }

    /**
     * Creates a blocked result - content violated a guardrail.
     *
     * @param guardrailType the guardrail that blocked the content
     * @param reason human-readable explanation
     * @param confidence 0-100 score indicating violation confidence
     */
    public static ContentGuardrailCheckResult blocked(
        ContentGuardrailType guardrailType,
        String reason,
        int confidence
    ) {
        return new ContentGuardrailCheckResult(false, guardrailType, reason, confidence, null);
    }

    /**
     * Returns true if content passed all guardrail checks.
     */
    public boolean isPassed() {
        return passed;
    }

    /**
     * Returns the guardrail that blocked this content, or null if passed.
     */
    public ContentGuardrailType getBlockedByGuardrail() {
        return blockedByGuardrail;
    }

    /**
     * Returns the reason the content was blocked, or null if passed.
     */
    public String getBlockReason() {
        return blockReason;
    }

    /**
     * Returns the confidence (0-100) that this is a true violation, or 0 if passed.
     */
    public int getConfidence() {
        return confidence;
    }

    /**
     * The content to use going forward on a pass - the original text, or the
     * rewritten text when a guardrail redacted part of it in place. Null when
     * the result was built by the no-content {@link #pass()} shorthand or when
     * the content was blocked outright; callers must fall back to their own
     * copy in that case.
     */
    public String getSanitizedContent() {
        return sanitizedContent;
    }

    @Override
    public String toString() {
        if (passed) {
            return "ContentGuardrailCheckResult(PASSED)";
        }
        return "ContentGuardrailCheckResult(BLOCKED by " + blockedByGuardrail
            + ", confidence=" + confidence + ", reason=" + blockReason + ")";
    }
}
