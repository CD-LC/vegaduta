"use client";

import { useEffect, useRef, useState } from "react";
import { api } from "../../../lib/api";
// Edge agents, flag-gated behind NEXT_PUBLIC_EDGE_AGENTS=1 - with
// the flag unset none of this renders or runs). The status chip itself lives
// in AppShell's header; this hook only keeps the opt-in banner + tier0 logic.
import {
  detectCapabilities,
  edgeEnabled,
  getEdgeBannerDismissed,
  subscribeEdgeActivity,
  EDGE_FALLBACK_ASK_TIMEOUT_MS,
  EDGE_PREFS_EVENT,
} from "../../../lib/edge/capabilities";
import { prewarmTier1Engine, getTier1RunModel } from "../../../lib/edge/tier1";
import { previewRouting, type RoutingPreview, type TenantProviderState } from "../../../lib/edge/routingState";
import { tier0Available } from "../../../lib/edge/tier0";
import { downloadManager } from "../../../lib/edge/downloadManager";
import { isVisionModelCached } from "../../../lib/edge/vision";
// A3 edge-first: the shared effective-opt-in helper (stored user choice
// always wins; with none stored, on-device defaults ON when this device has
// a text model genuinely ready).
import { getEffectiveEdgeOptIn } from "../IntelligenceSourceControl";

/**
 * Everything the playground knows about on-device (edge) execution: the live
 * processing phase of an in-flight edge attempt, the opt-in / banner / tier-0
 * / vision readiness state kept in sync with the edge preferences, the
 * pre-send routing preview, tier-1 warm-up, and the "ask before falling back
 * to the platform" prompt. All inert unless the edge build flag is on.
 */
