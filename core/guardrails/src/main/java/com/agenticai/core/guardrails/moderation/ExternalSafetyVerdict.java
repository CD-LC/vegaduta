package com.agenticai.core.guardrails.moderation;

/**
 * Phase P6. The result of an {@link ExternalSafetyClassifier} check for the
 * highest-stakes categories.
 *
 * <p>Deliberately carries NO raw content and NO free-text describing what was
 * found - only a coarse category label, a confidence, and an {@code
 * opaqueReference} the vendor uses to correlate this call with its own
 * (access-controlled) record. This is a safety property: a CSAM/terrorism
 * verdict must not scatter detail through ordinary object graphs, logs, or
 * exception messages. Everything sensitive stays on the vendor's side behind
 * {@code opaqueReference}; the restricted audit path (see {@link
 * ModerationRestrictedAuditService}) records only this shape.
 *
 * @param matched whether the classifier flagged the content at all
 * @param category coarse label for the matched category (e.g. {@code
 *     "csam"}, {@code "credible_violence"}); {@code null}/blank when not
 *     matched
 * @param confidence 0-100; 0 when not matched
 * @param opaqueReference vendor-side correlation id for follow-up/reporting -
 *     NOT the content, NOT a description; may be {@code null}
 */
public record ExternalSafetyVerdict(
    boolean matched,
    String category,
    int confidence,
    String opaqueReference
) {

    private static final ExternalSafetyVerdict NO_MATCH = new ExternalSafetyVerdict(false, null, 0, null);

    /** The verdict every safe/unavailable path returns - shared, allocation-free. */
    public static ExternalSafetyVerdict noMatch() {
        return NO_MATCH;
    }
}
