// Tier 2: the CPU (WebAssembly) fallback tier.
//
// WHY THIS EXISTS. Until now the local ladder was tier 1 (WebLLM, needs
// WebGPU) and tier 0 (Chrome's built-in Prompt API, needs Chrome with the
// model installed). A device with NEITHER — Firefox, Safari on older
// hardware, a work laptop with WebGPU disabled by policy, any browser without
// the Prompt API — resolved straight to tier 3 and every turn went to the
// hosted pool and its quota. tierRouter.ts carried a "WAVE 2b EXTENSION
// POINT — Tier 2 (WASM fallback)" comment and no module behind it. This is
// that module.
//
// WHAT IT IS. A small instruct model run by @huggingface/transformers (v4,
// already a dependency — this file adds none) on ONNX Runtime's WebAssembly
// backend, inside a dedicated Web Worker so a CPU-bound generation never
// freezes the tab. It deliberately never asks for WebGPU: that is tier 1's
// job, and a device that could run tier 1 should.
//
// SPEED, HONESTLY. This runs the whole transformer on the CPU. It is slow —
// slower than tier 1 on the same device, and far slower than the hosted
// pool. Nobody has measured tokens/sec for this model on this runtime in
// this repo, so no number appears anywhere in this file or in the copy it
// exports; TIER2_SPEED_NOTICE says "much slower" and nothing more precise.
// If a measurement is ever taken, put the number *and the device it was
// measured on* next to it.
//
// FOUR PROPERTIES, and where each is enforced here:
//   1. NO SPEND — nothing in this file calls a platform endpoint. The only
//      hosts it can ever reach are the two named in TIER2_NETWORK_HOSTS, and
//      only during installTier2Model() (see 2).
//   2. NO SURPRISE DOWNLOAD — two independent locks, either one sufficient:
//      (a) STRUCTURAL: every load on the routing path calls
//          applyTier2NetworkPolicy(env, false) before the library is asked
//          for anything. Read what that function sets and why — the shape is
//          NOT the obvious one, and the obvious one is a bug this file
//          already shipped once. In short:
//            - allowRemoteModels = false (the hub is refused), and
//            - allowLocalModels = TRUE, because getModelFile()'s first
//              statement throws when BOTH flags are false, before it ever
//              looks in the cache — so "both false" does not mean
//              "cache-only", it means "nothing can load at all", and
//              allowLocalModels already defaults to false in worker scope.
//            - env.fetch is replaced by a guard that throws unless an
//              install is in progress, which is what keeps allowLocalModels
//              = true from opening a same-origin fetch on a cache miss.
//          The guard alone is NOT the whole lock, and the file does not
//          pretend it is: ONNX Runtime's own wasm loader can fetch its .mjs
//          and .wasm without going through env.fetch, because backends/onnx.js
//          swallows a failed pre-load and lets ORT load the CDN URL itself.
//          So the cache-only path ALSO checks those exact URLs against the
//          Cache API before pipeline() and refuses the load if they are
//          missing (loadAssets/runtimeAssetsCached).
//          Net effect on the routing path: a cache HIT loads; a cache MISS
//          throws, and the misses that the guard cannot make safe are
//          declined before the library is asked. Only installTier2Model()
//          opens the guard, and the load body re-locks it in a finally. Every
//          claim here is read off the INSTALLED package and cited at
//          applyTier2NetworkPolicy; none of it has been exercised in a real
//          browser from this repo (see VERIFICATION STATE below).
//      (b) GATE: getTier2RunModel()/isTier2Ready() walk the real Cache API
//          for the exact files a load would need and answer false unless all
//          of them are already there — the same shape tier1.ts's
//          checkWeightsOnDevice() and localcode/index/indexer.ts's
//          embedderWeightsOnDevice() use. The router calls it exactly as it
//          calls getTier1RunModel.
//      installTier2Model() is the ONLY entry that may move bytes, it goes
//      through the shared downloadManager (visible progress in the /edge
//      chip) and it takes an AbortSignal — cancelling terminates the worker,
//      which is what actually cancels the in-flight fetches (the same thing
//      indexer.ts's installLocalEmbedder does, for the same reason).
//   3. NO SILENT NETWORK — this tier offers no tools, so there is no
//      network:"none" claim to break. What it DOES touch is named:
//      TIER2_NETWORK_HOSTS is the exact host list, it appears verbatim in
//      tier2DownloadDetails() (the consent copy), and the two hosts are
//      already in app/lib/csp.ts's connect-src.
//   4. HONEST DEGRADATION — every failure mode is a typed Tier2Error with a
//      code the router maps to a named outcome: no Worker support, no model
//      cached, a load that would have needed the network, a generation that
//      stalled, an abort. Nothing returns an empty string and calls it an
//      answer (tierRouter treats an empty reply as a failure, same as the
//      other tiers).
//
// VERIFICATION STATE — read this before quoting anything above as proven.
// What IS verified: the library behaviour every claim in this file rests on
// was read out of node_modules/@huggingface/transformers (src/, the sources
// the web build is compiled from), with file and function named at each
// claim; and the main-thread plumbing — which message carries allowNetwork,
// what the readiness walk requires, what an install records — is covered by
// tier2.test.ts, plus applyTier2NetworkPolicy(), which is exported purely so
// the flag combination can be asserted without a worker.
// What is NOT verified: nobody has loaded this model in a real browser from
// this repo. So there is no measured speed, no measured download size, and
// no observed proof that a second page load answers from cache — only the
// code path that should make it do so. Until someone runs it, "tier 2 works
// offline after install" is a design intention, not a result.
//
// WHERE THE WORKER LIVES. The worker body is tier2Worker.ts and the pieces both
// halves share (constants, protocol types, errors, network policy) are
// tier2Shared.ts, re-exported from here so importers see one module. Until
// 2026-09-12 the worker body sat at the bottom of THIS file and the module
// spawned itself as the worker; that self-reference is what hung `next build`
// for the entire app (see tier2Worker.ts's header). Do not fold it back in.
// "onnx-community/Qwen3-0.6B-ONNX". Evidence, all read off the INSTALLED
// @huggingface/transformers@4.2.0 in node_modules (never from memory):
//   - src/pipelines/text-generation.js's own docstring uses exactly this id
//     as its chat-completion example, with `messages`, `dtype`, and a
//     `TextStreamer` — i.e. the installed library documents this repo id for
//     the exact call shape this file makes.
//   - src/models/registry.js maps config model_type "qwen3" →
//     "Qwen3ForCausalLM", so the architecture is served by this build.
//   - src/pipelines/index.js exposes the "text-generation" task and
//     src/utils/devices.js lists "wasm" as a device.
// What could NOT be verified from the installed package, stated rather than
// guessed: which dtype *files* that hub repo actually publishes. TIER2_DTYPE
// is "q8" because src/utils/dtypes.js's DEFAULT_DEVICE_DTYPE_MAPPING maps the
// wasm device to q8 — the library's own default for this backend — and
// DEFAULT_DTYPE_SUFFIX_MAPPING maps q8 to the "_quantized" suffix, giving
// TIER2_WEIGHTS_FILE. If that file is absent from the repo the failure is
// visible and contained: installTier2Model() reports a load error to the
// person who clicked, and the routing path (which requires that exact file to
// be in the cache already) simply keeps answering "not ready". It cannot
// become a silent download or a broken chat.

