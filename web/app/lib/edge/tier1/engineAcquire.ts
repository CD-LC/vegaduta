// Acquiring the tier-1 engine: the optional Service-Worker host, the direct
// in-page load, load deadlines and the installed-model context windows.
// Split out of ../tier1.ts. @mlc-ai/web-llm is loaded ONLY via dynamic
// import() (see loadWebLlm) - the bundle rule in ../tier1.ts still holds.

import type { InitProgressReport, MLCEngineInterface } from "@mlc-ai/web-llm";
import type { ManifestModel } from "../capabilities";
import { downloadManager } from "../downloadManager";
import { tier1Engine, scheduleTier1IdleTeardown, waitForEdgeActivityIdle } from "./engineState";
import { Tier1Error, toTier1Error } from "./errors";
import { noteTier1LoadFailure, noteTier1LoadSuccess } from "./installStamps";
import { logTier1Event } from "./log";
import { WEBLLM_FALLBACK_CONTEXT_WINDOW } from "./promptBudget";
import { resolveTier1CacheBackend, tier1EngineAppConfig, tier1ResolvedAppConfig } from "./weightStore";

/** The one copy of @mlc-ai/web-llm the browser downloads (security/perf
 *  review FE-050). The page and the tier-1 service worker (public/webllm-sw.js)
 *  both load public/vendored/web-llm.js - copied from node_modules by
 *  scripts/vendor-webllm.mjs at build time and served immutable - instead of
 *  the page pulling a second, bundler-emitted 6 MB chunk of the same library.
 *  The import is left to the browser (bundler-ignored); vitest aliases the URL
 *  back to the package so vi.doMock("@mlc-ai/web-llm") keeps working. */
export const WEBLLM_VENDOR_URL = "/vendored/web-llm.js";
export function loadWebLlm(): Promise<typeof import("@mlc-ai/web-llm")> {
  return import(/* webpackIgnore: true */ /* turbopackIgnore: true */ WEBLLM_VENDOR_URL) as Promise<
    typeof import("@mlc-ai/web-llm")
  >;
}

const TIER1_SERVICE_WORKER_URL = "/webllm-sw.js";

function serviceWorkerSupported(): boolean {
  return typeof window !== "undefined" && typeof navigator !== "undefined" && "serviceWorker" in navigator;
}

let swRegistration: Promise<ServiceWorkerRegistration | null> | null = null;

/** Idempotent — one register() call per page load, reused (and memoized,
 * including failures) by every caller. Never throws. */
function ensureTier1ServiceWorkerRegistered(): Promise<ServiceWorkerRegistration | null> {
  if (!serviceWorkerSupported()) return Promise.resolve(null);
  if (swRegistration !== null) return swRegistration;
  swRegistration = navigator.serviceWorker
    .register(TIER1_SERVICE_WORKER_URL, { type: "module" })
    .catch((): null => null);
  return swRegistration;
}

/** Bounded attempt to acquire the engine via the persistent Service Worker.
 * Resolves null (never rejects) on anything short of success — unsupported
 * browser, registration failure, or a claimed-warm SW that never answers
 * (evicted/killed) — so acquireEngine's caller can fall back to the direct
 * in-page cold-load path uniformly, without special-casing errors. Shares
 * withEngineLoadDeadline with the cold-load path (defined below) so an
 * evicted SW fails fast rather than hanging: this attempt gets a shorter
 * slice of the budget, leaving the rest for a full cold GPU init fallback
 * within the SAME overall ENGINE_ACQUIRE_TIMEOUT_MS ceiling the caller in
 * createTier1Session already applies. */
const SW_ENGINE_ATTEMPT_TIMEOUT_MS = 20_000;

