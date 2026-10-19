// The tier-1 engine singleton and its lifecycle (idle teardown, visibility
// invalidation, backend-change release). Split out of ../tier1.ts.
//
// ALL mutable engine state lives on the one `tier1Engine` object so that the
// modules that read it (acquire, generation, installed-model management) share
// a single source of truth instead of each holding a copy. This file imports
// nothing at runtime except the logger - in particular NOT the weight-store
// module - so that module can import this one without a cycle.

import type { MLCEngineInterface } from "@mlc-ai/web-llm";
import { logTier1Event } from "./log";
import type { Tier1CacheBackend } from "./weightStore";

export interface Tier1EngineState {
  instance: MLCEngineInterface | null;
  modelId: string | null;
  inFlight: Promise<MLCEngineInterface> | null;
  /** Set for the duration of teardownIdleTier1Engine's unload() call (TOCTOU
   * fix: engineInFlight alone doesn't cover an in-progress *unload*).
   * acquireEngine awaits this before deciding cache-hit vs. fresh load, so it
   * can never hand back an engine that's mid-unload. */
  tearingDown: Promise<void> | null;
  /** Precise "is the engine object currently being read" counter
   * (incremented/decremented around the actual engine.chat.completions.create
   * call in streamCompletion). Deliberately NOT the broader per-turn
   * getEdgeActivity() signal - that spans a whole turn including its own
   * acquireEngine call, so a turn re-acquiring a different model
   * mid-conversation would see its OWN activity as "active" and wait on
   * itself. */
  readerCount: number;
  /** Whether `instance` is the Service-Worker-backed engine vs. the direct
   * in-page MLCEngine. Tracked so that when the SW turns out
   * evicted/unresponsive, acquireEngine's fallback builds a genuinely fresh
   * in-page engine instead of calling reload() on the dead SW-backed handle. */
  isServiceWorkerBacked: boolean;
  /** Which weight store `instance` was built against. Not derivable from the
   * preference: the preference can move after the engine exists, and a
   * Service-Worker-backed engine resolves its own backend inside the worker.
   * acquireEngine refuses to hand back an engine whose backend no longer
   * matches the resolved one. */
  cacheBackend: Tier1CacheBackend | null;
}

export const tier1Engine: Tier1EngineState = {
  instance: null,
  modelId: null,
  inFlight: null,
  tearingDown: null,
  readerCount: 0,
  isServiceWorkerBacked: false,
  cacheBackend: null,
};

/**
 * Drop the live engine because the weight store changed under it.
 *
 * Synchronous on purpose - the caller is a preference setter that a render
 * path may sit behind - so the handles are cleared immediately and the actual
 * unload() happens in the background once nothing is reading the engine. A
 * turn that is streaming right now holds its own reference (streamCompletion
 * captured it before this ran) and finishes normally on the old engine; only
 * the NEXT acquireEngine sees the cleared state and loads on the new backend.
 * Best-effort by design: an unload() failure still leaves this module with no
 * reference to a stale engine, which is the property that matters here.
 */
export function releaseTier1EngineForBackendChange(): void {
  const previous = tier1Engine.instance;
  if (!previous) {
    tier1Engine.cacheBackend = null;
    return;
  }
  const wasServiceWorkerBacked = tier1Engine.isServiceWorkerBacked;
  tier1Engine.instance = null;
  tier1Engine.modelId = null;
  tier1Engine.isServiceWorkerBacked = false;
  tier1Engine.cacheBackend = null;
  // A Service-Worker-hosted engine is meant to outlive this page and builds
  // its caches from the worker's own globals, so this page can neither
  // reconfigure it nor safely unload it out from under another tab. Dropping
  // the handle is all that is available; acquireEngine re-derives one.
  if (wasServiceWorkerBacked) return;
  void (async () => {
    try {
      await waitForEdgeActivityIdle();
      await previous.unload();
    } catch {
      // Best-effort - the handles above are already gone.
    }
  })();
}

/** Warm-up fix (2026-08-10 edge/WebLLM audit, finding #4): the only prior
 * signal for "can tier 1 serve" was getTier1RunModel/tier1Ready, which check
 * whether weights are cached on DISK — not whether a live, generation-ready
 * engine is sitting in memory right now. Routing/Send had no way to ask "is
 * it actually warm" or bound-wait a few hundred ms for an in-flight prewarm,
 * so a turn arriving seconds after mount raced a still-loading engine and
 * paid the full cold-load path anyway. Synchronous and cheap on purpose — no
 * Cache API or manifest fetch — so callers can check it on every routing
 * decision without cost. */
export function isTier1EngineWarm(modelId: string): boolean {
  return tier1Engine.instance !== null && tier1Engine.modelId === modelId && tier1Engine.tearingDown === null && tier1Engine.inFlight === null;
}

