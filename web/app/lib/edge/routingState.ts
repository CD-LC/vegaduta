"use client";

// Reliability blueprint Phase 3 (docs/RELIABILITY-BLUEPRINT-2026-08-06-PART1.md
// Part 4 — Model routing rebuild; handoff at
// docs/RELIABILITY-BLUEPRINT-2026-08-06-PHASE3-HANDOFF.md).
//
// THE single reader/writer of AI-routing preference. Before this module,
// three localStorage keys (capabilities.ts's MODEL_OVERRIDE_KEY/
// EDGE_OPT_IN_KEY/EDGE_ROUTING_PREF_KEY) plus a server-side tenant setting
// (ProvidersPanel.tsx's usePlatformLlm) were read independently by
// IntelligenceSourceControl.tsx, AgentWorkbench.tsx, and ProvidersPanel.tsx —
// this module does NOT replace those three localStorage keys or the
// server setting (capabilities.ts's getters/setters stay the source of
// truth for the raw values — routeTurn/resolveTier already depend on them
// and this phase explicitly must not change that decision logic), it
// UNIFIES how every UI surface reads and interprets them, so "what mode is
// this tenant/device in right now" has exactly one answer instead of three
// screens computing it slightly differently.
//
// THREE modes now (was four — "edge" removed 2026-08-08 as dead code, see
// below), as a discriminated union. Honest status, updated for Edge Wave 2
// (EDGE-B — this comment used to say a real edge-only mode didn't exist
// anywhere): a true "on-device, error out rather than fall back" mode NOW
// EXISTS, but it is a SERVER-declared, per-agent policy
// (Agent.edgeRoutingPolicy = EDGE_ONLY, fetched by tierRouter via
// GET /api/edge/config?agentId=..., and now also by previewRouting() below)
// — NOT a device-local preference this module can set. Deliberately so:
// "this agent's turns never leave the device" is a tenant governance
// guarantee, and a guarantee you could toggle off from any browser's
// localStorage would not be one — the 2026-08-07 "BYOK selected but edge
// still answers" bug was exactly a device-local flag outranking tenant
// intent, and server-policy-precedence is its fix.
//
// Dead-code removal (2026-08-08 audit finding P2): this module used to also
// expose a device-local "edge" mode, identical in every behavior to
// "hybrid" (both resolved to the same edge-first preference, same device
// side effects) and permanently unreachable from any real UI —
// getRoutingMode() always hardcoded "hybrid" for the edge-first path below,
// and no call site anywhere passed "edge" to setRoutingMode(). Removed
// rather than kept as a 4th, unreachable union member; "hybrid" is now the
// one and only device-local on-device preference. The SERVER-side EDGE_ONLY
// policy described above is a completely different mechanism (a governance
// guarantee, not a device preference) and is UNAFFECTED by this removal —
// see previewRouting()'s own comment for where that policy is now honored.

import {
  detectCapabilities,
  edgeEnabled,
  getEdgeOptIn,
  getEdgeRoutingPreference,
  getStoredModelOverride,
  resolveTier,
  setEdgeOptIn,
  setEdgeRoutingPreference,
  setModelOverride,
  EDGE_PREFS_EVENT,
  type EdgeCapabilities,
  type EdgeRoutingPreference,
  type ModelOverride,
  type TierReadiness,
} from "./capabilities";
import { getTier1RunModel } from "./tier1";
// Wave 2 fix (audit finding P0): the preview below used to compute its
// answer purely from tenant/device preferences, never consulting the same
// server-declared per-agent policy tierRouter.ts's routeTurn() itself checks
// FIRST (agentPolicy.ts's fetchEdgeAgentPolicy — see routeTurn()'s own
// Wave-2-step-0 comment). Reused, not reimplemented — same TTL-cached GET
// tierRouter.ts already calls.
import { fetchEdgeAgentPolicy, type EdgeAgentPolicyInfo } from "./agentPolicy";

// ---------------------------------------------------------------------------
// The four modes
// ---------------------------------------------------------------------------

export type RoutingMode = "hosted" | "byok" | "hybrid";

export interface RoutingModeOption {
  mode: RoutingMode;
  label: string;
  description: string;
}

/** Display metadata for the three modes — the one place UI copy for mode
 * names lives, so IntelligenceSourceControl/AgentWorkbench/ProvidersPanel
 * don't each invent their own labels. */
