package com.agenticai.core.guardrails.moderation;

/**
 * Phase P6 - the pluggable seam for the highest-stakes safety categories:
 * CSAM and credible violence/terrorism.
 *
 * <p><b>Why this is an interface with a no-op default and nothing more.</b>
 * Detecting these categories is not a regex or an LLM-judge prompt this
 * codebase can responsibly ship. It carries hard <em>legal reporting
 * obligations</em> (in the US, CSAM must be reported to NCMEC; comparable
 * duties exist in other jurisdictions), demands a vetted specialist vendor
 * (e.g. hash-matching against known-CSAM corpora that only authorized
 * organizations may hold), and must be built with counsel. Shipping a
 * home-grown detector for these would be both ineffective and legally
 * reckless. So this platform ships the SEAM - the interface, the wiring, and
 * a clearly-documented no-op default - and leaves the real implementation to
 * be dropped in by a deployment that has done the legal + vendor work.
 *
 * <p><b>Contract for a real implementation</b> (see {@link
 * NoOpExternalSafetyClassifier} for the shape):
 * <ul>
 *   <li>Return {@link ExternalSafetyVerdict#matched()} == true only for a
 *       genuine highest-stakes match; everything else is
 *       {@link ExternalSafetyVerdict#noMatch()}.</li>
 *   <li>Put NOTHING sensitive in the returned verdict - no raw content, no
 *       description. Only a coarse category, a confidence, and an opaque
 *       vendor reference. See {@link ExternalSafetyVerdict}'s javadoc.</li>
 *   <li>A match is NEVER auto-blocked-and-forgotten by the caller: {@link
 *       ModerationService} routes it to the RESTRICTED audit path ({@link
 *       ModerationRestrictedAuditService}) and to human/escalation handling,
 *       never the general audit log. This layer's job is detection + secure
 *       hand-off, not silent enforcement.</li>
 *   <li>Fail safe for availability but never fail <em>silent</em>: an outage
 *       must be surfaced to operators, because "the highest-stakes detector
 *       was down" is itself a fact that matters. {@link ModerationService}
 *       treats an exception here as a non-match for the content flow (so a
 *       vendor outage never wrongly blocks ordinary content) but records the
 *       failure.</li>
 * </ul>
 *
 * <p><b>REQUIRES LEGAL + VENDOR SIGN-OFF before any non-no-op implementation
 * is enabled.</b> Do not wire a real classifier here without it.
 */
public interface ExternalSafetyClassifier {

    /**
     * @param content the content to classify - passed to the (out-of-process,
     *     vendor) classifier; a real implementation must transmit and retain
     *     it per its counsel-approved data-handling policy
     * @param context who/what produced the content, for the vendor's own
     *     record-keeping and any mandated report; carries no secrets
     * @return a {@link ExternalSafetyVerdict}; {@link
     *     ExternalSafetyVerdict#noMatch()} when nothing fired
     */
    ExternalSafetyVerdict classify(String content, ModerationContext context);

    /**
     * Whether this classifier is a real, sign-off-gated implementation or the
     * shipped no-op. {@link ModerationService} uses this only to decide
     * whether the external layer is even worth invoking and how to describe
     * itself in audit detail - it is NOT an enable switch (that is the
     * admin-configured {@code moderation.external-classifier.enabled}
     * setting). Defaults to {@code false} so the no-op is honest by omission.
     */
    default boolean isOperational() {
        return false;
    }
}
