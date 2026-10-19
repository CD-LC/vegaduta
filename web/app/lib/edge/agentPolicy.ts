// Edge Wave 2 — EDGE-B (docs/rewriting-story/04-design/EDGE-AI-STRATEGY.md;
// ADR-008 decision 1): the client half of the per-agent edge policy lookup.
// One GET /api/edge/config?agentId=... per agent per TTL window answers, in a
// single round trip, everything tierRouter needs to know BEFORE a turn runs:
//   - the server-declared routing policy (EDGE_ONLY | EDGE_FIRST |
//     HOSTED_ONLY, or null = "no policy, device preferences decide");
//   - whether the agent has guardrails enabled, and whether the on-device
//     pattern tier (edgeGuardrails.ts) can represent ALL of them.
//
// The endpoint stays permitAll (the anonymous /try surface reads `enabled`
// pre-sign-in) but the per-agent `agent` field is only populated for an
// authenticated caller that can actually run the agent — see
// EdgeAgentsConfigController#getConfig + EdgeAgentPolicyLookupService. An
// anonymous/unauthorized caller (and any fetch failure) resolves to null
// here, which tierRouter treats as "no policy, no guardrail knowledge" — the
// exact pre-Wave-2 behavior, so a server that hasn't deployed this wave yet
// degrades cleanly (feature-detected, same posture as fetchTier1Manifest).
//
// Never rejects. Fire-and-forget failures fall back to null; the router's
// graceful-degradation contract covers the rest.

import { API_BASE } from "../api";
// setAgentEdgeRoutingPolicy below is a normal authenticated agent-management
// write (unlike fetchEdgeAgentPolicy's permitAll GET), so it needs the same
// Bearer token every other agent PATCH sends. api.ts's own apiFetch isn't
// exported (private to that module's giant `api` object), so this reads the
// token from the same source apiFetch itself does rather than duplicating
// api.ts's fetch machinery.
import { auth } from "../auth";
import { viewAsHeaders } from "../viewAs";

export type EdgeRoutingPolicy = "EDGE_ONLY" | "EDGE_FIRST" | "HOSTED_ONLY";

export interface EdgeAgentPolicyInfo {
  /** Server-declared routing policy; null = no policy configured. */
  policy: EdgeRoutingPolicy | null;
  /** Agent.enabledGuardrails non-empty. */
  guardrailsEnabled: boolean;
  /** Every enabled guardrail is representable by edgeGuardrails.ts's pattern
   * tier (currently pii + jailbreak). False => route hosted (honest
   * degradation — see the strategy doc's EDGE-A tradeoffs). */
  guardrailsPatternRepresentable: boolean;
}

/** Short TTL so a policy change an admin just saved takes effect within a
 * minute on live sessions without a per-turn round trip — same freshness
 * posture as the manifest's 5-minute public cache, tighter because policy is
 * a governance control, not asset metadata. */
const POLICY_TTL_MS = 60_000;

interface CacheEntry {
  at: number;
  value: Promise<EdgeAgentPolicyInfo | null>;
}

const cache = new Map<string, CacheEntry>();

function coercePolicy(v: unknown): EdgeRoutingPolicy | null {
  return v === "EDGE_ONLY" || v === "EDGE_FIRST" || v === "HOSTED_ONLY" ? v : null;
}

/**
 * The per-agent edge policy/guardrail facts, TTL-cached per agentId per tab.
 * Resolves null (never rejects) for: SSR, fetch failure, a pre-Wave-2 server,
 * an unauthenticated caller, or an agent this caller can't run — all
 * deliberately indistinguishable (the server's own anti-enumeration rule).
 */
export function fetchEdgeAgentPolicy(agentId: string): Promise<EdgeAgentPolicyInfo | null> {
  if (!agentId || typeof window === "undefined" || typeof fetch === "undefined") {
    return Promise.resolve(null);
  }
  const now = Date.now();
  const cached = cache.get(agentId);
  if (cached && now - cached.at < POLICY_TTL_MS) return cached.value;
  const value = (async (): Promise<EdgeAgentPolicyInfo | null> => {
    try {
      // Silent token: the per-agent `agent` field is auth-gated server-side
      // (Bearer JWT or X-Device-Token — never cookies), so send the Keycloak
      // token when one exists and fall through anonymous when it doesn't
      // (/try). NOT `credentials: "include"`: that switches the browser into
      // credentialed-CORS mode, which core deliberately rejects
      // (WebConfig#allowCredentials(false)) — it blocked this call entirely
      // on the deployed domains, and cookies were never how this endpoint
      // authenticates anyway.
      const token = await auth.getToken({ silent: true });
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), 5_000);
      try {
        const res = await fetch(`${API_BASE}/api/edge/config?agentId=${encodeURIComponent(agentId)}`, {
          headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...viewAsHeaders() },
          signal: controller.signal,
        });
        if (!res.ok) return null;
        const body = (await res.json()) as { agent?: unknown };
        const agent = body && typeof body === "object" ? (body.agent as Record<string, unknown> | null) : null;
        if (!agent || typeof agent !== "object") return null;
        return {
          policy: coercePolicy(agent.edgeRoutingPolicy),
          guardrailsEnabled: agent.guardrailsEnabled === true,
          guardrailsPatternRepresentable: agent.guardrailsPatternRepresentable === true,
        };
      } finally {
        clearTimeout(timer);
      }
    } catch {
      return null;
    }
  })();
  cache.set(agentId, { at: now, value });
  return value;
}

/** Test/console hook — drop the cache so the next lookup refetches. */
export function resetEdgeAgentPolicyCache(): void {
  cache.clear();
}

/**
 * Audit finding P1: the write half of fetchEdgeAgentPolicy above —
 * PATCH /api/agents/{agentId}/edge-routing-policy
 * (AgentController#setEdgeRoutingPolicy,
 * dto/AgentEdgeRoutingPolicyUpdateRequest), which existed on the backend
 * with no product surface calling it anywhere. `policy: null` clears the
 * override back to "device preferences decide" (EDGE_FIRST-equivalent) —
 * the same null-clears contract the request DTO's own doc comment
 * describes; there is deliberately no separate "clear" function, a caller
 * just passes null. Drops this agent's fetchEdgeAgentPolicy cache entry on
 * success so the next read (including previewRouting()'s policy fetch,
 * routingState.ts) sees the fresh value immediately rather than the stale,
 * up-to-60s-old cached one.
 */
export async function setAgentEdgeRoutingPolicy(agentId: string, policy: EdgeRoutingPolicy | null): Promise<void> {
  const token = await auth.getToken();
  const res = await fetch(`${API_BASE}/api/agents/${encodeURIComponent(agentId)}/edge-routing-policy`, {
    method: "PATCH",
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...viewAsHeaders(),
    },
    body: JSON.stringify({ edgeRoutingPolicy: policy }),
  });
  if (!res.ok) {
    throw new Error(`Failed to update this agent's routing policy (${res.status})`);
  }
  cache.delete(agentId);
}
