package com.agenticai.core.brief.dto;

/**
 * One line of the readiness rail, shape fixed by the Earned Autonomy contract (section 2) and
 * consumed verbatim by the web rail. {@code kind}: SECRET | CONNECTOR | KNOWLEDGE | CHANNEL |
 * PLAN_LIMIT | PERMISSION | GUARDRAIL. {@code state}: READY | MISSING | WAITING_ON_ADMIN | GATED |
 * SIMULATED. {@code reason} is an honest human sentence; {@code actionHref} deep-links to the
 * control that fixes it (may be null); {@code unlocksAt} names the plan that lifts a GATED row
 * (may be null). Secret rows carry the secret NAME only - never a value or preview.
 */
public record ReadinessRow(
    String kind,
    String ref,
    String label,
    String state,
    String reason,
    String actionHref,
    String unlocksAt
) {
    public static final String READY = "READY";
    public static final String MISSING = "MISSING";
    public static final String WAITING_ON_ADMIN = "WAITING_ON_ADMIN";
    public static final String GATED = "GATED";
    public static final String SIMULATED = "SIMULATED";
}