/** Device-lost handling for backgrounded tabs (2026-08-10 audit, finding #9):
 * acquireEngine's cache-hit path (above) returns tier1Engine.instance with no
 * liveness check at all, and detectCapabilities() (capabilities.ts, not
 * owned here) caches webgpu.available in sessionStorage for the tab's whole
 * life with no re-probe — so today NOTHING notices a WebGPU device lost to a
 * long background stretch or a GPU driver reset until the next generation
 * call fails outright (toTier1Error's DeviceLostError branch, which only
 * fires AFTER a real user turn has already paid for it).
 *
 * Checked first, per the brief: MLCEngineInterface (this package's own
 * lib/types.d.ts, @mlc-ai/web-llm@0.2.84) exposes only chat/completions/
 * embeddings/reload/setInitProgressCallback/setAppConfig/setLogitProcessor —
 * no GPUDevice, no context handle, nothing this module could probe directly
 * for liveness. Absent that, the conservative fallback the brief asks for:
 * track how long the tab sat hidden, and on the FIRST visibility recovery
 * after a long-enough background stretch, drop the cached singleton so the
 * next acquireEngine call does a fresh load instead of trusting a context
 * that may already be dead. A wrong invalidation just costs one extra
 * (cache-hit, fast) reload; a missed one is the status quo bug — so this
 * errs toward invalidating.
 *
 * Guarded exactly like every other engine-singleton mutation in this file:
 * never mid-load (tier1Engine.inFlight), never mid-teardown (tier1Engine.tearingDown),
 * and never while anything is actively reading the engine
 * (tier1Engine.readerCount === 0 — the same precise, mid-stream-only counter
 * teardownIdleTier1Engine/waitForEdgeActivityIdle use, not the broader
 * per-turn getEdgeActivity() signal). Never applied to the Service-Worker-
 * backed engine: that engine's WebGPU context lives in the worker's own
 * execution context, not this page's, so THIS page's visibility is not
 * evidence of its device state. The handler itself must never throw — a
 * broken listener must never affect page navigation. */
const TIER1_BACKGROUND_INVALIDATE_MS = 5 * 60_000;
let tier1TabHiddenAt: number | null = null;

function handleTier1VisibilityChange(): void {
  try {
    if (typeof document === "undefined") return;
    if (document.visibilityState === "hidden") {
      tier1TabHiddenAt = Date.now();
      return;
    }
    if (document.visibilityState !== "visible") return;
    const hiddenAt = tier1TabHiddenAt;
    tier1TabHiddenAt = null;
    if (hiddenAt === null) return;
    const hiddenMs = Date.now() - hiddenAt;
    if (hiddenMs < TIER1_BACKGROUND_INVALIDATE_MS) return;
    if (tier1Engine.inFlight !== null || tier1Engine.tearingDown !== null || tier1Engine.readerCount > 0) return;
    if (!tier1Engine.instance || tier1Engine.isServiceWorkerBacked) return;
    logTier1Event({
      phase: "visibility-resume",
      outcome: "engine-invalidated",
      modelId: tier1Engine.modelId,
      hiddenMs,
    });
    tier1Engine.instance = null;
    tier1Engine.modelId = null;
    tier1Engine.cacheBackend = null;
  } catch {
    // A broken listener must never affect page behavior.
  }
}

if (typeof document !== "undefined") {
  document.addEventListener("visibilitychange", handleTier1VisibilityChange);
}

/**
 * Idle-teardown (product spec requirement, 2026-08-07): the module-scope
 * engine singleton above is intentionally reused across turns (no re-init per
 * request — see acquireEngine's cache-hit branch) and now also gets warmed
 * proactively from more surfaces (IntelligenceSourceControl's selection
 * handlers, AgentPlayground's mount/prefs-change effect, ChatPanel's mount
 * effect — see each file's own comment). Without a bound, a person who
 * enables on-device once and never sends another local turn would hold a
 * multi-GB WebGPU context open for the rest of the tab's lifetime. This is a
 * SEPARATE concern from resetEdgeSession (tierRouter.ts) — that drops
 * per-conversation MESSAGE HISTORY only; the engine (loaded weights, GPU
 * context) deliberately survives a reset (same "don't re-init" contract). A
 * long idle window (not per-turn) is what keeps "don't tear down between
 * turns" true while still bounding worst-case GPU memory lifetime.
 */
const TIER1_ENGINE_IDLE_TEARDOWN_MS = 10 * 60_000;
let idleTeardownTimer: ReturnType<typeof setTimeout> | null = null;

/** Ceiling for waitForEdgeActivityIdle below — bounds how long a reload/
 * teardown will defer to an in-progress turn before giving up and proceeding
 * anyway. Matches the existing per-turn ceiling (tier1's own generation
 * timeout) so this can never wait meaningfully longer than a turn is already
 * allowed to run. */
const ACTIVITY_IDLE_WAIT_CEILING_MS = 90_000;

const ENGINE_READERS_POLL_MS = 50;

