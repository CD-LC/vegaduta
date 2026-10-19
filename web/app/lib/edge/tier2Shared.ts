// Tier 2 — the half shared by the main thread (tier2.ts) and the worker
// (tier2Worker.ts): model identity, generation settings, the worker protocol,
// typed errors, <think> handling and the cache-only network policy.
//
// THIS FILE MUST NOT IMPORT ANYTHING AT RUNTIME. It sits inside the worker
// bundle, and the worker bundle must stay "protocol + library" — see the
// header of tier2Worker.ts for why that is a build-time constraint, not a
// style preference. Type-only imports are fine (they are erased).
//
// Everything here is re-exported from tier2.ts, so existing importers
// (tierRouter.ts, tier2.test.ts) are unchanged.

// ---------------------------------------------------------------------------
// The model + the files a load needs
// ---------------------------------------------------------------------------

/** Shared-queue asset id — the /edge chip's row key for this model. */
export const TIER2_ASSET_ID = "edge-tier2-cpu-lm";

/** See the header for the full provenance of this id. */
export const TIER2_MODEL_ID = "onnx-community/Qwen3-0.6B-ONNX";

/** The wasm device's own default dtype in the installed package. */
export const TIER2_DTYPE = "q8";

/** The weights file TIER2_DTYPE resolves to — "_quantized" is the installed
 * package's DEFAULT_DTYPE_SUFFIX_MAPPING entry for q8, and "onnx/" is the
 * fixed subfolder in its get_model_files(). Readiness checks for THIS file,
 * and the load pins THIS dtype, so the check and the load can never disagree
 * about which bytes matter (the drift indexer.ts calls out in its own
 * weightsFileFor()). */
export const TIER2_WEIGHTS_FILE = "onnx/model_quantized.onnx";

/** Files that must ALL be cached for a load to move zero bytes. Kept short on
 * purpose, exactly as indexer.ts argues: a false NEGATIVE costs one extra
 * click that then completes from cache, a false POSITIVE is the unasked-for
 * download this check exists to prevent.
 *
 * Each entry is here because the installed package's text-generation load
 * asks for it with fatal=true, or worse:
 *   - config.json           AutoConfig, every load.
 *   - tokenizer.json        loadTokenizer(), getModelJSON(..., true, ...).
 *   - tokenizer_config.json the SAME fatal load — and it is also the file
 *     get_tokenizer_files() probes to decide whether the model has a
 *     tokenizer at all (utils/model_registry/get_tokenizer_files.js). On a
 *     cache-only load that probe cannot reach the hub, so an uncached
 *     tokenizer_config.json makes get_pipeline_files() return a file list
 *     with no tokenizer.json in it, pipelines.js sets hasTokenizer=false, and
 *     pipeline() RESOLVES with a tokenizer-less generator that then throws on
 *     the first chat turn (TextGenerationPipeline._call calls
 *     this.tokenizer.apply_chat_template). That is the false-positive failure
 *     mode this list exists to prevent, so the file is required here and the
 *     worker additionally refuses a load that came back without a tokenizer.
 *   - the weights file      the big one.
 * generation_config.json is deliberately NOT here: modeling_utils.js loads it
 * through get_optional_configs() with fatal=false, so a load succeeds without
 * it and requiring it would gate the tier off over an optional file. */
export const TIER2_REQUIRED_HUB_FILES = [
  "config.json",
  "tokenizer.json",
  "tokenizer_config.json",
  TIER2_WEIGHTS_FILE,
];

/** The Cache API bucket @huggingface/transformers writes into — env.cacheKey's
 * default in the installed package (src/env.js). Shared with the sibling
 * transformers.js workers, which is fine: entries are keyed by full URL.
 * Declared up here, above the worker section, because BOTH halves read it —
 * the main thread's readiness walk and the worker's pre-load check — and a
 * const declared below the worker body would be a temporal-dead-zone trap
 * waiting for anyone who moves the handler registration earlier. */
export const TRANSFORMERS_CACHE_KEY = "transformers-cache";

/** Brand-first name; raw identity stays in the detail. */
export const TIER2_DISPLAY_NAME = "On-device model — works without a GPU";
export const TIER2_DETAIL_NAME =
  "Qwen3 0.6B Instruct, ONNX build, 8-bit quantized. Runs on the CPU through WebAssembly.";