export function useEdgeState({
  agentId,
  sending,
  setVisionOnDevice,
}: {
  agentId: string | undefined;
  sending: boolean;
  setVisionOnDevice: (on: boolean) => void;
}) {
  // Real processing-stage indicator for an in-flight EDGE attempt (replaces
  // a generic "thinking" label with which layer is actually running right
  // now) - "checking" (routing decision, brief) -> "warming" (tier1 engine
  // loading from cache, or tier0's own first-use setup - downloadManager
  // reports this as "verifying"/"downloading") -> "generating" (tokens
  // actually streaming - subscribeEdgeActivity fires exactly here). Reset to
  // null on every sendViaEdge entry/exit so it never leaks into a hosted
  // turn's rendering (gated by turn.edgeAttempt below, belt-and-braces).
  const [edgePhase, setEdgePhase] = useState<"checking" | "warming" | "generating" | null>(null);
  // Bug fix (2026-08-06 diagnosis): the "warming" phase previously rendered
  // a static "warming up…" label for however long GPU load/compile took —
  // tier1.ts's progressReporter already computes a real percent for BOTH the
  // "downloading" and "verifying" (cache-load/warm-up) states, it just never
  // reached this component. Mirrors edgePhase's own reset points below.
  const [edgeWarmingPercent, setEdgeWarmingPercent] = useState<number | null>(null);
  // Cold-start honesty fix (2026-08-07): prewarmTier1Engine (below) is
  // fire-and-forget by contract — it never exposes when the engine actually
  // finishes loading — so the composer previously looked instantly ready the
  // moment an edge agent opened, even though the FIRST send could still pay
  // the full up-to-60s engine-load cost (see tier1.ts's ENGINE_ACQUIRE_TIMEOUT_MS)
  // with no warning beforehand. Rather than touch prewarmTier1Engine's
  // documented no-Promise contract (it has several other call sites and the
  // engine-load path has careful concurrency invariants that need real-device
  // verification to change safely), this uses the already-exported, side-
  // effect-free getTier1RunModel to answer one question up front: will THIS
  // agent's first send actually hit tier 1? If so, show an honest notice
  // instead of silence. Purely additive - never disables Send, never blocks
  // anything, only sets an expectation the UI was previously not setting at all.
  const [tier1PreparingNotice, setTier1PreparingNotice] = useState(false);
  // Bug fix (2026-08-06 follow-up): sendViaEdge never touched streamChat, so
  // it never got today's Cancel button / RequestStatusIndicator treatment -
  // an on-device turn that hit tier1.ts's new (also today's) 30s
  // generation-idle timeout still LOOKED stuck for the full 30s with no way
  // to bail early. routeTurn/tryTier0/tryTier1 already accept and honor an
  // AbortSignal (see tierRouter.ts's RouteTurnOptions.signal, wired all the
  // way into tier0.ts/tier1.ts's session.prompt calls) - this was simply
  // never created or offered to the user. One controller per in-flight edge
  // turn, cleared on every entry/exit exactly like edgePhase above.
  const edgeAbortControllerRef = useRef<AbortController | null>(null);
  function cancelEdgeTurn() {
    edgeAbortControllerRef.current?.abort();
  }
  // Fix #6 (P1): sendViaEdge's own "ask before falling back to the platform"
  // support - same shape as CallPanel's pendingFallbackAsk/askToFallback, so
  // the shared edge.fallbackPolicy preference (capabilities.ts) means the
  // same thing here as it does on the voice surface it originally shipped
  // on, instead of this surface always falling back silently regardless of
  // what the person set.
  const [pendingFallbackAsk, setPendingFallbackAsk] = useState<string | null>(null);
  const fallbackAskResolverRef = useRef<((confirmed: boolean) => void) | null>(null);
  function askToFallback(message: string): Promise<boolean> {
    return new Promise((resolve) => {
      fallbackAskResolverRef.current = (confirmed: boolean) => {
        fallbackAskResolverRef.current = null;
        setPendingFallbackAsk(null);
        resolve(confirmed);
      };
      setPendingFallbackAsk(message);
      setTimeout(() => {
        fallbackAskResolverRef.current?.(false);
      }, EDGE_FALLBACK_ASK_TIMEOUT_MS);
    });
  }
  // Which on-device model the EdgeActivity bus reported for the CURRENT
  // turn - captured in the subscribeEdgeActivity listener below, cleared
  // when sendViaEdge starts a new turn, read once when its metrics row is
  // written. A ref, not state: it's written mid-turn and only rendered
  // after the turn completes, so it never needs to trigger a render.
  const lastEdgeModelRef = useRef<string | null>(null);

  useEffect(() => {
    if (!edgeEnabled()) return undefined;
    const unsubscribeActivity = subscribeEdgeActivity((activity) => {
      if (activity.active) {
        setEdgePhase("generating");
        // beginEdgeActivity carries the real modelId while a local model
        // generates (tier 0 reports its shared asset id instead - not a
        // name worth showing - hence the tier check).
        if (activity.modelId && activity.tier !== 0) lastEdgeModelRef.current = activity.modelId;
      }
    });
    const unsubscribeDownloads = downloadManager.subscribe((event) => {
      if (event.type === "asset" && (event.asset.state === "downloading" || event.asset.state === "verifying")) {
        // This subscription lives for the component's whole lifetime, not
        // just a turn - gate on an ALREADY-active edgePhase (set by
        // sendViaEdge's "checking" entry below) so a stray/background
        // download (browsing models, tier0's own periodic availability
        // check) can't latch the header into "Warming up..." with no turn
        // in flight to ever clear it back to null.
        setEdgePhase((prev) => (prev === null || prev === "generating" ? prev : "warming"));
        // Real percent, not just a phase label — receivedBytes/totalBytes
        // are populated proportionally in both "downloading" and
        // "verifying" (see tier1.ts's progressReporter).
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


  // Tier 0 opt-in state. All three are inert (and the effect
  // below never runs its body) unless the NEXT_PUBLIC_EDGE_AGENTS build flag
  // is set. A3 edge-first: edgeOptIn is now the EFFECTIVE opt-in — the
  // stored localStorage `edge.optIn` choice when the person ever made one
  // (either way; it always wins and persists), else ON by default when this
  // device has a text model genuinely ready (getEffectiveEdgeOptIn). Synced
  // via the EDGE_PREFS_EVENT window event the same as before, so a click in
  // the EdgeSetupChip or IntelligenceSourceControl repaints this too.
  // edgeTier0Ready is a live probe of the Chrome built-in model.
  const [edgeOptIn, setEdgeOptInState] = useState(false);
  const [edgeBannerDismissed, setEdgeBannerDismissedState] = useState(false);
  const [edgeTier0Ready, setEdgeTier0Ready] = useState(false);
  // Bug fix (2026-08-06): CameraPanel's canSee prop was computed ONLY from
  // the agent's hosted/BYOK model catalog entry (the effect below this one),
  // with zero awareness of on-device vision - so switching this agent's
  // intelligence source to On-device, with on-device vision (SmolVLM) marked
  // "Ready", still hid Camera/Share-screen entirely: capabilities.supportsImageInput
  // never changes based on which source is actually active. isVisionModelCached()
  // is the same cheap sync check IntelligenceSourceControl's own "Ready" badge
  // reads - kept in this same edge-prefs sync (not capabilities' own effect,
  // which is deliberately about the HOSTED attach-file feature, a different
  // concern) so it refreshes on the identical triggers edgeOptIn already does.
  const [edgeVisionReady, setEdgeVisionReady] = useState(false);
  // Part 4 requirement 4 — pre-send routing preview state. tenantUsePlatformLlm/
  // activeProviderCount are this component's own lightweight fetch (this file
  // isn't handed `providers`/tenant settings as props the way AgentWorkbench
  // is), used only to build the TenantProviderState previewRouting() needs.
  const [tenantUsePlatformLlm, setTenantUsePlatformLlm] = useState(false);
  const [activeProviderCount, setActiveProviderCount] = useState(0);
  const [routingPreview, setRoutingPreview] = useState<RoutingPreview | null>(null);

  useEffect(() => {
    if (!edgeEnabled()) return undefined;
    let cancelled = false;
    // Both api calls are untyped (Promise<any>), which makes an unannotated
    // catch callback an implicitly-any-returning function under
    // noImplicitAny - the explicit return annotations name the slice the
    // .then below actually reads (usePlatformLlm / provider status), which
    // is all this preview fetch needs of either DTO.
    void Promise.all([
      api.getTenantSettings().catch((): { usePlatformLlm?: boolean } | null => null),
      api.listProviders().catch((): { status?: string }[] => []),
    ]).then(([settings, providers]) => {
      if (cancelled) return;
      setTenantUsePlatformLlm(!!settings?.usePlatformLlm);
      setActiveProviderCount(Array.isArray(providers) ? providers.filter((p) => p?.status === "ACTIVE").length : 0);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  // Re-resolved (not just re-read from storage) on the state that actually
  // changes what the NEXT turn would do: which agent is selected, the
  // tenant's platform-LLM/BYOK posture, and any routing-preference edit made
  // elsewhere on this page (IntelligenceSourceControl/EdgeSetupChip both fire
  // EDGE_PREFS_EVENT on every setter). Deliberately NOT keyed on `input` -
  // this must not recompute per keystroke.
  useEffect(() => {
    if (!edgeEnabled() || typeof window === "undefined") {
      setRoutingPreview(null);
      return undefined;
    }
    let cancelled = false;
    const tenant: TenantProviderState = {
      usePlatformLlm: tenantUsePlatformLlm,
      activeProviderCount,
    };
    const compute = () => {
      void previewRouting(tenant, agentId ?? null).then((preview) => {
        if (!cancelled) setRoutingPreview(preview);
      });
    };
    compute();
    window.addEventListener(EDGE_PREFS_EVENT, compute);
    return () => {
      cancelled = true;
      window.removeEventListener(EDGE_PREFS_EVENT, compute);
    };
  }, [agentId, tenantUsePlatformLlm, activeProviderCount]);

  useEffect(() => {
    if (!edgeEnabled() || typeof window === "undefined") return undefined;
    let cancelled = false;
    const syncEdgePrefs = () => {
      setEdgeBannerDismissedState(getEdgeBannerDismissed());
      setEdgeVisionReady(isVisionModelCached());
      // Async on purpose: readiness (tier-0 availability / verified tier-1
      // weights) is an async probe. Until it resolves, sends take the
      // unchanged hosted path — never a blocked send, never a flash of
      // wrongly-claimed on-device mode.
      getEffectiveEdgeOptIn()
        .then((on) => {
          if (!cancelled) setEdgeOptInState(on);
        })
        .catch(() => {});
    };
    syncEdgePrefs();
    window.addEventListener(EDGE_PREFS_EVENT, syncEdgePrefs);
    // Verify-pass fix: with NO stored choice, the edge-first default flips ON
    // the moment a text model becomes ready — which happens via a download
    // finishing (an ASSET event, not a prefs event). Re-probe on ready so the
    // send path picks up the default without needing a reload or an unrelated
    // preference click. (Deletes go through prefs/asset events the same way.)
    const unsubscribeAssets = downloadManager.subscribe((event) => {
      if (event.type === "asset" && event.asset.state === "ready") syncEdgePrefs();
    });
    tier0Available()
      .then((ok) => {
        if (!cancelled) setEdgeTier0Ready(ok);
      })
      .catch(() => {});
    return () => {
      cancelled = true;
      unsubscribeAssets();
      window.removeEventListener(EDGE_PREFS_EVENT, syncEdgePrefs);
    };
  }, []);

  // Latency audit fix #1 (2026-08-06): warm an already-downloaded tier-1
  // engine as soon as this edge-enabled agent is opened/selected (idle
  // time), not on first message send — the first send is where
  // acquireEngine's load path was previously reached for the first time,
  // paying the full engine-init cost synchronously in front of the person's
  // first reply. Fire-and-forget: prewarmTier1Engine returns void, never
  // sets any state here, and never blocks render — it internally no-ops
  // (never downloads) unless a fitting tier-1 model's weights are already
  // verified present on this device (see its own doc comment in tier1.ts).
  // Keyed on agentId so switching agents re-warms for the newly selected
  // one; gated on edgeEnabled() only (same gate every other edge effect in
  // this file uses) so it runs regardless of the person's current on-device
  // opt-in choice — warming costs nothing extra if edge is never actually
  // used this session, and the opt-in can flip on mid-session.
  //
  // Warm-up problem fix (2026-08-07): also re-fires on EDGE_PREFS_EVENT,
  // matching the sibling sync effect above (line ~515) — a preference
  // change made from another surface (e.g. IntelligenceSourceControl's
  // model picker, or the /edge marketplace page) previously only took
  // effect on the NEXT send; this warms the newly-selected model the moment
  // the preference changes, same as this effect already does on agent mount.
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
          // Cold-start honesty fix (2026-08-07, see tier1PreparingNotice's own
          // comment above): getTier1RunModel is side-effect-free (no download,
          // no engine touch) - this only ANSWERS "would tier 1 run for this
          // agent's next send", it doesn't itself warm anything. Cleared below
          // as soon as a real send starts, so it never lingers stale once the
          // authoritative edgePhase indicator takes over.
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

  // Clear the proactive notice the moment a real send starts - from here on,
  // the existing edgePhase/edgeWarmingPercent indicator (already wired to
  // tier1.ts's real progress callback) is the accurate, live signal.
  useEffect(() => {
    if (sending) setTier1PreparingNotice(false);
  }, [sending]);

  // Bug fix (2026-08-06, live user report): "On-device" selected as the
  // overall intelligence source did not make camera/watch frames go
  // on-device too - visionOnDevice is a genuinely separate toggle
  // (CameraPanel's own checkbox) that defaulted off regardless. This turns
  // it on automatically the moment both the general preference AND a ready
  // on-device vision model exist, so the one big toggle actually governs
  // vision - never forces it back OFF, so an explicit manual opt-out via
  // CameraPanel's checkbox still sticks.
  useEffect(() => {
    if (edgeOptIn && edgeVisionReady) setVisionOnDevice(true);
  }, [edgeOptIn, edgeVisionReady, setVisionOnDevice]);

  return {
    edgePhase,
    setEdgePhase,
    edgeWarmingPercent,
    setEdgeWarmingPercent,
    tier1PreparingNotice,
    edgeAbortControllerRef,
    cancelEdgeTurn,
    pendingFallbackAsk,
    setPendingFallbackAsk,
    fallbackAskResolverRef,
    askToFallback,
    lastEdgeModelRef,
    edgeOptIn,
    edgeBannerDismissed,
    edgeTier0Ready,
    edgeVisionReady,
    routingPreview,
  };
}
