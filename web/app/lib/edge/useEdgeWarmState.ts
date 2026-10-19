"use client";

// Shared warm-state hook (Finding #8, 2026-08-10 edge/WebLLM audit): extracted
// from AgentPlayground.tsx's inline edgePhase/edgeWarmingPercent/
// tier1PreparingNotice state + effects, which ChatPanel.tsx had no equivalent
// of at all (it only ever had a bare `sending` boolean, so a cold engine load
// — up to tier1.ts's own 60s ENGINE_ACQUIRE_TIMEOUT_MS ceiling — read as a
// hung/broken UI rather than a real loading state).
//
// Scope note: this hook is wired into ChatPanel.tsx (net new for that
// surface). AgentPlayground.tsx's own inline copy is intentionally left
// UNTOUCHED here — that file is outside this change's edit scope (a
// different agent owns web/app/components/agents/ in this run) — so for now
// the same logic genuinely lives in two places. The two copies are kept
// provably identical in behavior (mirrors the exact state shape, effect
// gating, and event wiring AgentPlayground.tsx already has - see its own
// comments above the equivalent code) so a future pass can point
// AgentPlayground at this hook instead of re-deriving the logic.
//
// Behavior mirrors AgentPlayground.tsx's existing pattern exactly:
//   - edgePhase / edgeWarmingPercent: live processing-stage + real percent
//     for an in-flight on-device attempt. "generating" comes from
//     subscribeEdgeActivity (capabilities.ts); "warming" + percent come from
//     downloadManager's asset events (tier0.ts/tier1.ts report download AND
//     cache-load/warm-up progress through the same channel — see
//     tier1.ts's progressReporter). Gated so a stray/background download
//     with no turn in flight can never latch the indicator on with nothing
//     to ever clear it (mirrors AgentPlayground's own gate).
//   - tier1PreparingNotice: a side-effect-free "would tier 1 actually serve
//     the next send" answer (getTier1RunModel), shown only while nothing is
//     sending yet - once a real send starts, edgePhase/edgeWarmingPercent are
//     the live, accurate signal and this would be redundant/stale.
//   - the idle-time prewarm call itself now warms BOTH tiers: tier1's
//     existing prewarmTier1Engine, plus tier0's new prewarmTier0Session
//     (finding #11, tier0.ts) - both are fire-and-forget, best-effort, and
//     never trigger a download on their own, so calling both unconditionally
//     here is free when neither has anything ready to warm.
//
// The caller owns the actual send flow and is responsible for calling
// beginEdgeAttempt()/endEdgeAttempt() at the same points AgentPlayground's
// sendViaEdge already brackets its own setEdgePhase("checking")/setEdgePhase(null)
// calls - this hook only owns the state and the background wiring, not the
// send flow itself (every caller's send logic differs too much to share that
// part safely).

import { useEffect, useState } from "react";
import { detectCapabilities, edgeEnabled, subscribeEdgeActivity, EDGE_PREFS_EVENT } from "./capabilities";
import { downloadManager } from "./downloadManager";
import { getTier1RunModel, prewarmTier1Engine } from "./tier1";
import { prewarmTier0Session } from "./tier0";

export type EdgeTurnPhase = "checking" | "warming" | "generating" | null;

export interface EdgeWarmState {
  /** Which stage an in-flight on-device attempt is in right now, or null
   * while nothing edge-related is happening. */
  edgePhase: EdgeTurnPhase;
  /** Real download/warm-up percent for the "warming" phase, or null when no
   * percent is known yet (still show a label, just not a number). */
  edgeWarmingPercent: number | null;
  /** True when a ready tier-1 model would actually serve this agent's next
   * send - an honest "getting ready" notice for the window before any send
   * has started. Always false once `sending` is true. */
  tier1PreparingNotice: boolean;
  /** Call at the same point AgentPlayground's sendViaEdge calls
   * setEdgePhase("checking") - right before attempting an on-device turn. */
  beginEdgeAttempt: () => void;
  /** Call on every exit from an on-device attempt (success, fallthrough, or
   * error) - mirrors every setEdgePhase(null) call AgentPlayground's
   * sendViaEdge makes. */
  endEdgeAttempt: () => void;
}

