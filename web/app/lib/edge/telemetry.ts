// The client half of
// EdgeTurnTelemetryController. Before this, nothing measured how often
// on-device replies actually succeed vs. silently fall back to hosted vs.
// error outright; this is the one call site tierRouter.ts's routeTurn()
// invokes after EVERY completed edge turn, success or not.
//
// Contract: fire-and-forget, NEVER throws, NEVER awaited by the caller's
// critical path — a failed report (network drop, server hiccup) must have
// zero effect on what the user sees. No retry, no queueing: losing an
// occasional telemetry point is fine, delaying or blocking a chat reply
// over it is not.

import { API_BASE } from "../api";
import { auth } from "../auth";
import { viewAsHeaders } from "../viewAs";

export type EdgeTurnOutcome = "success" | "fallback" | "error";

export interface EdgeTurnReport {
  agentId: string;
  /** The same value routeTurn's caller threads through as sessionKey
   * (typically the agent id) — carried for log correlation only. */
  sessionKey?: string | null;
  tier: 0 | 1;
  modelId: string | null;
  outcome: EdgeTurnOutcome;
  /** Typed failure reason (Tier0Error/Tier1Error code, or routeTurn's own
   * HostedFallthroughReason) — omitted for a clean success. */
  reason?: string | null;
  latencyMs?: number | null;
}

/**
 * Report one completed edge turn. Never rejects, never throws — call it
 * with `void reportEdgeTurn(...)` and move on. Silently no-ops without a
 * `fetch`/`window` (SSR) or without an agentId (nothing meaningful to
 * attribute).
 */
export function reportEdgeTurn(report: EdgeTurnReport): void {
  // Local read-path buffer first (see below) — a synchronous, in-memory
  // record of every turn this tab has seen, independent of whether the
  // server POST below actually lands. Keeps read visibility (e.g. for
  // tuning the edge circuit-breaker policy in tierRouter.ts by real
  // trip frequency/cause instead of by feel) even when offline, when
  // `API_BASE` is unreachable, or before any backend sink exists to query.
  recordEdgeTurnLocally(report);
  try {
    if (typeof window === "undefined" || typeof fetch === "undefined") return;
    if (!report.agentId) return;
    // Silent Bearer token, NOT `credentials: "include"`: the controller
    // authenticates by JWT (Principal) — an authenticated caller gets an RL
    // episode, an anonymous one a log line — never by cookies. Credentialed
    // CORS mode was also actively harmful: core answers with
    // allowCredentials(false) (WebConfig), so the browser blocked this POST
    // outright on the deployed domains.
    void (async () => {
      const token = await auth.getToken({ silent: true });
      await fetch(`${API_BASE}/api/edge/turns`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
          ...viewAsHeaders(),
        },
        keepalive: true,
        body: JSON.stringify({
          agentId: report.agentId,
          sessionKey: report.sessionKey ?? null,
          tier: report.tier,
          modelId: report.modelId,
          outcome: report.outcome,
          reason: report.reason ?? null,
          latencyMs: report.latencyMs ?? null,
        }),
      });
    })().catch(() => {
      // Best-effort — see header comment.
    });
  } catch {
    // Best-effort — see header comment.
  }
}

// ---------------------------------------------------------------------------
// Read path (2026-08-06 follow-up) — reportEdgeTurn was write-only: nothing
// let you see real trip frequency or failure-cause distribution, so the
// edge circuit-breaker policy (tierRouter.ts's EDGE_CIRCUIT_BREAKER_POLICY)
// could only be tuned by feel. Small in-memory ring buffer, most-recent-last,
// capped so it can't grow unbounded across a long session. Client-side only
// (per-tab, resets on reload) — not a substitute for the server sink the
// header's TODO still describes, just enough to inspect recent behavior
// live (devtools console, an admin-only debug panel, a test) without one.
// ---------------------------------------------------------------------------

export interface EdgeTurnRecord extends EdgeTurnReport {
  /** When this record was buffered (Date.now()), not when the (best-effort,
   * possibly still-inflight or failed) server POST landed. */
  recordedAt: number;
}

const EDGE_TURN_RING_BUFFER_SIZE = 50;
const recentEdgeTurns: EdgeTurnRecord[] = [];

function recordEdgeTurnLocally(report: EdgeTurnReport): void {
  try {
    recentEdgeTurns.push({ ...report, recordedAt: Date.now() });
    if (recentEdgeTurns.length > EDGE_TURN_RING_BUFFER_SIZE) {
      recentEdgeTurns.shift();
    }
  } catch {
    // Best-effort — see header comment; buffering must never affect the caller.
  }
}

/**
 * Recent edge-turn outcomes, oldest first, newest last — up to the last
 * `EDGE_TURN_RING_BUFFER_SIZE` (50) turns this tab has reported via
 * reportEdgeTurn(), across all tiers/targets. Pass `limit` to take only the
 * most recent N. Read-only snapshot (a copy, not a live reference) — safe to
 * hold onto without re-subscribing.
 */
export function getRecentEdgeTurns(limit?: number): EdgeTurnRecord[] {
  if (limit === undefined || limit >= recentEdgeTurns.length) return [...recentEdgeTurns];
  return recentEdgeTurns.slice(recentEdgeTurns.length - Math.max(0, limit));
}