export const ROUTING_MODES: Record<RoutingMode, RoutingModeOption> = {
  hosted: {
    mode: "hosted",
    label: "Hosted",
    description: "Always uses the platform's hosted model pool.",
  },
  byok: {
    mode: "byok",
    label: "Your own provider",
    description: "Uses this tenant's own configured LLM provider (BYOK) instead of the platform pool.",
  },
  hybrid: {
    mode: "hybrid",
    label: "Hybrid",
    description: "On-device first with automatic hosted fallback — today's default.",
  },
};

// ---------------------------------------------------------------------------
// Tenant-level BYOK signal — the one piece routingState.ts does NOT own the
// storage for (it's a server round-trip owned by ProvidersPanel.tsx's
// GET/PUT /api/tenant/settings + GET /api/providers/platform-llm), but every
// mode computation needs to know it. Callers pass it in rather than this
// module re-fetching it, so there is still exactly one network-owning
// component (ProvidersPanel.tsx) — this module only consumes the result.
// ---------------------------------------------------------------------------

export interface TenantProviderState {
  /** ProvidersPanel.tsx's usePlatformLlm — false means the tenant has opted
   * into using its own configured provider(s) instead of the platform pool. */
  usePlatformLlm: boolean;
  /** How many BYOK providers this tenant actually has configured and active.
   * A tenant can have usePlatformLlm=false with zero providers configured
   * (e.g. mid-setup, or a removed provider) — see resolveByokFallback below. */
  activeProviderCount: number;
}

/** Undefined-in-the-original-audit case, decided and documented here per the
 * blueprint's explicit instruction ("decide and document what [BYOK with no
 * configured provider] falls back to"): fall back to the platform pool. The
 * least-surprising choice — a tenant who removed/never configured a
 * provider should never lose the ability to chat entirely; erroring out
 * would be a harder failure than a paid tenant briefly seeing the platform
 * pool bill against them for one turn. This is a product policy call, not a
 * technical constraint — revisit with the maintainers if a different default is
 * wanted (e.g. hard error to make a broken BYOK setup impossible to miss). */
export function resolveByokFallback(): RoutingMode {
  return "hosted";
}

// ---------------------------------------------------------------------------
// Current mode — one function, replaces three screens each re-deriving this
// ---------------------------------------------------------------------------

/**
 * What mode is this tenant/device configured for RIGHT NOW, given the raw
 * preference values capabilities.ts already owns plus the tenant's BYOK
 * state. Pure/synchronous — does not probe device capabilities (see
 * previewRouting() below for "what will this turn actually resolve to").
 */
export function getRoutingMode(tenant: TenantProviderState): RoutingMode {
  // A stored "hosted" model override, or an explicit hosted-only routing
  // preference, always wins — mirrors routeTurn()'s own precedence
  // (getStoredModelOverride() === "hosted" is checked before
  // getEdgeRoutingPreference() there too; kept in the same order here).
  if (getStoredModelOverride() === "hosted") return "hosted";
  if (getEdgeRoutingPreference() === "hosted-only") return "hosted";

  // BYOK: tenant has opted out of the platform pool.
  if (!tenant.usePlatformLlm) {
    return tenant.activeProviderCount > 0 ? "byok" : resolveByokFallback();
  }

  // Edge-first preference (the default) resolves to "hybrid" — the one and
  // only device-local on-device mode (see header comment: the old separate
  // "edge" mode was dead code, removed 2026-08-08).
  if (getEdgeRoutingPreference() === "edge-first" && getEdgeOptIn()) return "hybrid";

  return "hosted";
}

/** Subscribe to mode changes. Fires once immediately, then on every
 * capabilities.ts EDGE_PREFS_EVENT — that event already fires from every
 * setter (setModelOverride/setEdgeRoutingPreference/setEdgeOptIn), so this
 * is a thin re-derive, not a second event system. Tenant state changes
 * (ProvidersPanel's own state) are the caller's responsibility to re-invoke
 * this with fresh `tenant` — this module doesn't own that fetch. */
export function subscribeRoutingMode(
  tenant: TenantProviderState,
  listener: (mode: RoutingMode) => void
): () => void {
  if (typeof window === "undefined") return () => {};
  const fire = () => listener(getRoutingMode(tenant));
  fire();
  window.addEventListener(EDGE_PREFS_EVENT, fire);
  return () => window.removeEventListener(EDGE_PREFS_EVENT, fire);
}

