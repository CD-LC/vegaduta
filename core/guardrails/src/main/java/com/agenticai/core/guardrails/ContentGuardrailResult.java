package com.agenticai.core.guardrails;

/**
 * Result of a single guardrail evaluation on a piece of content.
 * Indicates whether the content passed the guardrail check and, if not, why.
 */
public class ContentGuardrailResult {

    private final ContentGuardrailType guardrailType;
    private final boolean passed;
    private final String reason;
    private final int confidence; // 0-100: how confident is the violation
    // Non-null ONLY for a redacting pass (see #redact). A guardrail that just
    // approves content leaves this null, which is what every pre-existing
    // implementation does - they are pass/fail only.
    private final String redactedContent;

    private ContentGuardrailResult(
        ContentGuardrailType guardrailType,
        boolean passed,
        String reason,
        int confidence,
        String redactedContent
    ) {
        this.guardrailType = guardrailType;
        this.passed = passed;
        this.reason = reason;
        this.confidence = confidence;
        this.redactedContent = redactedContent;
    }

    /**
     * Creates a passing result - content passed the guardrail check.
     */
    public static ContentGuardrailResult pass(ContentGuardrailType guardrailType) {
        return new ContentGuardrailResult(guardrailType, true, null, 0, null);
    }

    /**
     * Creates a passing result that ALSO rewrites the content - the guardrail
     * found something it did not like but could neutralise in place (a
     * tenant REDACT rule, see {@code TenantGuardrailRuleEvaluator}) rather than
     * having to withhold the whole message.
     *
     * <p>Added because {@link GuardrailRuleAction#REDACT} existed in the data
     * model, and in the tool-output path, with no way to express itself on the
     * chat content path - a REDACT rule authored by a tenant would have been
     * silently inert there. {@link ContentGuardrailEngine} threads the rewritten
     * text through the remaining guardrails and hands it back on
     * {@link ContentGuardrailCheckResult#getSanitizedContent()}.
     *
     * @param redactedContent the rewritten content, never null
     */
    public static ContentGuardrailResult redact(ContentGuardrailType guardrailType, String redactedContent) {
        return new ContentGuardrailResult(guardrailType, true, null, 0, redactedContent);
    }

    /**
     * Creates a failing result - content violated the guardrail.
     *
     * @param guardrailType the guardrail that caught the violation
     * @param reason human-readable explanation (shown to user if blocking)
     * @param confidence 0-100 score indicating violation confidence
     */
    public static ContentGuardrailResult fail(
        ContentGuardrailType guardrailType,
        String reason,
        int confidence
    ) {
        return new ContentGuardrailResult(guardrailType, false, reason, confidence, null);
    }

    public ContentGuardrailType getGuardrailType() {
        return guardrailType;
    }

    public boolean isPassed() {
        return passed;
    }

    public String getReason() {
        return reason;
    }

    public int getConfidence() {
        return confidence;
    }

    /**
     * The rewritten content when this guardrail redacted rather than blocked,
     * or null when it simply passed or failed. Only ever non-null on a passing
     * result - a block withholds the content, it does not rewrite it.
     */
    public String getRedactedContent() {
        return redactedContent;
    }

    @Override
    public String toString() {
        if (passed) {
            return guardrailType + ": PASS";
        }
        return guardrailType + ": FAIL (confidence=" + confidence + ", reason=" + reason + ")";
    }
}