//
// Split: tier2Presence.ts (markers + Cache API readiness walk) and
// tier2WorkerClient.ts (worker plumbing); this file owns readiness gating, the
// cache-only load, sessions and the explicit install.

import { edgeEnabled } from "./capabilities";
import { TIER2_ASSET_ID, TIER2_MODEL_ID, TIER2_DTYPE, TIER2_DISPLAY_NAME, TIER2_DETAIL_NAME, TIER2_NETWORK_HOSTS, TIER2_STORAGE_BUDGET_BYTES, TIER2_SPEED_NOTICE, TIER2_MAX_NEW_TOKENS, TIER2_HISTORY_CHAR_BUDGET, TIER2_TOKEN_IDLE_TIMEOUT_MS, TIER2_TURN_CEILING_MS, TIER2_LOAD_IDLE_TIMEOUT_MS, Tier2Error } from "./tier2Shared";
import type { Tier2ChatMessage, Tier2ErrorCode } from "./tier2Shared";
export * from "./tier2Shared";
import {
  forgetTier2Readiness,
  isTier2ModelMarked,
  markTier2Installed,
  probeTier2Readiness,
  resetTier2ReadinessProbe,
  tier2FilesOnDevice,
} from "./tier2Presence";
import {
  abandonTier2Request,
  destroyTier2Worker,
  nextTier2RequestId,
  request,
  tier2WorkerState,
} from "./tier2WorkerClient";
export { isTier2ModelMarked } from "./tier2Presence";
export { __setTier2WorkerFactoryForTests } from "./tier2WorkerClient";