// ---------------------------------------------------------------------------
// Setting the mode — the ONLY place UI should call
// setModelOverride/setEdgeRoutingPreference/setEdgeOptIn directly for the
// purpose of switching modes (ProvidersPanel's usePlatformLlm PUT is a
// separate, tenant-level action still owned by ProvidersPanel.tsx — this
// function does not touch the server).
// ---------------------------------------------------------------------------

/** Bug fix (2026-08-06, flagged during Phase 3 review): the edge/hybrid and
 * byok branches used to call `setModelOverride("auto")` unconditionally,
 * which silently threw away a user's pinned tier-1 model id (e.g.
 * "Llama-3.2-3B-Instruct-q4f16_1-MLC", set via the model picker rows —
 * those call the re-exported `setModelOverride` directly, independent of
 * this function) every time they left and returned to on-device mode. The
 * ONLY value that actually needs clearing here is `"hosted"` — per
 * getRoutingMode()'s precedence, a stored `"hosted"` override always wins
 * over everything else (mirroring routeTurn()'s own precedence), so it
 * would silently block the new mode from ever taking effect if left in
 * place. Any other stored value — `"auto"` or a specific pinned model id —
 * is compatible with every mode and is never a source-of-truth conflict, so
 * it's left untouched, preserving the user's pin across mode switches. */
export function setRoutingMode(mode: RoutingMode): void {
  const clearHostedPin = () => {
    if (getStoredModelOverride() === "hosted") setModelOverride("auto");
  };
  switch (mode) {
    case "hosted":
      setModelOverride("hosted");
      setEdgeRoutingPreference("hosted-only");
      // Bug fix (2026-08-07, live user report: "byok is selected, but still
      // edge is responding all requests"): getEdgeOptIn() is EDGE_OPT_IN_KEY
      // — a single global-per-device flag, not scoped per agent or per
      // routing mode. It's an explicit tri-state ("1"/"0" always wins over
      // the routing-preference-derived default — see its own doc comment),
      // so once anything on this device ever turned on-device ON (even for
      // a different agent, any time in the past), that "1" persists and
      // keeps winning independent of routingPref. routeTurn() only special-
      // cases "hosted-only" explicitly (checked right above getEdgeOptIn()),
      // so "hosted" mode was already accidentally safe from this — but
      // explicitly clearing it here too removes the landmine for good
      // rather than depending on that check never moving/changing.
      setEdgeOptIn(false);
      return;
    case "hybrid":
      clearHostedPin();
      setEdgeRoutingPreference("edge-first");
      setEdgeOptIn(true);
      return;
    case "byok":
      // BYOK itself is the tenant-level usePlatformLlm=false toggle
      // (ProvidersPanel.tsx's server call) — this only clears a "hosted"
      // pin so the tenant's BYOK choice isn't shadowed by a stale
      // per-device override (see clearHostedPin's doc above).
      clearHostedPin();
      setEdgeRoutingPreference("ask");
      // Bug fix (2026-08-07, live user report — see the "hosted" branch's
      // comment above for the full mechanism): UNLIKE "hosted-only",
      // routeTurn() has no special case at all for routingPref "ask" — it
      // falls straight through to the getEdgeOptIn() check, which a stale
      // explicit "1" from an EARLIER on-device session (this device, any
      // agent) satisfies regardless of the person just now explicitly
      // choosing "Your own provider". Without this line, BYOK could stay
      // silently shadowed by on-device indefinitely — the UI honestly says
      // "Your request will be processed by Your own provider" while every
      // reply actually comes back tagged ON-DEVICE. Explicitly turning
      // opt-in off here is what actually makes "ask" mean "nothing runs
      // on-device until you turn it on yourself" (its own documented
      // promise in EDGE_BRANDING.routingPrefAskSubtitle) true again.
      setEdgeOptIn(false);
      return;
    default: {
      const _exhaustive: never = mode;
      void _exhaustive;
    }
  }
}

