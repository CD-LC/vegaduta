package com.agenticai.core.agents.core;

/**
 * Edge Wave 2, EDGE-B (docs/rewriting-story/04-design/EDGE-AI-STRATEGY.md; ADR-008 decision 1)
 * - the server-declared, per-agent routing policy for on-device (edge) turns, persisted on
 * {@code agent.edge_routing_policy} (migration V190, nullable = no policy configured).
 *
 * <p>Enforcement happens CLIENT-side, in {@code web/app/lib/edge/tierRouter.ts}: edge routing
 * decides which brain answers BEFORE any server call ever happens, so the server's only honest
 * role is declaring the policy (via {@code GET /api/edge/config?agentId=...}) and trusting the
 * shipped client to honor it. That is a real, named limitation, not an oversight: a hostile
 * client could ignore EDGE_FIRST/HOSTED_ONLY, but those two only route MORE traffic to the
 * server (where every existing guardrail/quota gate applies anyway), and EDGE_ONLY's privacy
 * guarantee protects the tenant from the PLATFORM's servers seeing content - a client the user
 * themselves tampered with is outside that threat model.
 *
 * <p>Null (no policy) means the device's own localStorage preferences decide, exactly the
 * pre-V190 behavior. Where a policy is set it takes precedence over device preferences - the
 * fix for the 2026-08-07 "BYOK selected but edge still answers" bug class.
 */
public enum EdgeRoutingPolicy {

    /**
     * Turns for this agent must stay on-device: a local-tier failure surfaces as an error to
     * the person ("this agent is configured to stay on-device") instead of falling through to
     * hosted. The privacy mode healthcare/legal/gov tenants can actually rely on - "on-device
     * is a preference, not a guarantee" stops applying to agents carrying this policy.
     */
    EDGE_ONLY,

    /**
     * Prefer on-device, hosted fallback allowed - today's default behavior, made explicit and
     * server-declared so a tenant can pin it against device-local drift.
     */
    EDGE_FIRST,

    /**
     * Never route locally, regardless of what this device's localStorage preferences say -
     * e.g. an agent whose guardrail/compliance posture requires the full server-side
     * governance stack on every turn.
     */
    HOSTED_ONLY
}