async function acquireEngineViaServiceWorker(
  model: ManifestModel,
  onProgress: (report: InitProgressReport) => void
): Promise<MLCEngineInterface | null> {
  if (!serviceWorkerSupported()) return null;
  try {
    const registration = await ensureTier1ServiceWorkerRegistered();
    if (!registration) return null;
    const webllm = await loadWebLlm();
    captureInstalledContextWindows(webllm);
    return await withEngineLoadDeadline(
      // D-9: third arg is the admin-set context_window_size override
      // (undefined = keep the engine's record default, pre-existing behavior).
      webllm.CreateServiceWorkerMLCEngine(
        model.id,
        { initProgressCallback: onProgress },
        adminContextChatOpts(model)
      ),
      SW_ENGINE_ATTEMPT_TIMEOUT_MS
    );
  } catch {
    // Evicted/dead/never-installed SW, message-protocol mismatch, or a
    // timed-out respawn — all fall back the same way, in acquireEngine below.
    return null;
  }
}

function progressReporter(model: ManifestModel): (report: InitProgressReport) => void {
  return (report) => {
    try {
      const fraction = Math.max(0, Math.min(1, report.progress || 0));
      const fromCache = /cache/i.test(report.text || "");
      downloadManager.reportExternalProgress(model.id, model.displayName, {
        // Loading already-cached weights is the "warming up" state, not a
        // re-download — the chip renders "verifying" as warm-up (E-0b rule 3).
        state: fraction >= 1 || fromCache ? "verifying" : "downloading",
        receivedBytes: Math.round(fraction * model.sizeBytes),
        totalBytes: model.sizeBytes,
      });
    } catch {
      // Progress is best-effort — never break the load over it.
    }
  };
}

export interface AcquireEngineOptions {
  /** Default true: try the Service-Worker-hosted engine first (see the SW
   * section above), falling back to the direct in-page engine on failure/
   * timeout/eviction. startTier1Download passes false — a brand-new,
   * not-yet-cached model's multi-GB download keeps EXACTLY its pre-existing
   * unbounded, direct-in-page behavior; routing that through the SW's own
   * 20s-bounded attempt would wrongly fail over a legitimate large transfer. */
  viaServiceWorker?: boolean;
}

/** Warm-up fix (2026-08-10 edge/WebLLM audit, finding #1 — the literal bug
 * behind "no proper warm up and ready call to llm"): WebGPU compiles its
 * shaders lazily on the FIRST chat.completions.create() dispatch, not on
 * weight load. Reporting "ready"/"warmed" the instant CreateMLCEngine/
 * reload() resolves left that 2-3x-slower compile cost sitting on the
 * user's real first message regardless of how early prewarmTier1Engine ran.
 * One discarded 1-token completion here pays that cost during warm-up
 * instead. Best-effort: a priming failure must never block reporting the
 * engine ready — the real turn's own completion call still gets its normal
 * error handling either way. */
async function primeTier1EngineShaderCompile(engine: MLCEngineInterface, modelId: string): Promise<void> {
  try {
    await engine.chat.completions.create({
      messages: [{ role: "user", content: "Hi" }],
      max_tokens: 1,
    });
  } catch (err) {
    // 2026-08-10 audit finding #13 (LOW, kept small per the brief — no new
    // endpoint, no new infra): this used to swallow every priming failure
    // with zero signal, so a fleet-wide shader-compile regression (a driver/
    // browser update, a model whose kernels don't compile on some GPU class)
    // was invisible until it showed up as slow first-turns with no error
    // anywhere. Reuses the exact same structured shape every other
    // logTier1Event call site already emits (phase/outcome/modelId/detail) —
    // one console.debug line, still never a network call, still never
    // affects whether the real turn's own completion call gets its normal
    // (typed, user-visible) error handling. Best-effort only: a logging
    // failure must never surface here either.
    logTier1Event({
      phase: "shader-prime",
      outcome: "error",
      modelId,
      detail: err instanceof Error ? err.message : String(err),
    });
  }
}