/**
 * Can tier 2 serve a turn RIGHT NOW without downloading anything?
 * Never throws, never downloads, never prompts. This is the exact analogue of
 * tier1.ts's getTier1RunModel()/tier1Ready() and the router gates on it the
 * same way.
 */
export async function isTier2Ready(): Promise<boolean> {
  if (typeof window === "undefined") return false;
  if (!edgeEnabled()) return false;
  if (!tier2Supported()) return false;
  // Already loaded in this tab: the strongest possible evidence.
  if (tier2WorkerState.modelLoaded) return true;
  // Cheap synchronous short-circuit: nothing has ever finished installing
  // here, so nothing can be ready and no Cache API work is needed.
  if (!isTier2ModelMarked()) return false;
  return probeTier2Readiness();
}

export interface Tier2ModelInfo {
  id: string;
  displayName: string;
  detailName: string;
  /** True when the runtime this device will use can stream token deltas —
   * only known for certain after a load, so this reports the LAST observed
   * value and defaults to true because the installed package does export
   * TextStreamer (see the worker's `streaming` field for the real check). */
  streaming: boolean;
}

/** The model a tier-2 turn would run RIGHT NOW, or null. Mirrors
 * getTier1RunModel's contract exactly: non-null only when everything needed
 * is already on this device, never a promise of a download. */
export async function getTier2RunModel(): Promise<Tier2ModelInfo | null> {
  if (!(await isTier2Ready())) return null;
  return {
    id: TIER2_MODEL_ID,
    displayName: TIER2_DISPLAY_NAME,
    detailName: TIER2_DETAIL_NAME,
    streaming: tier2WorkerState.lastObservedStreaming,
  };
}

/** Does this environment have the pieces tier 2 needs at all? Synchronous
 * and cheap — no probing, no imports. */
export function tier2Supported(): boolean {
  try {
    if (typeof window === "undefined") return false;
    if (typeof Worker === "undefined") return false;
    if (typeof WebAssembly === "undefined") return false;
    return true;
  } catch {
    return false;
  }
}

/** One generation at a time per worker: the timeout accounting stays
 * per-call and the worker's memory stays bounded. Same idiom as whisper.ts's
 * transcribeChain. */
let generationChain: Promise<unknown> = Promise.resolve();


/** Clears the module's cached state so a test (or a person who just
 * uninstalled the model) starts from zero. */
export function resetTier2State(): void {
  destroyTier2Worker("worker-failed", "tier 2 state reset");
  resetTier2ReadinessProbe();
  tier2WorkerState.lastObservedStreaming = true;
}


// ---------------------------------------------------------------------------
// Cache-only load (the routing path)
// ---------------------------------------------------------------------------

/** Load the model in the worker WITHOUT permitting any network access.
 * Throws a Tier2Error; never downloads (property 2(a) — allowNetwork:false). */
function ensureTier2Loaded(): Promise<void> {
  if (tier2WorkerState.loadInFlight !== null) return tier2WorkerState.loadInFlight;
  if (tier2WorkerState.modelLoaded) return Promise.resolve();
  tier2WorkerState.loadInFlight = (async () => {
    const requestId = nextTier2RequestId();
    const response = await request(
      { type: "load", requestId, modelId: TIER2_MODEL_ID, dtype: TIER2_DTYPE, allowNetwork: false },
      TIER2_LOAD_IDLE_TIMEOUT_MS,
      "load-failed",
      true
    );
    if (response.type !== "loaded") {
      const reason: Tier2ErrorCode = response.type === "error" ? response.reason : "load-failed";
      const detail = response.type === "error" ? response.detail : "unexpected worker reply";
      if (reason === "would-download" || reason === "unavailable") {
        // The readiness gate walked the cache and said yes, and the load then
        // could not find a file. Whatever the gate believed is wrong NOW, so
        // stop believing it: drop the marker and the memoised probe, which
        // makes isTier2Ready() re-walk and answer false. Without this the
        // router keeps reporting this device as "on-device, tier 2" while
        // every turn fails and the person is told the model is not installed
        // — a defined-looking state carrying a false explanation.
        forgetTier2Readiness();
        throw new Tier2Error(
          "unavailable",
          "The CPU on-device model is not installed on this device yet.",
          detail
        );
      }
      throw new Tier2Error(reason, "Could not start the CPU on-device model.", detail);
    }
    tier2WorkerState.modelLoaded = true;
    tier2WorkerState.lastObservedStreaming = response.streaming;
  })().finally(() => {
    tier2WorkerState.loadInFlight = null;
  });
  return tier2WorkerState.loadInFlight;
}