// Re-exported so a UI surface that only needs the raw model-override picker
// (a specific manifest model id, not one of the four modes) doesn't also
// need to import capabilities.ts directly — keeps "no UI component reads
// capabilities.ts's storage functions directly" a clean, checkable rule.
export { getStoredModelOverride, setModelOverride, getEdgeOptIn, type ModelOverride, type EdgeRoutingPreference };

// ---------------------------------------------------------------------------
// Routing preview (Part 4 requirement 4) — "Your request will be processed
// by {X}" BEFORE the user hits send, using the SAME resolution logic
// routeTurn() uses internally, without executing a turn. This deliberately
// duplicates routeTurn()'s early precedence checks (hosted-override /
// routing-preference / opt-in) rather than importing tierRouter.ts, because
// tierRouter.ts imports tier0.ts/tier1.ts which pull in @mlc-ai/web-llm's
// dynamic-import boundary and session-management state — this module must
// stay a cheap, side-effect-free preview callable on every keystroke/render,
// not something that risks touching a live session. It calls the exact same
// exported primitives (resolveTier, getTier1RunModel) routeTurn() calls, in
// the same order, so the ANSWER matches even though the call path differs.
//
// Wave 2 fix (audit finding P0): also duplicates routeTurn()'s Wave 2 step 0
// — the server-declared per-agent policy fetch (fetchEdgeAgentPolicy) — in
// the same precedence order routeTurn() itself uses (tierRouter.ts's
// routeTurn(), ~lines 308-406): policy is checked BEFORE tenant/device
// preference, and a HOSTED_ONLY/guardrails-forced-hosted policy or an
// EDGE_ONLY policy with no local model ready produce a policy-accurate
// answer instead of a device-preference-only one that can promise the
// opposite of what routeTurn will actually do. Callers that don't have an
// agentId yet (a draft agent, or a call site that predates this fix) pass
// none and get exactly the pre-fix, device/tenant-only behavior — this is a
// strictly additive, backward-compatible change.
// ---------------------------------------------------------------------------

export type RoutingPreview =
  | { mode: RoutingMode; tier: 0 | 1; label: string; policyForced?: boolean }
  | { mode: "hosted" | "byok"; tier: 3; label: string; policyForced?: boolean }
  // NOT a hosted fallthrough — mirrors tierRouter.ts's "edge-only-refused"
  // HostedFallthroughReason doc: "the caller must SURFACE this as an error
  // ... never re-send the turn hosted". An EDGE_ONLY agent with no local
  // model ready refuses the turn outright; this is the preview's honest
  // reflection of that refusal, not a hosted/BYOK fallback.
  | { mode: "refused"; tier: null; label: string; policyForced?: boolean };

// `policyForced` (bug fix, policy-divergence banner false positive): true
// ONLY when this result was actually produced by a real server-declared
// forcing condition — HOSTED_ONLY, EDGE_ONLY, or guardrails-unrepresentable
// — as opposed to the ORDINARY device/tenant-preference path (including its
// own "device isn't ready yet, fall back to hosted for this turn" branch in
// previewOnDeviceTier below, which has nothing to do with server policy).
// Callers that want to know "did the server override the user's choice" —
// e.g. IntelligenceSourceControl.tsx's divergence banner — must check this
// flag, NOT just "does mode differ from what the user picked": a mismatch
// with policyForced=false is a mundane capability fallback, not a policy
// override, and must never be reported as one.

/**
 * Pure, synchronous "which tier would resolveTier pick right now" —
 * literally `resolveTier(caps, readiness)` (capabilities.ts ~292-298),
 * re-exported under this module's naming so a caller that already has
 * `caps`/`readiness` in hand (e.g. mid-render, no need to re-probe) can ask
 * the routing question without importing capabilities.ts directly and
 * without this module forking resolveTier's decision logic. Read-only: this
 * NEVER creates a session, downloads anything, or executes a turn — it's the
 * same guarantee resolveTier itself makes. previewRouting() below is the
 * higher-level, async, tenant-aware wrapper most UI should call instead;
 * this is the low-level primitive it's built on, exported because Part 4's
 * spec asks for it directly (a "given known caps/readiness, what tier"
 * question distinct from previewRouting's "go detect caps and tell me").
 */
export function resolveEffectiveRouting(
  caps: EdgeCapabilities,
  readiness?: TierReadiness
): { tier: 0 | 1 | 2 | 3; onDevice: boolean } {
  const tier = resolveTier(caps, readiness);
  return { tier, onDevice: tier === 0 || tier === 1 || tier === 2 };
}

