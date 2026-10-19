package com.agenticai.core.common.diagnostics;

/**
 * Diagnostics build-out (2026-08) - the seam other code (including {@code
 * DiagnosticService}'s two {@code record*} methods, once it is wired to call this -
 * see this package's design note for the exact wiring) uses to turn a raw failure
 * into four masked, audience-appropriate explanations at once. See {@link
 * FailureContext}/{@link FailureExplanation} for the full input/output contract and
 * {@link SensitiveDataMasker} for the masking guarantee that applies to every tier,
 * including {@code EXPERT_TECHNICAL}.
 *
 * <p>Single method returning all four tiers together, not an enum-parameter
 * fetch-one-tier method: masking and exception classification are shared work across
 * all four tiers, so doing that once per failure is strictly cheaper than up to 4x if
 * a caller fetched tiers individually, and the real call sites want more than one
 * tier available from a single failure (e.g. store {@code technical} in a {@code
 * PlatformIssue} detail column, surface {@code basic} in a user-facing notification)
 * without a second round trip.
 */
public interface FailureExplanationService {

    /**
     * Never throws for a well-formed {@link FailureContext} with a non-null {@code
     * exception} - every internal failure mode (a missing template, an LLM
     * error/timeout/malformed response) is caught and degrades to a generic
     * templated explanation rather than propagating, matching this codebase's
     * existing "an observability/explanation path must never become a second
     * failure on top of the original one" posture (see {@code
     * DiagnosticService}'s javadoc).
     */
    FailureExplanation explain(FailureContext context);
}