/** Every host a tier-2 install REQUESTS directly, and what each one serves.
 * Not the same thing as every host the browser ends up connecting to: the
 * large files 302-redirect off huggingface.co to its *.hf.co CDN, which is
 * why app/lib/csp.ts's connect-src carries https://*.hf.co as well. Both
 * entries below are named in the consent copy, and both are in connect-src:
 *   - huggingface.co   the model repo above (config, tokenizer, weights).
 *                      transformers.js's env.remoteHost in the installed
 *                      package; large files 302 to the *.hf.co CDNs.
 *   - cdn.jsdelivr.net ONNX Runtime Web's own .wasm binary and .mjs loader.
 *                      NOT our choice: src/backends/onnx.js in the installed
 *                      package sets wasmPaths to
 *                      https://cdn.jsdelivr.net/npm/onnxruntime-web@<version>/dist/
 *                      whenever wasmPaths is unset, which is the case here.
 *                      Both files are cached into the same transformers cache
 *                      (src/backends/utils/cacheWasm.js), which is why
 *                      readiness below insists they are present before tier 2
 *                      may route — otherwise a "ready" device could still
 *                      pull ~tens of MB from a CDN on its first turn. */
export const TIER2_NETWORK_HOSTS = ["huggingface.co", "cdn.jsdelivr.net"] as const;

/** Budget for the pre-install storage-headroom question, and for NOTHING
 * else. It is a GUESS, not a measurement: nobody here has weighed this
 * download. It is deliberately never shown to a person and never handed to
 * the chip as a transfer total — the chip is told a size only once the
 * library reports a real one (the same rule indexer.ts adopted after its own
 * guess-vs-truth readout bug). Erring small is the dangerous direction for a
 * headroom check, so this errs large. */
export const TIER2_STORAGE_BUDGET_BYTES = 1_200_000_000;

/** Honest, number-free speed copy for any surface that offers this tier. */
export const TIER2_SPEED_NOTICE =
  "This model runs on your processor instead of a graphics card, so replies arrive much more slowly than usual. " +
  "It works with no account and no network once it is installed.";

// ---------------------------------------------------------------------------
// Generation settings (all chosen, none measured — see the header)
// ---------------------------------------------------------------------------

/** Reply cap. Chosen low because every token is CPU work and a runaway
 * generation on this tier is minutes, not seconds. Not a quality judgement
 * and not a measurement. */
export const TIER2_MAX_NEW_TOKENS = 256;

/** How much conversation this tier carries. Characters, not tokens: this
 * module has no tokenizer on the main thread, and a char budget cannot
 * silently under-count the way the chars/N estimators elsewhere in the edge tiers
 * can. Oldest turns are dropped first; the system prompt and the current
 * message are never dropped. */
export const TIER2_HISTORY_CHAR_BUDGET = 4_000;

/** Idle watchdog: no NEW token for this long means the generation is stuck,
 * not merely slow. Deliberately generous — a slow first token on a cold CPU
 * is normal for this tier and must not be mistaken for a hang. */
export const TIER2_TOKEN_IDLE_TIMEOUT_MS = 90_000;

/** Hard ceiling for one turn regardless of progress, so a pathological
 * generation cannot hold the worker forever. */
export const TIER2_TURN_CEILING_MS = 10 * 60_000;

/** Load watchdog (idle-based, re-armed on every progress event) — mirrors
 * whisper.ts's LOAD_IDLE_TIMEOUT_MS. */
export const TIER2_LOAD_IDLE_TIMEOUT_MS = 180_000;

// ---------------------------------------------------------------------------
// Worker protocol (shared by both halves of this file)
// ---------------------------------------------------------------------------

export interface Tier2ChatMessage {
  role: "system" | "user" | "assistant";
  content: string;
}

export type Tier2WorkerRequest =
  | {
      type: "load";
      requestId: number;
      modelId: string;
      dtype: string;
      /** false → `env.allowRemoteModels = false`, i.e. a cache-only load that
       * throws instead of fetching. The routing path ALWAYS sends false. Only
       * installTier2Model sends true. */
      allowNetwork: boolean;
    }
  | {
      type: "generate";
      requestId: number;
      messages: Tier2ChatMessage[];
      maxNewTokens: number;
    }
  | { type: "interrupt"; requestId: number };

export type Tier2WorkerResponse =
  | { type: "progress"; requestId: number; loaded: number; total: number; status: string }
  | {
      type: "loaded";
      requestId: number;
      modelId: string;
      /** MEASURED on this load, not echoed: true only because the worker
       * actually attached a TextStreamer and the library accepted it. */
      streaming: boolean;
      /** The absolute URLs ONNX Runtime's wasm binary and loader were
       * configured to come from, read off env.backends.onnx.wasm.wasmPaths
       * immediately after the library was imported and BEFORE the load could
       * rewrite .mjs to a blob: URL. The main thread stores these so the
       * readiness gate can insist they are cached. */
      runtimeAssetUrls: string[];
    }
  | { type: "token"; requestId: number; delta: string }
  | { type: "done"; requestId: number; text: string }
  | { type: "error"; requestId: number; reason: Tier2ErrorCode; detail: string };

// ---------------------------------------------------------------------------
// Typed errors (mirror of Tier0Error/Tier1Error — same router handling)
// ---------------------------------------------------------------------------

