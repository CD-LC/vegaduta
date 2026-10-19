import {
  EDGE_OPT_IN_KEY,
  detectCapabilities,
  edgeEnabled,
  getEdgeRoutingPreference,
} from "../../../lib/edge/capabilities";
import { getStoredModelOverride } from "../../../lib/edge/routingState";
import { tier1Ready } from "../../../lib/edge/tier1";
import { tier0Availability } from "../../../lib/edge/tier0";

// ---------------------------------------------------------------------------
// A3 edge-first preference helpers (shared with AgentPlayground)
// ---------------------------------------------------------------------------

/**
 * The user's STORED on-device choice, tri-state: true/false when they ever
 * decided (either way — setEdgeOptIn writes "1"/"0" only on explicit user
 * gestures across every edge surface), null when they never have. The null
 * case is what the edge-first default below is allowed to fill in;
 * a stored choice always wins and persists (plan's binding rule).
 *
 * Deliberately stays OUTSIDE the "everything funnels through routingState.ts"
 * rule (Phase 3 unification): routingState.ts's getEdgeOptIn() re-export
 * only answers the boolean-with-default question ("is on-device effectively
 * on right now"), which is not what this needs — this needs the raw
 * tri-state (was there ever an EXPLICIT choice, or is nothing stored at
 * all), so the edge-first default fill-in below can tell "user explicitly
 * said no" apart from "user never said anything". routingState.ts has no
 * raw tri-state export, so this reads EDGE_OPT_IN_KEY directly, same as
 * before this migration.
 */
export function getStoredEdgeOptInChoice(): boolean | null {
  try {
    if (typeof localStorage === "undefined") return null;
    const raw = localStorage.getItem(EDGE_OPT_IN_KEY);
    if (raw === "1") return true;
    if (raw === "0") return false;
    return null;
  } catch {
    return null;
  }
}

/**
 * Is a TEXT model genuinely ready on this device right now? "Ready" is
 * strict on purpose (E-0b rule 6 — never on the promise of a download):
 * Chrome's built-in model reporting "available" (not merely downloadable),
 * or a tier-1 model whose weights tier1Ready() verifies are already cached.
 * Never rejects.
 */
export async function edgeTextModelReady(): Promise<boolean> {
  try {
    if (typeof window === "undefined" || !edgeEnabled()) return false;
    if ((await tier0Availability()) === "available") return true;
    const caps = await detectCapabilities();
    return await tier1Ready(caps);
  } catch {
    return false;
  }
}

/**
 * A3 edge-first: the EFFECTIVE on-device opt-in — the stored user choice
 * when one exists, else ON exactly when the device has a text model ready
 * (flag on + capable device + ready model = edge is the default first
 * priority). Deliberately does NOT persist the default: only user gestures
 * write the preference, so a later explicit choice always wins cleanly.
 */
export async function getEffectiveEdgeOptIn(): Promise<boolean> {
  if (!edgeEnabled()) return false;
  // "Always use hosted" in the model picker is itself a stored user choice —
  // the router honours it before anything else (user-hosted-override), so
  // the effective opt-in must read hosted too, not attempt-and-fall-through.
  // (getStoredModelOverride now sourced from routingState.ts's re-export —
  // identical function, just funneled through the one module per the
  // Phase 3 rule.)
  if (getStoredModelOverride() === "hosted") return false;
  // A1's routing-preference module (edge.routingPref) is the source of truth
  // the router itself checks: "hosted-only" never routes locally.
  //
  // getEdgeRoutingPreference itself is the SECOND documented exception to
  // "funnel through routingState.ts" (the first is getStoredEdgeOptInChoice
  // above): routingState.ts re-exports only the EdgeRoutingPreference TYPE,
  // not a getter — its own getRoutingMode()/setRoutingMode() collapse the
  // hosted-override / hosted-only-pref / edge-first checks into one 4-mode
  // answer, which would lose the exact step-by-step precedence this
  // function needs (an explicit stored opt-in, checked below, must win over
  // a stale/mismatched routing-pref value read on its own — see this
  // function's git history for why). This helper's async, capability-gated
  // contract is also depended on unchanged by AgentPlayground.tsx's actual
  // send path (out of this task's scope), so it keeps reading the raw
  // preference straight from capabilities.ts rather than approximating it
  // through the mode-level API.
  if (getEdgeRoutingPreference() === "hosted-only") return false;
  const stored = getStoredEdgeOptInChoice();
  if (stored !== null) return stored;
  // No stored choice: edge-first (the default) fills it in — but only when a
  // text model is genuinely ready (stricter than the router's own gate, which
  // would just fall through no-local-tier anyway); "ask" stays hosted until
  // the person enables on-device themselves.
  if (getEdgeRoutingPreference() !== "edge-first") return false;
  return edgeTextModelReady();
}