// ---------------------------------------------------------------------------
// Session — the same caller-facing shape as Tier0Session / Tier1Session
// ---------------------------------------------------------------------------

/** Identical call signature to Tier0Session and Tier1Session so tierRouter
 * needs no new concepts: one `prompt` that streams deltas through onToken,
 * aborts through the signal, and resolves to the full reply. The 4th
 * parameter exists ONLY for structural compatibility with Tier1Session's
 * onToolCall — tier 2 has NO tool access at all (see
 * TIER2_NO_TOOLS_DIRECTIVE), so it is accepted and never called. */
export interface Tier2Session {
  prompt(
    text: string,
    onToken?: (delta: string) => void,
    signal?: AbortSignal,
    onToolCall?: (event: { name: string; phase: "calling" | "done" | "error" }) => void
  ): Promise<string>;
  destroy(): void;
}

export interface CreateTier2SessionOptions {
  sessionKey?: string;
  systemPrompt?: string;
}

/** Tier 2 never offers or executes a tool — same situation as tier 0, and the
 * same live failure mode it was written for (a small model asked to read a
 * file confidently inventing the contents). Told plainly to the model rather
 * than blocking the turn, because on a device that reaches tier 2 there is
 * often no other local option. */
const TIER2_NO_TOOLS_DIRECTIVE =
  "\n\nYou are running on this device's processor with no ability to browse the web, read files, " +
  "run code, search a codebase, or call any other tool. You can only use what you already know " +
  "plus what is in this conversation. If you are asked to inspect, analyze, or read a specific " +
  "file, repository, URL, or other live data you have not been given here, say plainly that you " +
  "cannot access it in this mode instead of guessing or inventing plausible-sounding details.";

/** Keep the prompt inside TIER2_HISTORY_CHAR_BUDGET by dropping the OLDEST
 * exchanges first. The system message and the newest user message are never
 * dropped — if those alone exceed the budget the turn still goes through and
 * the model's own window decides, which is honest: this module cannot count
 * tokens and must not pretend it can. */
function budgetMessages(system: string, history: Tier2ChatMessage[], current: string): Tier2ChatMessage[] {
  const fixedCost = system.length + current.length;
  const kept: Tier2ChatMessage[] = [];
  let used = 0;
  for (let i = history.length - 1; i >= 0; i--) {
    const message = history[i];
    const cost = message.content.length;
    if (fixedCost + used + cost > TIER2_HISTORY_CHAR_BUDGET) break;
    used += cost;
    kept.unshift(message);
  }
  const out: Tier2ChatMessage[] = [];
  if (system) out.push({ role: "system", content: system });
  out.push(...kept);
  out.push({ role: "user", content: current });
  return out;
}

/**
 * Create a tier-2 session. Throws Tier2Error("unavailable") when the model is
 * not already installed on this device — the router treats that as a
 * fall-through exactly like tier 0's and tier 1's typed failures, and it can
 * never turn into a download.
 */
export async function createTier2Session(
  options: CreateTier2SessionOptions = {}
): Promise<Tier2Session> {
  if (typeof window === "undefined" || !edgeEnabled()) {
    throw new Tier2Error("unsupported", "On-device mode only runs in a browser.");
  }
  if (!tier2Supported()) {
    throw new Tier2Error(
      "unsupported",
      "This browser cannot run the CPU on-device model (it needs Web Workers and WebAssembly)."
    );
  }
  if (!(await isTier2Ready())) {
    throw new Tier2Error("unavailable", "The CPU on-device model is not installed on this device yet.");
  }
  // Load up front so the first prompt does not silently pay the warm-up.
  // Cache-only: this call is structurally unable to download (see
  // ensureTier2Loaded → allowNetwork:false).
  await ensureTier2Loaded();

  const system = (options.systemPrompt || "") + TIER2_NO_TOOLS_DIRECTIVE;
  let history: Tier2ChatMessage[] = [];

  return {
    async prompt(text, onToken, signal) {
      if (signal?.aborted === true) throw new Tier2Error("aborted", "On-device reply was stopped.");
      // Serialize: one generation at a time on this worker. The annotation is
      // load-bearing: `generationChain` is reassigned from `run` on the next
      // line, so without it the inference is circular and `run` lands as any.
      const run: Promise<string> = generationChain.then(() =>
        runTier2Turn(system, history, text, onToken, signal)
      );
      // Block body, not `() => undefined`: with strictNullChecks off the
      // `undefined` literal widens to any, which trips noImplicitAny here.
      // The rejection is still delivered to the awaiting caller below; this
      // handler only keeps the serialization chain alive.
      generationChain = run.catch(() => {});
      const reply = await run;
      history = [...history, { role: "user", content: text }, { role: "assistant", content: reply }];
      return reply;
    },
    destroy() {
      // Drop the conversation only. The loaded pipeline stays in the worker
      // for the next session — rebuilding it is the expensive part, and this
      // mirrors tier1's "a reset drops history, not the engine" contract.
      history = [];
    },
  };
}