/**
 * Verify-pass fix (critical finding, then re-fixed after a second verify
 * pass): resolves immediately if nothing is currently reading the engine
 * object; otherwise waits (short-poll on tier1Engine.readerCount, since it's a
 * plain counter with no pub/sub) until it drops to zero, bounded by
 * ACTIVITY_IDLE_WAIT_CEILING_MS so a stuck count can never wedge a caller
 * forever. Used to defer a reload until nothing is mid-generation on the
 * shared engine singleton — "never re-initialize a warm instance
 * mid-request" only holds if reload actually waits for in-flight reads to
 * clear first.
 *
 * Deliberately checks tier1Engine.readerCount (precise: only nonzero for the exact
 * span a streamCompletion call is reading the engine), NOT the broader
 * per-turn getEdgeActivity() signal used elsewhere in this file for UI/
 * telemetry purposes — the FIRST version of this fix used that broader
 * signal and could self-wait: a turn re-acquiring a different (pinned) model
 * mid-conversation already has ITS OWN getEdgeActivity() active by the time
 * it reaches acquireEngine, so it would wait for its own turn to end before
 * being allowed to proceed — up to the full ceiling, every single such turn.
 * tier1Engine.readerCount can't have this problem: it only goes nonzero once a
 * call actually enters the read span, which is always AFTER any
 * acquireEngine call earlier in that same turn's flow.
 */
export function waitForEdgeActivityIdle(): Promise<void> {
  if (tier1Engine.readerCount === 0) return Promise.resolve();
  return new Promise((resolve) => {
    const deadline = Date.now() + ACTIVITY_IDLE_WAIT_CEILING_MS;
    const check = () => {
      if (tier1Engine.readerCount === 0 || Date.now() >= deadline) {
        resolve();
        return;
      }
      setTimeout(check, ENGINE_READERS_POLL_MS);
    };
    check();
  });
}

/** Reused as the "engine touched" hook — called on every successful acquire
 * AND after every completed turn (see createTier1Session's prompt below), so
 * the idle window always measures time since LAST USE, not time since load. */
export function scheduleTier1IdleTeardown(): void {
  if (typeof window === "undefined") return;
  if (idleTeardownTimer) clearTimeout(idleTeardownTimer);
  idleTeardownTimer = setTimeout(() => {
    idleTeardownTimer = null;
    void teardownIdleTier1Engine();
  }, TIER1_ENGINE_IDLE_TEARDOWN_MS);
}

/** Never tears down a Service-Worker-backed engine — that instance is meant
 * to outlive this page (the whole point of the SW path), and a genuinely
 * evicted/killed SW is already handled uniformly by acquireEngine's own
 * fallback. Never tears down mid-load (tier1Engine.inFlight) or mid-generation
 * (guarded by the caller only scheduling this after a turn/load completes,
 * plus the tier1Engine.inFlight check here as a second safeguard). Best-effort —
 * an unload() failure still clears the local handles so the next turn starts
 * clean rather than reusing a possibly-broken engine. */
export async function teardownIdleTier1Engine(cause: "idle" | "incomplete-install" = "idle"): Promise<void> {
  if (!tier1Engine.instance || tier1Engine.inFlight !== null || tier1Engine.isServiceWorkerBacked || tier1Engine.tearingDown !== null) return;
  // Verify-pass fix (critical finding, precision fix on second verify pass):
  // the idle timer can fire while a turn is actively generating
  // (scheduleTier1IdleTeardown resets on every touch, but a single turn
  // running longer than the idle window would still hit this). Never unload
  // out from under a live generation — defer instead of silently
  // teardown-and-corrupt an in-flight response. Checks tier1Engine.readerCount
  // (precise: only nonzero mid-stream), not the broader getEdgeActivity() —
  // see waitForEdgeActivityIdle's doc comment for why the broader signal is
  // wrong here too (would defer teardown for a turn's ENTIRE duration,
  // including time it isn't touching the engine at all).
  if (tier1Engine.readerCount > 0) {
    scheduleTier1IdleTeardown();
    return;
  }
  const modelId = tier1Engine.modelId;
  const teardown = (async () => {
    try {
      await tier1Engine.instance!.unload();
    } catch {
      // Best-effort — still drop the handles below.
    } finally {
      logTier1Event(
        cause === "idle"
          ? { phase: "idle-teardown", modelId, idleMs: TIER1_ENGINE_IDLE_TEARDOWN_MS }
          : { phase: "incomplete-install-teardown", modelId }
      );
      tier1Engine.instance = null;
      tier1Engine.modelId = null;
      tier1Engine.isServiceWorkerBacked = false;
      tier1Engine.cacheBackend = null;
    }
  })();
  // Verify-pass fix (TOCTOU finding): set BEFORE the first await inside the
  // IIFE above yields control, so no interleaving acquireEngine call can read
  // tier1Engine.instance between "decided to tear down" and "actually unloading".
  tier1Engine.tearingDown = teardown;
  try {
    await teardown;
  } finally {
    tier1Engine.tearingDown = null;
  }
}