/** `tenant.usePlatformLlm`/`activeProviderCount`-based "what does hosted
 * actually mean for this tenant right now" — shared by every branch below
 * that resolves to a hosted fallthrough (policy-forced or capability-
 * forced), so a BYOK tenant sees an honest "Your own provider" label instead
 * of a generic "Hosted" one regardless of WHY the turn ended up hosted. */
function resolvedHostedTarget(tenant: TenantProviderState): "hosted" | "byok" {
  return !tenant.usePlatformLlm && tenant.activeProviderCount > 0 ? "byok" : (resolveByokFallback() as "hosted");
}

/** Shared tail of previewRouting() for "attempt on-device, see what tier
 * resolves" — the exact same primitives routeTurn() itself calls
 * (getTier1RunModel/resolveEffectiveRouting), used identically whether the
 * attempt is an ordinary edge-first device preference (`edgeOnly=false`,
 * an honest hosted/BYOK fallback on a miss) or a server EDGE_ONLY policy
 * (`edgeOnly=true`, refuses rather than falls back on a miss — see
 * RoutingPreview's "refused" doc above). */
async function previewOnDeviceTier(
  mode: RoutingMode,
  tenant: TenantProviderState,
  edgeOnly: boolean
): Promise<RoutingPreview> {
  const caps: EdgeCapabilities = await detectCapabilities();
  const t1Model = await getTier1RunModel(caps);
  // Same primitive resolveEffectiveRouting() exposes above — called here
  // too so there is exactly one call site translating (caps, readiness)
  // into a tier, not two copies of the same resolveTier() call.
  const { tier: startTier } = resolveEffectiveRouting(caps, { tier1Ready: t1Model !== null });
  // `edgeOnly` here means this attempt was itself mandated by a server
  // EDGE_ONLY policy (the only caller that passes true — see previewRouting
  // below), so every outcome below is policy-attributable when it's true.
  // When `edgeOnly` is false, this is an ordinary device-preference attempt
  // (policy is null/EDGE_FIRST) and NONE of its outcomes — including the
  // hosted/BYOK fallback right below — are policy-forced, even though the
  // fallback can still visibly diverge from what the user picked.
  if (startTier === 1) {
    return {
      mode,
      tier: 1,
      label: `${ROUTING_MODES[mode].label} (on-device, ${t1Model?.displayName ?? "local model"})`,
      policyForced: edgeOnly,
    };
  }
  if (startTier === 0) {
    return {
      mode,
      tier: 0,
      label: `${ROUTING_MODES[mode].label} (on-device, browser built-in)`,
      policyForced: edgeOnly,
    };
  }
  // startTier 2/3 with no tier-2 model live yet.
  if (edgeOnly) {
    // Mirrors routeTurn()'s edge-only-refused path exactly (tierRouter.ts
    // ~lines 392-400): an EDGE_ONLY agent never falls back to hosted/BYOK —
    // it refuses the turn. This refusal IS the policy in action.
    return {
      mode: "refused",
      tier: null,
      label: "This agent's policy requires on-device processing, and no on-device model is ready on this device",
      policyForced: true,
    };
  }
  // This preference falls back to hosted/BYOK for THIS turn — surface that
  // honestly rather than claiming "on-device". This is the ORDINARY
  // capability fallback (no server policy involved at all — policy is
  // null/EDGE_FIRST whenever edgeOnly is false here), so policyForced stays
  // false/omitted: a divergence banner must not blame a "server routing
  // policy" for this.
  const fallback = resolvedHostedTarget(tenant);
  return {
    mode: fallback,
    tier: 3,
    label: `${ROUTING_MODES[fallback].label} (device can't serve on-device right now)`,
  };
}

/**
 * What will actually handle the NEXT turn, right now, on this device — not
 * the stored preference (which can diverge, e.g. "on-device" selected but
 * capabilities resolve to hosted this instant because no model is ready
 * yet). Never rejects; mirrors routeTurn()'s own "never throw to the UI"
 * contract. Pass the same TenantProviderState ProvidersPanel/callers already
 * have.
 *
 * `agentId` (optional, Wave 2 fix): when provided, this agent's
 * server-declared edge routing policy is fetched and applied FIRST, with
 * the exact same precedence routeTurn() itself uses — see this module's
 * header comment above previewRouting's type export. Omit it (or pass
 * null/undefined) to get the pre-fix, policy-blind behavior — useful for a
 * draft agent that has no id yet, or a caller that only has tenant/device
 * state in hand.
 */