async function runTier2Turn(
  system: string,
  history: Tier2ChatMessage[],
  text: string,
  onToken: ((delta: string) => void) | undefined,
  signal: AbortSignal | undefined
): Promise<string> {
  const requestId = nextTier2RequestId();
  const messages = budgetMessages(system, history, text);

  let abortListener: (() => void) | null = null;
  let ceilingTimer: ReturnType<typeof setTimeout> | null = null;

  const abandon = (reason: Tier2ErrorCode, detail: string): void => {
    // Stop the worker at its next token step, then stop waiting for it. The
    // request is removed from `pending` so its eventual reply is dropped
    // (handleWorkerMessage ignores unknown ids) — the worker finishes the
    // token it is on rather than being terminated, which keeps the loaded
    // pipeline alive for the next turn.
    abandonTier2Request(requestId, reason, detail);
  };

  try {
    const responsePromise = request(
      { type: "generate", requestId, messages, maxNewTokens: TIER2_MAX_NEW_TOKENS },
      TIER2_TOKEN_IDLE_TIMEOUT_MS,
      "generation-timeout",
      true,
      { onToken }
    );
    if (signal) {
      abortListener = () => abandon("aborted", "caller aborted the turn");
      signal.addEventListener("abort", abortListener, { once: true });
    }
    ceilingTimer = setTimeout(
      () => abandon("turn-ceiling-exceeded", `turn exceeded ${TIER2_TURN_CEILING_MS}ms`),
      TIER2_TURN_CEILING_MS
    );

    const response = await responsePromise;
    if (response.type === "done") return response.text;
    const reason: Tier2ErrorCode = response.type === "error" ? response.reason : "prompt-failed";
    const detail = response.type === "error" ? response.detail : "unexpected worker reply";
    if (reason === "aborted") throw new Tier2Error("aborted", "On-device reply was stopped.", detail);
    if (reason === "generation-timeout") {
      throw new Tier2Error("generation-timeout", "The CPU on-device model stopped responding.", detail);
    }
    if (reason === "turn-ceiling-exceeded") {
      throw new Tier2Error("turn-ceiling-exceeded", "The CPU on-device model took too long.", detail);
    }
    throw new Tier2Error(reason, "The CPU on-device model could not finish this reply.", detail);
  } finally {
    if (ceilingTimer) clearTimeout(ceilingTimer);
    if (signal && abortListener) signal.removeEventListener("abort", abortListener);
  }
}

// ---------------------------------------------------------------------------
// Install (explicit user click ONLY — never reached from the routing path)
// ---------------------------------------------------------------------------

/** Honest pre-download copy. Names both hosts (nothing else is reachable),
 * states the speed cost, and gives no size — because no size has been
 * measured, and TIER2_STORAGE_BUDGET_BYTES is explicitly a guess for the
 * headroom check. The chip shows the real byte total the moment the library
 * reports one. */
export function tier2DownloadDetails(): string {
  return (
    `${TIER2_DISPLAY_NAME} — a one-time download from ${TIER2_NETWORK_HOSTS.join(" and ")} ` +
    `(${TIER2_NETWORK_HOSTS[0]} serves the model itself; ${TIER2_NETWORK_HOSTS[1]} serves the ` +
    `WebAssembly runtime that runs it). Cached on this device and reused offline afterwards. ` +
    `${TIER2_SPEED_NOTICE} ${TIER2_DETAIL_NAME}`
  );
}

export type Tier2InstallResult =
  | { ok: true }
  | { ok: false; code: Tier2ErrorCode | "no-storage" | "cancelled"; detail: string };

/**
 * Download + load the tier-2 model. MUST only be called from an explicit user
 * gesture behind tier2DownloadDetails() — this function never prompts and is
 * never called from routeTurn. Progress is visible in the shared /edge chip
 * via downloadManager, and `signal` is a real cancel: aborting terminates the
 * worker, which aborts the library's in-flight fetches. Never rejects.
 */