export type Tier2ErrorCode =
  | "unsupported" // no Worker / no WebAssembly / edge disabled / SSR
  | "unavailable" // the model is not cached on this device — nothing to run
  | "load-failed" // the pipeline could not be built from what IS cached
  | "would-download" // a cache-only load asked for the network and was refused
  | "prompt-failed" // the turn itself failed mid-generation
  | "generation-timeout" // no new token for TIER2_TOKEN_IDLE_TIMEOUT_MS
  | "turn-ceiling-exceeded" // the whole turn ran past TIER2_TURN_CEILING_MS
  | "worker-failed" // the worker died / never answered
  | "aborted"; // caller's AbortSignal fired

export class Tier2Error extends Error {
  code: Tier2ErrorCode;
  /** Original message, for logs — never for the UI. */
  detail: string | null;

  constructor(code: Tier2ErrorCode, message: string, detail?: unknown) {
    super(message);
    this.name = "Tier2Error";
    this.code = code;
    this.detail = detail instanceof Error ? detail.message : detail != null ? String(detail) : null;
  }
}

// ---------------------------------------------------------------------------
// <think> handling
// ---------------------------------------------------------------------------

/** The Qwen3 family can open a `<think>` block before its answer. The chat
 * template is asked not to (see the worker's tokenizer_encode_kwargs), but a
 * template that ignores the hint would otherwise stream reasoning into the
 * chat as if it were the reply. This strips complete blocks and, for an
 * UNTERMINATED one (a generation that hit the token cap mid-thought), drops
 * the tail as well — which leaves an empty string, and an empty reply is a
 * failure this tier's callers already fall through on. That is the honest
 * outcome: there was no answer, only an unfinished thought. */
export function stripThinkBlocks(text: string): string {
  let out = "";
  let rest = text;
  for (;;) {
    const open = rest.indexOf("<think>");
    if (open === -1) {
      out += rest;
      break;
    }
    out += rest.slice(0, open);
    const close = rest.indexOf("</think>", open);
    if (close === -1) break; // unterminated — drop everything after it
    rest = rest.slice(close + "</think>".length);
  }
  return out;
}

// ---------------------------------------------------------------------------
// The cache-only load policy — property 2(a), and the one thing in this file
// most worth reading before changing it
// ---------------------------------------------------------------------------

/** Stamped into every error this policy produces so the load handler can tell
 * "this device has not installed the model" apart from a genuine crash,
 * without pattern-matching on library prose. */
export const TIER2_CACHE_ONLY_MARKER = "tier2-cache-only";

/** The subset of @huggingface/transformers' `env` this file writes. Loose on
 * purpose: the library's env is a plain mutable object and this policy is
 * deliberately testable against a stand-in. */
export interface Tier2LibraryEnv {
  allowLocalModels?: boolean;
  allowRemoteModels?: boolean;
  experimental_useCrossOriginStorage?: boolean;
  fetch?: unknown;
  [key: string]: unknown;
}

export type FetchLike = (...args: any[]) => any;

/** The library's own fetch, captured the first time we see an env, so the
 * guard below can hand requests through during an install. */
export let tier2BaseFetch: FetchLike | null = null;

/** Open ONLY while an install load is running. The guard reads this on every
 * call rather than being swapped in and out, so there is no window where a
 * half-applied policy leaves a real fetch installed. */
export let tier2NetworkAllowed = false;

/** Replaces env.fetch for the life of the worker. Almost everything the
 * library fetches goes through env.fetch — utils/hub.js's getFile() (model
 * files AND the same-origin local-model path), get_file_metadata's Range
 * probe, and backends/utils/cacheWasm.js's loadAndCacheFile() for the ONNX
 * Runtime wasm assets.
 *
 * TWO PATHS IN THE INSTALLED PACKAGE DO NOT GO THROUGH IT, and neither is
 * hypothetical — say so rather than calling this a complete lock:
 *   1. utils/cache/CrossOriginStorageCache.js calls global fetch directly. It
 *      is reached only when env.experimental_useCrossOriginStorage is true;
 *      the policy pins that false on both paths, which closes it.
 *   2. ONNX Runtime's OWN loader. backends/onnx.js pre-loads the wasm binary
 *      and .mjs factory through cacheWasm.js (so through this guard) but
 *      wraps each in try/catch and only `logger.warn`s a failure; if the
 *      pre-load fails it restores wasmPaths.mjs to the CDN URL and ORT then
 *      imports that URL and fetches the .wasm itself, with no env.fetch
 *      involved. A refusal here would therefore turn into a jsdelivr fetch
 *      rather than an error. That is why the cache-only path checks those
 *      exact URLs against the Cache API BEFORE calling pipeline() (see
 *      loadAssets) and refuses the load outright, and why readiness requires
 *      them too. Guard plus pre-check, not guard alone.
 *
 * It throws SYNCHRONOUSLY. Every caller is inside an async function, so the
 * throw surfaces as a rejection exactly like a failed fetch would, and
 * loadResourceFile's own try/catch around getFile(localPath) treats it as
 * "not present locally" and falls through to its allowRemoteModels=false
 * error — which is the message the load handler already knows. */
