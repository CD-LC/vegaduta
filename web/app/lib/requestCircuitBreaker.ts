// Universal Request Lifecycle: the per-target circuit breaker shared by every
// controller with the same `target` (split out of requestLifecycle.ts, which
// re-exports it).
import { DEFAULT_CIRCUIT_BREAKER_POLICY, type CircuitBreakerPolicy } from "./requestLifecycleTypes";

// ---------------------------------------------------------------------------
// Circuit breaker — module-level registry, keyed by `target`
// ---------------------------------------------------------------------------

interface CircuitEntry {
  /** Failure timestamps within the current window (oldest first). */
  timestamps: number[];
  /** Set once the breaker trips; cleared once cooldown elapses or a success
   * lands. */
  openUntil: number | null;
}

class CircuitBreakerRegistry {
  private entries = new Map<string, CircuitEntry>();
  /** Per-target policy overrides (reliability blueprint follow-up: edge
   * on-device targets have no shared-resource-protection rationale a hosted
   * backend breaker has — a GPU hiccup on one tab doesn't threaten a shared
   * backend the way a hammered hosted API does — so they warrant a different
   * threshold/cooldown than DEFAULT_CIRCUIT_BREAKER_POLICY without touching
   * it or any other target). Set via registerCircuitBreakerPolicyOverride();
   * only consulted when a caller's recordFailure() omits an explicit policy
   * — a call-site that always passes its own resolved policy (e.g.
   * RequestLifecycleController, which resolves per-instance and always
   * supplies it) is unaffected either way. */
  private policyOverrides = new Map<string, CircuitBreakerPolicy>();

  /** True = fail fast without running the operation at all. */
  isOpen(target: string): boolean {
    const entry = this.entries.get(target);
    if (!entry || entry.openUntil === null) return false;
    if (Date.now() < entry.openUntil) return true;
    // Cooldown elapsed — half-open: let the next attempt through and clear
    // the trip so a real failure re-trips fresh rather than staying "open"
    // forever off a stale timestamp list.
    this.entries.delete(target);
    return false;
  }

  /** Register (or replace) the policy used for `target` when a caller's
   * recordFailure() doesn't pass one explicitly. Additive — does not affect
   * targets that already pass their own policy at every call. */
  setPolicyOverride(target: string, policy: CircuitBreakerPolicy): void {
    this.policyOverrides.set(target, policy);
  }

  getPolicyOverride(target: string): CircuitBreakerPolicy | null {
    return this.policyOverrides.get(target) ?? null;
  }

  recordFailure(target: string, policy?: CircuitBreakerPolicy): void {
    const resolvedPolicy = policy ?? this.policyOverrides.get(target) ?? DEFAULT_CIRCUIT_BREAKER_POLICY;
    const now = Date.now();
    const entry = this.entries.get(target) ?? { timestamps: [], openUntil: null };
    entry.timestamps = entry.timestamps.filter((t) => now - t < resolvedPolicy.windowMs);
    entry.timestamps.push(now);
    if (entry.timestamps.length >= resolvedPolicy.failureThreshold) {
      entry.openUntil = now + resolvedPolicy.cooldownMs;
    }
    this.entries.set(target, entry);
  }

  recordSuccess(target: string): void {
    this.entries.delete(target);
  }

  reset(target: string): void {
    this.entries.delete(target);
  }

  getState(target: string): { open: boolean; consecutiveFailures: number; opensUntil: number | null } {
    const entry = this.entries.get(target);
    if (!entry) return { open: false, consecutiveFailures: 0, opensUntil: null };
    const open = entry.openUntil !== null && Date.now() < entry.openUntil;
    return { open, consecutiveFailures: entry.timestamps.length, opensUntil: entry.openUntil };
  }
}

/** Module singleton — every RequestLifecycleController with the same
 * `target` shares this registry's counters. Exported for tests/admin tooling
 * and for call-sites that want to show breaker state proactively. */
export const requestCircuitBreaker = new CircuitBreakerRegistry();

export function getCircuitBreakerState(target: string): {
  open: boolean;
  consecutiveFailures: number;
  opensUntil: number | null;
} {
  return requestCircuitBreaker.getState(target);
}

export function resetCircuitBreaker(target: string): void {
  requestCircuitBreaker.reset(target);
}

/** Register a per-target circuit-breaker policy override — used by callers
 * that record failures via `requestCircuitBreaker.recordFailure(target)`
 * (no explicit policy arg) and want THAT target to trip on different
 * threshold/cooldown numbers than DEFAULT_CIRCUIT_BREAKER_POLICY, without
 * changing the default for every other target. Purely additive: a target
 * with no override, or a caller that always passes its own policy anyway
 * (e.g. RequestLifecycleController), is completely unaffected. */
export function registerCircuitBreakerPolicyOverride(target: string, policy: CircuitBreakerPolicy): void {
  requestCircuitBreaker.setPolicyOverride(target, policy);
}

export function getCircuitBreakerPolicyOverride(target: string): CircuitBreakerPolicy | null {
  return requestCircuitBreaker.getPolicyOverride(target);
}