export async function acquireEngine(
  model: ManifestModel,
  options: AcquireEngineOptions = {}
): Promise<MLCEngineInterface> {
  // Verify-pass fix (TOCTOU finding): never read tier1Engine.instance while a
  // concurrent idle-teardown is mid-unload() — wait for it to settle first,
  // then re-evaluate below with fresh (post-teardown) state.
  if (tier1Engine.tearingDown !== null) {
    await tier1Engine.tearingDown.catch(() => {});
  }
  // A warm engine is only a cache hit while it still runs on the store this
  // page says it is using. tier1Engine.cacheBackend moves only when an engine is
  // built; the preference can move at any time, and an engine built under the
  // shared store keeps fetching weights there (and paying its /raw/ checksum
  // fetches) no matter what the preference now reads. Falling through instead
  // reloads it on the current backend - the divergence lasts one load, not the
  // rest of the session.
  if (tier1Engine.instance && tier1Engine.modelId === model.id && tier1Engine.cacheBackend === resolveTier1CacheBackend()) {
    scheduleTier1IdleTeardown(); // cache hit still counts as "used" — reset the idle clock
    return tier1Engine.instance;
  }
  if (tier1Engine.inFlight !== null) {
    // Serialize: let the in-flight load settle, then re-check.
    try {
      await tier1Engine.inFlight;
    } catch {
      // The previous load failed — retry fresh below.
    }
    if (tier1Engine.instance && tier1Engine.modelId === model.id && tier1Engine.cacheBackend === resolveTier1CacheBackend()) {
      scheduleTier1IdleTeardown();
      return tier1Engine.instance;
    }
  }
  const load = (async (): Promise<MLCEngineInterface> => {
    const onProgress = progressReporter(model);
    // The SW-hosted engine builds its caches inside the worker, from the
    // worker's own globals - this page cannot pass it an appConfig and cannot
    // observe which backend it resolved there. With the shared store active
    // that would mean loading through a backend we did not choose and cannot
    // reason about, so the shared store keeps the in-page path, where the
    // backend we resolved is the backend that runs.
    if (options.viaServiceWorker !== false && resolveTier1CacheBackend() === "cache") {
      const swEngine = await acquireEngineViaServiceWorker(model, onProgress);
      if (swEngine) {
        // Leak fix (finding #3): the previous handle is about to be
        // orphaned — wait for any live generation reading it to finish
        // (mirrors the reload-in-place branch below), then best-effort
        // release it before this module's only reference moves to swEngine.
        const previousEngine = tier1Engine.instance;
        if (previousEngine && previousEngine !== swEngine) {
          await waitForEdgeActivityIdle();
          void previousEngine.unload().catch(() => {});
        }
        tier1Engine.instance = swEngine;
        tier1Engine.modelId = model.id;
        tier1Engine.isServiceWorkerBacked = true;
        // The SW path is only entered when the Cache API is the effective
        // backend (the guard above), and the worker's own default is that
        // same backend - so this records what actually ran, not a wish.
        tier1Engine.cacheBackend = "cache";
        noteTier1LoadSuccess(model.id); // clears any load-failure history (cacheCompat)
        await primeTier1EngineShaderCompile(swEngine, model.id);
        downloadManager.reportExternalProgress(model.id, model.displayName, {
          state: "ready",
          receivedBytes: model.sizeBytes,
          totalBytes: model.sizeBytes,
        });
        scheduleTier1IdleTeardown();
        return swEngine;
      }
      // SW unavailable, registration failed, or the attempt timed out
      // (evicted/unresponsive SW) — fall through to the direct in-page cold
      // load below exactly as if the SW path had never been attempted. Never
      // hangs: acquireEngineViaServiceWorker only ever resolves, never rejects.
    }
    // THE dynamic import — the only place @mlc-ai/web-llm code is pulled into
    // THIS page's own bundle (the SW attempt above dynamic-imports it too,
    // but inside the worker script, never here).
    const webllm = await loadWebLlm();
    captureInstalledContextWindows(webllm);
    // undefined on the default path, so the call shape below is unchanged
    // unless someone turned the experimental shared store on AND the
    // extension is present.
    const appConfig = tier1EngineAppConfig(webllm);
    const engineConfig = appConfig
      ? { initProgressCallback: onProgress, appConfig }
      : { initProgressCallback: onProgress };
    try {
      let engine: MLCEngineInterface;
      if (tier1Engine.instance && !tier1Engine.isServiceWorkerBacked) {
        // Model switch — or a same-model weight-store switch, which reaches
        // here for the same reason: the cache-hit check above refuses an
        // engine whose backend no longer matches. Either way, reload in place
        // rather than leaking a second engine (two loaded models would double
        // GPU memory). Only valid when the
        // existing instance is itself a direct in-page engine — reusing a
        // SW-backed handle here would just retry the same dead message
        // channel the SW attempt above already gave up on.
        //
        // Verify-pass fix (critical finding): a model-switch reload calls
        // engine.reload() on the SAME object a live generation may still be
        // reading from (runTurn/streamCompletion hold their own reference
        // from an earlier acquireEngine call) — reloading it mid-stream both
        // corrupts that turn and violates "never re-initialize a warm
        // instance mid-request". Defer the reload until nothing is actively
        // generating on this engine; bounded by
        // ACTIVITY_IDLE_WAIT_CEILING_MS so a stuck flag can't wedge this
        // forever. This only affects the RELOAD path — a fresh
        // CreateMLCEngine below (no prior instance, or SW-backed) never
        // touches a live object and needs no such wait.
        await waitForEdgeActivityIdle();
        // Re-check after the wait: a concurrent idle-teardown may have
        // unloaded/cleared tier1Engine.instance (or, in principle, a SW takeover)
        // while we were waiting for activity to clear — never reload() a
        // stale/null reference, fall through to a fresh create instead.
        if (tier1Engine.instance && !tier1Engine.isServiceWorkerBacked) {
          engine = tier1Engine.instance;
          engine.setInitProgressCallback(onProgress);
          // A reload reuses an engine built earlier in this session, possibly
          // before the preference changed - point it at the backend that is
          // effective NOW. Unconditionally, in both directions: the old
          // `if (appConfig)` shape could only ever push the shared store ON,
          // so turning the preference off left a reused engine still writing
          // to (and reading from) the extension's store while every status
          // surface on this page reported the Cache API.
          engine.setAppConfig?.(tier1ResolvedAppConfig(webllm));
          tier1Engine.modelId = null; // invalid during reload
          // D-9: reload honors the admin context override too. The no-override
          // call stays EXACTLY arity-1 (not an explicit undefined) so the
          // pre-existing call shape — and everything asserting on it — is
          // untouched when no admin value exists.
          const reloadOpts = adminContextChatOpts(model);
          await (reloadOpts ? engine.reload(model.id, reloadOpts) : engine.reload(model.id));
        } else {
          // Leak fix (finding #3): tier1Engine.instance here is either null or a
          // dead/evicted SW handle (the SW attempt above already failed for
          // this exact acquisition) — best-effort release it before this
          // module's only reference moves to the fresh in-page engine.
          const previousEngine = tier1Engine.instance;
          engine = await webllm.CreateMLCEngine(model.id, engineConfig, adminContextChatOpts(model));
          if (previousEngine && previousEngine !== engine) {
            await waitForEdgeActivityIdle();
            void previousEngine.unload().catch(() => {});
          }
          tier1Engine.instance = engine;
        }
      } else {
        const previousEngine = tier1Engine.instance;
        engine = await webllm.CreateMLCEngine(model.id, engineConfig, adminContextChatOpts(model));
        if (previousEngine && previousEngine !== engine) {
          await waitForEdgeActivityIdle();
          void previousEngine.unload().catch(() => {});
        }
        tier1Engine.instance = engine;
      }
      tier1Engine.modelId = model.id;
      tier1Engine.isServiceWorkerBacked = false;
      tier1Engine.cacheBackend = resolveTier1CacheBackend();
      noteTier1LoadSuccess(model.id); // clears any load-failure history (cacheCompat)
      await primeTier1EngineShaderCompile(engine, model.id);
      downloadManager.reportExternalProgress(model.id, model.displayName, {
        state: "ready",
        receivedBytes: model.sizeBytes,
        totalBytes: model.sizeBytes,
      });
      scheduleTier1IdleTeardown();
      return engine;
    } catch (err) {
      // A failed create/reload (device lost, GPU OOM) leaves nothing usable —
      // drop the instance so the next attempt starts clean.
      tier1Engine.instance = null;
      tier1Engine.modelId = null;
      tier1Engine.cacheBackend = null;
      tier1Engine.isServiceWorkerBacked = false;
      const typed = toTier1Error(err, "engine-failed");
      // 2026-09-19: remember that an INSTALLED model failed to load, so the
      // model manager can say why on-device "stopped working" and offer a
      // re-download (getTier1CacheHealth) instead of silently falling through
      // to hosted forever. No-op for first-time downloads and for GPU-memory
      // loss it can recognise (DeviceLostError, or out-of-memory / device-lost
      // in the message); an unlabelled GPU failure is counted — see
      // noteTier1LoadFailure for why that is safe.
      noteTier1LoadFailure(model.id, typed);
      throw typed;
    }
  })();
  tier1Engine.inFlight = load.finally(() => {
    tier1Engine.inFlight = null;
  });
  return tier1Engine.inFlight;
}