export async function installTier2Model(options: {
  signal?: AbortSignal;
  onProgress?: (p: { loaded: number; total: number; status: string }) => void;
} = {}): Promise<Tier2InstallResult> {
  const { signal, onProgress } = options;
  if (typeof window === "undefined" || !edgeEnabled()) {
    return { ok: false, code: "unsupported", detail: "edge disabled" };
  }
  if (!tier2Supported()) {
    return { ok: false, code: "unsupported", detail: "no Web Worker or WebAssembly support" };
  }
  // downloadManager and branding are main-thread-only concerns, imported here
  // rather than at module scope so the worker chunk never carries them.
  const [{ downloadManager }, { EDGE_BRANDING }] = await Promise.all([
    import("./downloadManager"),
    import("./branding"),
  ]);

  // A function, not an inline `signal?.aborted === true`: this is checked at
  // several points across awaits, and TypeScript keeps the narrowing from the
  // first check alive through them — the later checks then compile as
  // "'false' and 'true' have no overlap" even though the flag genuinely can
  // flip between them. A call defeats the narrowing and reads the live value.
  const cancelled = (): boolean => signal?.aborted === true;

  if (cancelled()) return { ok: false, code: "cancelled", detail: "cancelled before start" };

  const alreadyOnDevice = (await tier2FilesOnDevice()) === true;
  if (!alreadyOnDevice && !(await downloadManager.hasStorageHeadroomFor(TIER2_STORAGE_BUDGET_BYTES))) {
    downloadManager.reportExternalProgress(TIER2_ASSET_ID, TIER2_DISPLAY_NAME, {
      state: "error",
      error: EDGE_BRANDING.insufficientStorageError,
    });
    return { ok: false, code: "no-storage", detail: "insufficient storage quota" };
  }
  if (cancelled()) return { ok: false, code: "cancelled", detail: "cancelled before start" };

  // No `url`: the manager moves no bytes for this asset, it only renders the
  // progress transformers.js reports back through reportExternalProgress —
  // the same arrangement whisper.ts and vision.ts use for library-owned
  // transfers. totalBytes starts at 0 ("unknown", rendered as no size at all)
  // and becomes real the first time the library reports one.
  downloadManager.enqueue({ id: TIER2_ASSET_ID, displayName: TIER2_DISPLAY_NAME, totalBytes: 0 });

  const onAbort = () => destroyTier2Worker("aborted", "the install was cancelled");
  signal?.addEventListener("abort", onAbort, { once: true });

  try {
    const requestId = nextTier2RequestId();
    const response = await request(
      { type: "load", requestId, modelId: TIER2_MODEL_ID, dtype: TIER2_DTYPE, allowNetwork: true },
      TIER2_LOAD_IDLE_TIMEOUT_MS,
      "load-failed",
      true,
      {
        onProgress: (p) => {
          if (p.status === "downloading" && p.total > 0) {
            downloadManager.reportExternalProgress(TIER2_ASSET_ID, TIER2_DISPLAY_NAME, {
              state: p.loaded >= p.total ? "verifying" : "downloading",
              receivedBytes: p.loaded,
              totalBytes: p.total,
            });
          }
          if (onProgress) {
            try {
              onProgress(p);
            } catch {
              // Best-effort.
            }
          }
        },
      }
    );

    if (cancelled()) {
      downloadManager.reportExternalProgress(TIER2_ASSET_ID, TIER2_DISPLAY_NAME, {
        state: "cancelled",
      });
      return { ok: false, code: "cancelled", detail: "cancelled during download" };
    }
    if (response.type !== "loaded") {
      const code: Tier2ErrorCode = response.type === "error" ? response.reason : "load-failed";
      const detail = response.type === "error" ? response.detail : "unexpected worker reply";
      downloadManager.reportExternalProgress(TIER2_ASSET_ID, TIER2_DISPLAY_NAME, {
        state: "error",
        error: alreadyOnDevice ? "Could not start the CPU on-device model" : EDGE_BRANDING.interruptedError,
      });
      return { ok: false, code, detail };
    }

    tier2WorkerState.modelLoaded = true;
    tier2WorkerState.lastObservedStreaming = response.streaming;
    markTier2Installed(response.runtimeAssetUrls || []);
    downloadManager.reportExternalProgress(TIER2_ASSET_ID, TIER2_DISPLAY_NAME, { state: "ready" });
    void downloadManager.requestPersistentStorage();
    return { ok: true };
  } finally {
    signal?.removeEventListener("abort", onAbort);
  }
}
