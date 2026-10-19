package com.agenticai.core.agents.version;

/**
 * Body of {@code PUT /api/agents/{agentId}/versions/{versionId}/label}.
 *
 * <p>Deliberately NOT {@code @NotNull}: {@code null} is the documented way to
 * CLEAR a label from this version, the same "null is a target state, not a
 * missing value" convention {@code AgentEdgeRoutingPolicyUpdateRequest} and
 * {@code AgentFallbackUpdateRequest} already use on this resource. Bean validation
 * would make "unlabel this version" unexpressible without a second endpoint.
 *
 * <p>An unknown string still fails at deserialization with a 400 naming the valid
 * values, because the field is typed as the enum rather than as {@code String} -
 * so the permissiveness here is exactly "null is allowed", not "anything is".
 *
 * <p>{@code force}/{@code reason} are the eval-gate override (V260): when a
 * PRODUCTION promote is blocked by the gate, a caller holding the
 * MANAGE_AGENTS scope may pass {@code force=true} plus a non-blank
 * {@code reason}; the override is written to the audit log. Both fields are
 * ignored for STAGING/null labels and for promotes the gate allows.
 */
public record AgentVersionLabelUpdateRequest(AgentVersionLabel label, Boolean force, String reason) {
}