export async function previewRouting(
  tenant: TenantProviderState,
  agentId?: string | null
): Promise<RoutingPreview> {
  try {
    // Mirrors routeTurn()'s own top-of-function order (tierRouter.ts
    // routeTurn(), ~lines 303-306) exactly: edge-disabled wins first,
    // unconditionally, before any server policy or device preference is
    // even consulted — an EDGE_ONLY policy plays no role if the on-device
    // feature itself is off (routeTurn never gets far enough to fetch it
    // either). BYOK preference is independent of the edge flag (it's a
    // tenant/device setting, not an edge-tier feature), so it's still
    // consulted here via getRoutingMode() rather than hardcoding "hosted".
    if (!edgeEnabled() || typeof window === "undefined") {
      const fallbackMode = getRoutingMode(tenant);
      return fallbackMode === "byok"
        ? { mode: "byok", tier: 3, label: ROUTING_MODES.byok.label }
        : { mode: "hosted", tier: 3, label: ROUTING_MODES.hosted.label };
    }

    // ---- Server policy BEFORE device/tenant preferences (Wave 2 fix) —
    // mirrors routeTurn()'s Wave 2 step 0 (tierRouter.ts's routeTurn(),
    // ~lines 308-372) exactly: a HOSTED_ONLY (or guardrails-the-pattern-
    // tier-can't-represent) agent short-circuits to hosted here BEFORE
    // getRoutingMode() is even consulted, and an EDGE_ONLY agent SKIPS the
    // device-preference checks getRoutingMode() would otherwise apply — a
    // local "Always use hosted" pin does not save it from an on-device
    // attempt, and a miss refuses rather than falling back to hosted. Both
    // are the exact asymmetry a naive "compute mode, then maybe check
    // policy" ordering would get backwards.
    const policyInfo: EdgeAgentPolicyInfo | null = agentId ? await fetchEdgeAgentPolicy(agentId) : null;
    const policy = policyInfo?.policy ?? null;
    const edgeOnly = policy === "EDGE_ONLY";
    const guardrailsActive = policyInfo?.guardrailsEnabled === true;

    if (policy === "HOSTED_ONLY") {
      const target = resolvedHostedTarget(tenant);
      return {
        mode: target,
        tier: 3,
        label: `${ROUTING_MODES[target].label} — this agent's policy requires hosted processing`,
        policyForced: true,
      };
    }
    if (guardrailsActive && policyInfo && !policyInfo.guardrailsPatternRepresentable) {
      if (edgeOnly) {
        // Mirrors routeTurn()'s EDGE_ONLY + unrepresentable-guardrails
        // refusal (tierRouter.ts ~lines 338-346) — a config contradiction
        // the router surfaces as an error rather than silently violating
        // either the privacy policy or the guardrail posture.
        return {
          mode: "refused",
          tier: null,
          label:
            "This agent's guardrails need the platform's full safety checks, but its policy forbids hosted processing",
          policyForced: true,
        };
      }
      const target = resolvedHostedTarget(tenant);
      return {
        mode: target,
        tier: 3,
        label: `${ROUTING_MODES[target].label} — this agent's guardrails require hosted processing`,
        policyForced: true,
      };
    }

    // No forcing policy (null, or EDGE_FIRST): normal tenant/device
    // preference decides, unchanged from before this fix — EXCEPT when
    // edgeOnly, which must attempt on-device regardless of what
    // getRoutingMode() would otherwise say (see comment above).
    if (!edgeOnly) {
      const mode = getRoutingMode(tenant);
      if (mode === "hosted") {
        return { mode: "hosted", tier: 3, label: ROUTING_MODES.hosted.label };
      }
      if (mode === "byok") {
        return { mode: "byok", tier: 3, label: ROUTING_MODES.byok.label };
      }
      return await previewOnDeviceTier(mode, tenant, false);
    }
    return await previewOnDeviceTier("hybrid", tenant, true);
  } catch {
    return { mode: "hosted", tier: 3, label: ROUTING_MODES.hosted.label };
  }
}