/**
 * Bug fix (2026-08-06 diagnosis): `acquireEngine`'s `CreateMLCEngine`/
 * `reload` call has no internal timeout — a stalled WebGPU load (driver
 * contention, backgrounded tab, a stuck shader compile) previously left the
 * awaiting caller hanging forever, which meant `createTier1Session` never
 * returned, `tryTier1`/`routeTurn` never returned, and `sendViaEdge` never
 * cleared its `sending`/`edgePhase` state. This wrapper bounds ONLY the
 * turn-facing wait (`createTier1Session` below) — it does NOT touch
 * `acquireEngine` itself or `startTier1Download`'s explicit, user-initiated
 * multi-GB download, which legitimately needs an unbounded wait. On timeout
 * the real load is left running in the background (nothing here can cancel
 * WebLLM's in-flight promise); if it later succeeds, `tier1Engine.instance` is
 * still set for the next attempt to reuse. Because each new call to
 * `createTier1Session` races the SAME deadline again from scratch, a
 * permanently-stuck `tier1Engine.inFlight` no longer wedges every subsequent
 * attempt — each one fails fast (bounded) instead of hanging forever, and
 * the router's existing fall-through to tier 0 / hosted takes over.
 */
export const ENGINE_ACQUIRE_TIMEOUT_MS = 60_000;

