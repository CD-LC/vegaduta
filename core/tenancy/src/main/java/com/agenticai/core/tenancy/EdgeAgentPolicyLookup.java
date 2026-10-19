package com.agenticai.core.tenancy;

import java.security.Principal;
import java.util.UUID;

/**
 * Edge Wave 2 (EDGE-B/EDGE-A, docs/rewriting-story/04-design/EDGE-AI-STRATEGY.md) - the
 * dependency-inversion seam that lets {@link EdgeAgentsConfigController} (this module) answer a
 * per-agent question whose data lives in {@code core/agents} (the {@code Agent} entity's
 * {@code edgeRoutingPolicy} and {@code enabledGuardrails}), which this module cannot depend on:
 * the Maven dependency arrow points {@code agents -> tenancy}, never the reverse. The interface
 * lives here, the one implementation ({@code EdgeAgentPolicyLookupService}) lives in
 * {@code core/agents}, and the controller injects it via {@code ObjectProvider} so a
 * tenancy-only context (unit tests, hypothetical partial deployments) still boots - the
 * controller then simply answers "no per-agent data" rather than failing.
 *
 * <p>WHY this exists at all instead of a separate endpoint in {@code core/agents}: the client's
 * tier router wants policy + guardrail gating in the SAME single pre-turn lookup it already
 * makes ({@code GET /api/edge/config}), and splitting it across two endpoints would double the
 * per-conversation round-trips for data that is always consumed together.
 */
public interface EdgeAgentPolicyLookup {

    /**
     * Per-agent edge-governance facts for an authenticated, agent-resolvable caller.
     *
     * <p>Returns {@code null} - never throws - when the caller presents no usable credential
     * (neither a real Keycloak session nor a valid {@code X-Device-Token}), or when the agent is
     * missing / cross-tenant / not shared with this caller. A null result is deliberately
     * indistinguishable across those cases (same anti-enumeration posture as the edge tool
     * endpoints' 404-not-403 rule): {@code /api/edge/config} stays permitAll for the anonymous
     * {@code /try} surface, so this method is the only gate between an unauthenticated probe and
     * per-agent configuration facts.
     */
    EdgeAgentPolicyView lookup(UUID agentId, Principal principal, String deviceToken);

    /**
     * The safe per-agent subset the edge client needs BEFORE a turn runs. {@code
     * edgeRoutingPolicy} is the raw enum name ({@code EDGE_ONLY | EDGE_FIRST | HOSTED_ONLY}) or
     * null for "no policy configured" (client device preferences then decide, exactly as before
     * this field existed). {@code guardrailsEnabled} is {@code Agent.enabledGuardrails}
     * non-empty; {@code guardrailsPatternRepresentable} is whether EVERY enabled guardrail can
     * be represented by the client's pattern tier (see {@code web/app/lib/edge/edgeGuardrails.ts}
     * - currently the regex-portable {@code pii}/{@code jailbreak} pair). Deliberately booleans,
     * not the guardrail id list: the client only needs "can I govern this locally or must I
     * route hosted", and not leaking the exact guardrail configuration through a permitAll
     * endpoint keeps the exposed surface minimal.
     */
    record EdgeAgentPolicyView(
        String edgeRoutingPolicy,
        boolean guardrailsEnabled,
        boolean guardrailsPatternRepresentable
    ) {
    }
}