export function tier2GuardedFetch(this: unknown, ...args: any[]): any {
  if (tier2NetworkAllowed) {
    if (!tier2BaseFetch) {
      throw new Error(`${TIER2_CACHE_ONLY_MARKER}: this runtime has no fetch to download with.`);
    }
    return tier2BaseFetch(...args);
  }
  const target = args[0];
  const url = typeof target === "string" ? target : String((target as any)?.url ?? target);
  throw new Error(
    `${TIER2_CACHE_ONLY_MARKER}: refused a network request for "${url}" ` +
      `(env.allowRemoteModels=false — this load may only read the cache).`
  );
}

/**
 * Configure @huggingface/transformers for ONE load. Exported so the flag
 * combination can be asserted in a unit test — the worker is a fake in
 * tier2.test.ts and would otherwise never exercise this at all, which is
 * precisely how the bug below shipped.
 *
 * `allowNetwork` false is the routing path; true is installTier2Model() only.
 *
 * WHY allowLocalModels IS SET **TRUE** ON THE CACHE-ONLY PATH. This reads
 * backwards and is the correction to a real defect: this file used to set
 * only `allowRemoteModels = false` and leave allowLocalModels at its worker
 * default. Read off the installed package:
 *   - src/env.js: `allowLocalModels: !(IS_BROWSER_ENV || IS_WEBWORKER_ENV
 *     || IS_DENO_WEB_RUNTIME)` — false in worker scope, which is our scope.
 *   - src/utils/hub.js, getModelFile(): its FIRST statement is
 *     `if (!env.allowLocalModels) { ... else if (!env.allowRemoteModels)
 *     throw Error('Invalid configuration detected: both local and remote
 *     models are disabled...') }`. That throw happens before getCache(),
 *     before loadResourceFile(), before any cache lookup at all.
 * So "both false" is not a cache-only mode — it is a mode in which nothing
 * loads, ever. A fully installed model could only answer in the tab that
 * installed it, because installTier2Model() leaves workerModelLoaded true
 * for that tab's lifetime; the next page load failed every turn and told the
 * person the model was not installed while the weights sat in the cache.
 *
 * WHY TRUE IS SAFE HERE. With allowLocalModels true, getModelFile() reaches
 * loadResourceFile(), which calls checkCachedResource() FIRST (src/utils/
 * hub.js) — a cache.match() on the local path and then on the hub URL, no
 * request. A cache HIT therefore loads. On a MISS, loadResourceFile would
 * next try getFile(localPath) — a real same-origin request for
 * /models/<repo>/<file> — and that is what tier2GuardedFetch refuses; the
 * function then falls into its `!env.allowRemoteModels` branch and throws
 * without the hub ever being considered. The cache keys are unaffected by
 * this flag (buildResourcePaths derives proposedCacheKey from the remote URL
 * for the browser Cache API, whatever allowLocalModels says), so a model
 * installed with the flag false is still found with it true.
 */
export function applyTier2NetworkPolicy(env: Tier2LibraryEnv, allowNetwork: boolean): void {
  if (typeof env.fetch === "function" && env.fetch !== tier2GuardedFetch) {
    tier2BaseFetch = env.fetch as FetchLike;
  }
  tier2NetworkAllowed = allowNetwork === true;
  env.fetch = tier2GuardedFetch;
  // Pinned false on BOTH paths. CrossOriginStorageCache.js is the only
  // *library* loader that calls global fetch instead of env.fetch, and it is
  // opt-in, so turning it off is enough to close it — unlike ORT's own wasm
  // loader, which needs the pre-check in loadAssets. We want neither.
  env.experimental_useCrossOriginStorage = false;
  if (allowNetwork === true) {
    // Install. allowLocalModels false so a cache miss goes straight to the
    // hub instead of spending a same-origin 404 on /models/... for every file
    // first; the guard is open, so the hub request actually happens.
    env.allowLocalModels = false;
    env.allowRemoteModels = true;
    return;
  }
  // Routing path. See the two paragraphs above for why this is not "both
  // false" — that combination cannot load a cached model.
  env.allowLocalModels = true;
  env.allowRemoteModels = false;
}

/** Test seam: the policy keeps the captured fetch and the open/closed flag in
 * module scope (the worker has exactly one library env), so a test that wants
 * a clean slate without re-importing the module says so explicitly. */
export function __resetTier2NetworkPolicyForTests(): void {
  tier2BaseFetch = null;
  tier2NetworkAllowed = false;
}

// ===========================================================================