export function withEngineLoadDeadline<T>(promise: Promise<T>, ms: number): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => {
      reject(
        new Tier1Error(
          "engine-failed",
          "The on-device model is taking too long to start.",
          "engine-load-timeout"
        )
      );
    }, ms);
    promise.then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      (err) => {
        clearTimeout(timer);
        reject(err);
      }
    );
  });
}

/** context_window_size per installed-engine model record, captured verbatim
 * from @mlc-ai/web-llm's prebuiltAppConfig at the existing dynamic-import
 * sites (never guessed, never hardcoded here — the fallback-manifest values
 * above are the server seed's mirror, this map is the live package's own). */
const installedRecordWindows = new Map<string, number>();

function captureInstalledContextWindows(webllm: unknown): void {
  try {
    const list = (webllm as { prebuiltAppConfig?: { model_list?: unknown } })?.prebuiltAppConfig?.model_list;
    if (!Array.isArray(list)) return;
    for (const record of list) {
      const id = (record as { model_id?: unknown })?.model_id;
      const cw = (record as { overrides?: { context_window_size?: unknown } })?.overrides?.context_window_size;
      if (typeof id === "string" && typeof cw === "number" && Number.isFinite(cw) && cw > 0) {
        installedRecordWindows.set(id, Math.trunc(cw));
      }
    }
  } catch {
    // Best-effort — resolveTier1ContextWindow falls back to 4096.
  }
}

/** Discovery precedence per the design: admin/manifest value → installed
 * engine record → conservative 4096. */
export function resolveTier1ContextWindow(model: ManifestModel): number {
  if (model.contextWindowSize != null && model.contextWindowSize > 0) {
    return Math.trunc(model.contextWindowSize);
  }
  return installedRecordWindows.get(model.id) ?? WEBLLM_FALLBACK_CONTEXT_WINDOW;
}

/** The engine-load override (WebLLM ChatOptions.context_window_size): passed
 * ONLY when an admin actually set a manifest value — otherwise the engine
 * keeps its own record default, exactly as before this change. */
function adminContextChatOpts(model: ManifestModel): { context_window_size: number } | undefined {
  return model.contextWindowSize != null && model.contextWindowSize > 0
    ? { context_window_size: Math.trunc(model.contextWindowSize) }
    : undefined;
}
