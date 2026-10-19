package com.agenticai.core.guardrails.moderation;

/**
 * Phase P6. The resolved, effective configuration of one policy pack for one
 * {@link ModerationContext}, as computed by {@link ModerationPolicyService}
 * from the layered {@code platform_setting} rows.
 *
 * @param enabled whether this pack applies at all for this tenant/plan
 * @param semanticEnabled whether the optional LLM-judge layer runs for this
 *     pack (the pattern layer always runs when the pack is enabled)
 * @param reviewThreshold 0-100; confidence at/above this but below {@code
 *     blockThreshold} queues the content for human review
 * @param blockThreshold 0-100; confidence at/above this blocks outright
 */
public record PolicyPackConfig(
    boolean enabled,
    boolean semanticEnabled,
    int reviewThreshold,
    int blockThreshold
) {

    public static PolicyPackConfig disabled() {
        return new PolicyPackConfig(false, false, 100, 100);
    }
}
