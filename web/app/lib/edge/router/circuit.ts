// Edge circuit-breaker targets/policy and the RECOVERING signal. Split out of
// ../tierRouter.ts, which re-exports every public name.

import {
  registerCircuitBreakerPolicyOverride,
  type CircuitBreakerPolicy,
} from "../../requestLifecycle";

export const EDGE_TIER0_CIRCUIT_TARGET = "edge-tier0";
export const EDGE_TIER1_CIRCUIT_TARGET = "edge-tier1";
export const EDGE_TIER2_CIRCUIT_TARGET = "edge-tier2";

// DEFAULT_CIRCUIT_BREAKER_POLICY (requestLifecycle.ts) — failureThreshold 4,
// cooldownMs 15_000 — was built for the hosted chat-stream:core target,
// where a shared backend actually needs protecting from a hammering client
// during an outage. An edge-tier0/tier1 "failure" is a local, single-device
// event (a WebGPU driver hiccup, a Prompt API session going stale, one
// stochastic empty generation) with no shared resource behind it to
// protect — there's nothing this breaker is guarding except the same
// device's own next attempt. That justifies a policy that trips later
// (more tolerant of one-off local flakiness before assuming something is
// systemically broken on this device) and recovers sooner (no backoff
// rationale for hammering a shared backend applies, so there's no reason to
// make the user wait as long before local inference is tried again):
//   - failureThreshold 6 (vs. 4): local models/devices are more failure-prone
//     turn-to-turn than a hosted API: cold WebGPU context creation flakes,
//     the Prompt API's own retry-on-empty (promptWithOneRetryOnEmpty, above)
//     already burns one legitimate retry per turn, small on-device models
//     produce more genuinely-empty degenerate replies than a hosted model
//     ever does. Tripping at 4 punished normal edge variance, not a real
//     device-level failure.
//   - cooldownMs 6_000 (vs. 15_000): once tripped, the fallback (the other
//     local tier, or hosted) already covers the user immediately per this
//     file's graceful-degradation contract — the cooldown here only gates
//     how soon THIS device's local tier gets tried again, not anyone else's
//     traffic. 6s is enough to skip a tight failure burst (e.g. a session
//     stuck in a bad state right after a failed turn) without leaving a
//     transient hiccup looking "down" for a noticeably long time.
// windowMs stays at the default 30_000 — unrelated to the shared-resource
// question above, no reason cited to change it.
export const EDGE_CIRCUIT_BREAKER_POLICY: CircuitBreakerPolicy = {
  failureThreshold: 6,
  windowMs: 30_000,
  cooldownMs: 6_000,
};

// Registered once at module load. registerCircuitBreakerPolicyOverride is
// purely additive per-target — this only changes what
// requestCircuitBreaker.recordFailure(target) falls back to when a caller
// omits an explicit policy (exactly what the two recordFailure() calls below
// now do); chat-stream:core and every other target's policy — including
// RequestLifecycleController-driven callers, which always resolve and pass
// their own policy explicitly — are untouched.
registerCircuitBreakerPolicyOverride(EDGE_TIER0_CIRCUIT_TARGET, EDGE_CIRCUIT_BREAKER_POLICY);
registerCircuitBreakerPolicyOverride(EDGE_TIER1_CIRCUIT_TARGET, EDGE_CIRCUIT_BREAKER_POLICY);
// Tier 2 gets the SAME policy for the same reason the comment above gives: a
// tier-2 failure is a local, single-device event with no shared resource
// behind it. Nothing about the CPU runtime argues for a different threshold
// or cooldown than the other two local tiers, and inventing different numbers
// here would be a number with no measurement behind it.
registerCircuitBreakerPolicyOverride(EDGE_TIER2_CIRCUIT_TARGET, EDGE_CIRCUIT_BREAKER_POLICY);

// ---------------------------------------------------------------------------
// Observable RECOVERING signal (reliability blueprint Phase 2) — fires while
// promptWithOneRetryOnEmpty is making its silent second attempt, so the UI
// can surface a "recovering" state instead of the retry being invisible.
// House style matches offline.ts's Set-of-listeners pub-sub.
// ---------------------------------------------------------------------------

/** Still 0 | 1: the retry-on-empty pass this event reports is deliberately
 * NOT run for tier 2 — see tryTier2's comment on why a second CPU generation
 * is not a cheap retry. */
export type EdgeRecoveryEvent = { recovering: boolean; tier: 0 | 1 };

const recoveryListeners = new Set<(event: EdgeRecoveryEvent) => void>();

export function subscribeEdgeRecovery(listener: (event: EdgeRecoveryEvent) => void): () => void {
  recoveryListeners.add(listener);
  return () => {
    recoveryListeners.delete(listener);
  };
}

export function notifyEdgeRecovery(event: EdgeRecoveryEvent): void {
  for (const listener of recoveryListeners) {
    try {
      listener(event);
    } catch {
      // A broken listener must never affect routing.
    }
  }
}