/**
 * Shared on-device warm/loading-state signal for a chat surface. `agentId`
 * re-runs the idle-time prewarm when the selected agent changes (and on the
 * EDGE_PREFS_EVENT window event, same as AgentPlayground); `sending` clears
 * the proactive "preparing" notice the moment a real send starts.
 */
export function useEdgeWarmState(agentId: string | null | undefined, sending: boolean): EdgeWarmState {
  const [edgePhase, setEdgePhase] = useState<EdgeTurnPhase>(null);
  const [edgeWarmingPercent, setEdgeWarmingPercent] = useState<number | null>(null);
  const [tier1PreparingNotice, setTier1PreparingNotice] = useState(false);

  // Background subscriptions - live for this hook's whole lifetime, not just
  // one turn. Mirrors AgentPlayground's equivalent effect exactly, including
  // its gate against a stray/background download latching the indicator on
  // with no active turn to ever clear it back to null.
  useEffect(() => {
    if (!edgeEnabled()) return undefined;
    const unsubscribeActivity = subscribeEdgeActivity((activity) => {
      if (activity.active) setEdgePhase("generating");
    });
    const unsubscribeDownloads = downloadManager.subscribe((event) => {
      if (
        event.type === "asset" &&
        (event.asset.state === "downloading" || event.asset.state === "verifying")
      ) {
        setEdgePhase((prev) => (prev === null || prev === "generating" ? prev : "warming"));
        setEdgeWarmingPercent(
          event.asset.totalBytes
            ? Math.min(100, Math.round((event.asset.receivedBytes / event.asset.totalBytes) * 100))
            : null
        );
      }
    });
    return () => {
      unsubscribeActivity();
      unsubscribeDownloads();
    };
  }, []);

  // Idle-time prewarm (both tiers) + the proactive "would tier 1 serve"
  // notice. Re-fires on agent change and on EDGE_PREFS_EVENT, same triggers
  // AgentPlayground's equivalent effect uses.
  useEffect(() => {
    if (!edgeEnabled() || typeof window === "undefined") {
      setTier1PreparingNotice(false);
      return undefined;
    }
    let cancelled = false;
    const prewarm = () => {
      detectCapabilities()
        .then((caps) => {
          if (cancelled) return undefined;
          prewarmTier1Engine(caps, agentId ?? null);
          // Finding #11 (tier0.ts): tier 0's own idle-time warm probe. Same
          // fire-and-forget, never-downloads, best-effort contract as
          // prewarmTier1Engine above - safe to call unconditionally here.
          prewarmTier0Session();
          // Side-effect-free (no download, no engine touch) - only answers
          // "would tier 1 run for this agent's next send". Cleared below the
          // moment a real send starts.
          return getTier1RunModel(caps).then((model) => {
            if (!cancelled) setTier1PreparingNotice(model !== null);
          });
        })
        .catch(() => {
          if (!cancelled) setTier1PreparingNotice(false);
        });
    };
    prewarm();
    window.addEventListener(EDGE_PREFS_EVENT, prewarm);
    return () => {
      cancelled = true;
      window.removeEventListener(EDGE_PREFS_EVENT, prewarm);
    };
  }, [agentId]);

  // Clear the proactive notice the moment a real send starts - from then on
  // edgePhase/edgeWarmingPercent (already live) is the accurate signal.
  useEffect(() => {
    if (sending) setTier1PreparingNotice(false);
  }, [sending]);

  function beginEdgeAttempt(): void {
    setEdgePhase("checking");
    setEdgeWarmingPercent(null);
  }

  function endEdgeAttempt(): void {
    setEdgePhase(null);
    setEdgeWarmingPercent(null);
  }

  return { edgePhase, edgeWarmingPercent, tier1PreparingNotice, beginEdgeAttempt, endEdgeAttempt };
}
