// Where tier-1 weights live (Cache API vs the experimental cross-origin
// store), which models are marked installed, and the once-per-session
// verification that the weights are really on the device. Split out of
// ../tier1.ts.

import type { AppConfig } from "@mlc-ai/web-llm";
import { EDGE_PREFS_EVENT } from "../capabilities";
import { releaseTier1EngineForBackendChange, tier1Engine } from "./engineState";

// ---------------------------------------------------------------------------
// Readiness — which model would run, and is it already downloaded?
// ---------------------------------------------------------------------------

/** localStorage marker set only after a model fully loaded once on this
 * device. Its absence reliably means "never downloaded here", so the routing
 * path can answer "not ready" synchronously without touching the Cache API.
 * Its PRESENCE is only a hint: the browser can evict WebLLM's Cache API
 * weights while localStorage survives, so a marker alone never resolves
 * tier 1 — verifyTier1WeightsPresent() below re-checks the actual cache
 * once per session (E-0b rule 6: never resolve a tier on the promise of a
 * download; a stale marker would silently start a multi-GB re-download
 * mid-chat). (Cleared-localStorage-but-kept-Cache edge case: the re-download
 * is a cache hit, so the cost of being wrong is seconds.) */
export const TIER1_CACHED_KEY_PREFIX = "edge.tier1.cached.";

export function safeLocalGet(key: string): string | null {
  try {
    if (typeof localStorage === "undefined") return null;
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

export function safeLocalSet(key: string, value: string): void {
  try {
    if (typeof localStorage === "undefined") return;
    localStorage.setItem(key, value);
  } catch {
    // Best-effort.
  }
}

export function safeLocalRemove(key: string): void {
  try {
    if (typeof localStorage === "undefined") return;
    localStorage.removeItem(key);
  } catch {
    // Best-effort.
  }
}

export function isTier1ModelCached(modelId: string): boolean {
  return safeLocalGet(TIER1_CACHED_KEY_PREFIX + modelId) === "1";
}

// ---------------------------------------------------------------------------
// Which store WebLLM keeps weights in (E-9 shared weights)
// ---------------------------------------------------------------------------

/** The two weight stores this file knows how to reason about.
 *  - "cache":        the Cache API scope below. WebLLM's default
 *                    (prebuiltAppConfig.cacheBackend === "cache", read off
 *                    the installed @mlc-ai/web-llm@0.2.84). Presence is
 *                    readable from this page and deletion works.
 *  - "cross-origin": Chrome's Cross-Origin Storage extension, which WebLLM
 *                    reaches through appConfig.cacheBackend === "cross-origin".
 *                    Weights another site already fetched can be reused, so a
 *                    model can start at zero bytes - but see the two
 *                    limitations recorded on getTier1WeightBackendState().
 * web-llm also ships "indexeddb" and "opfs"; nothing here opts into those, so
 * they are deliberately not modelled. */
export type Tier1CacheBackend = "cache" | "cross-origin";

const TIER1_SHARED_WEIGHTS_PREF_KEY = "edge.tier1.sharedWeights";

/**
 * Is the shared (cross-origin) weight store turned on? EXPERIMENTAL, default
 * OFF, and off is also what an unreadable/absent preference means - enabling
 * it is always someone's explicit choice.
 *
 * Turning it on does NOT by itself make anything download: the download path
 * is still only startTier1Download() behind a user gesture, and the routing
 * path still only runs weights it could positively verify (see
 * checkWeightsOnDevice / getTier1RunModel).
 */
export function getTier1SharedWeightsPreference(): boolean {
  return safeLocalGet(TIER1_SHARED_WEIGHTS_PREF_KEY) === "1";
}

/**
 * Flip the shared-store preference. Three things happen, and the second and
 * third are the ones that make OFF actually mean off:
 *
 *  1. the per-session presence memo is dropped, because "are this model's
 *     weights present" is a backend-specific question and every memoized
 *     answer was computed against the previous backend;
 *  2. a live in-page engine is released. It was built with (or without) an
 *     appConfig pointing at the old store and would keep fetching weights
 *     there for the rest of the session — reporting one backend through
 *     getTier1WeightBackendState() while using the other. Releasing it means
 *     the next turn loads on the backend this page now says it is using. The
 *     engine is unloaded only once nothing is reading it, so a turn that is
 *     streaming right now finishes on the handle it already holds.
 *  3. a Service-Worker-hosted engine cannot be reconfigured from this page at
 *     all (it builds its caches from the worker's own globals), so the handle
 *     is dropped here and acquireEngine re-derives one; see
 *     tier1Engine.cacheBackend for the check that stops a stale one being reused.
 *
 * Call previewTier1SharedWeightsChange(next) FIRST if a person is about to be
 * asked to confirm: it names what the flip costs, which for an off-flip after
 * a shared-store install is a second full download.
 */
export function setTier1SharedWeightsPreference(on: boolean): void {
  const before = resolveTier1CacheBackend();
  safeLocalSet(TIER1_SHARED_WEIGHTS_PREF_KEY, on ? "1" : "0");
  weightPresence.clear();
  if (resolveTier1CacheBackend() !== before) releaseTier1EngineForBackendChange();
  try {
    if (typeof window !== "undefined") window.dispatchEvent(new Event(EDGE_PREFS_EVENT));
  } catch {
    // Best-effort - the preference itself is already stored.
  }
}

/** Is the Cross-Origin Storage extension actually here? The same test
 * WebLLM's own createArtifactCache() makes before it will build the
 * cross-origin cache (CrossOriginStorage.isAvailable() ===
 * navigator.crossOriginStorage !== undefined, read off the installed
 * package) - so this predicts the backend the library will really use,
 * rather than the one we asked for. */
export function crossOriginStorageAvailable(): boolean {
  try {
    if (typeof navigator === "undefined") return false;
    return (navigator as { crossOriginStorage?: unknown }).crossOriginStorage !== undefined;
  } catch {
    return false;
  }
}

/** The backend WebLLM will REALLY use for the next load. Mirrors
 * createArtifactCache()'s own fallback: asking for "cross-origin" without the
 * extension present silently yields the Cache API, and presence detection has
 * to follow the library rather than the preference or it would mislabel every
 * install on such a device. */
export function resolveTier1CacheBackend(): Tier1CacheBackend {
  if (!getTier1SharedWeightsPreference()) return "cache";
  return crossOriginStorageAvailable() ? "cross-origin" : "cache";
}

export type Tier1WeightBackendReason =
  /** Default path: weights live in this origin's Cache API. */
  | "cache-api"
  /** Preference on and the extension is present - the shared store is live. */
  | "cross-origin-active"
  /** Preference on but no extension, so WebLLM falls back to the Cache API. */
  | "cross-origin-unavailable";

export interface Tier1WeightBackendState {
  /** What the preference asks for. */
  preferred: Tier1CacheBackend;
  /** What WebLLM will actually use (see resolveTier1CacheBackend). */
  effective: Tier1CacheBackend;
  crossOriginStorageAvailable: boolean;
  /** Can this page decide, locally and offline, whether a model's weights are
   * present? True only for the Cache API. */
  presenceDeterminable: boolean;
  /** Can this page remove weights from the effective store? */
  deletable: boolean;
  reason: Tier1WeightBackendReason;
}

/**
 * What the on-device model manager and the routing UI need in order to say
 * out loud what is true about weight storage. Two limitations of the
 * cross-origin backend are read straight off @mlc-ai/web-llm@0.2.84 and are
 * the reason it ships OFF:
 *
 *  1. Presence is not locally decidable. ArtifactCrossOriginStorageCache
 *     resolves a file by content hash, and CrossOriginStorage.getFileHash()
 *     obtains that hash by fetching the Hugging Face `/raw/` twin of the
 *     weight URL - a network request, on a device that may be offline, whose
 *     failure is indistinguishable from "the weights are gone". So this page
 *     answers "cannot determine" for that backend instead of guessing.
 *  2. Deletion is not possible. CrossOriginStorage.delete() returns without
 *     doing anything, and deleteTensorCache() early-returns for that cache
 *     class, so nothing this page can call frees those bytes.
 *
 * Synchronous and side-effect-free; safe to call from render.
 */
export function getTier1WeightBackendState(): Tier1WeightBackendState {
  const preferred: Tier1CacheBackend = getTier1SharedWeightsPreference() ? "cross-origin" : "cache";
  const available = crossOriginStorageAvailable();
  const effective = resolveTier1CacheBackend();
  const reason: Tier1WeightBackendReason =
    preferred === "cache" ? "cache-api" : available ? "cross-origin-active" : "cross-origin-unavailable";
  return {
    preferred,
    effective,
    crossOriginStorageAvailable: available,
    presenceDeterminable: effective === "cache",
    deletable: effective === "cache",
    reason,
  };
}

// ---------------------------------------------------------------------------
// Which store each installed model's weights actually went into, and what a
// preference flip therefore costs
// ---------------------------------------------------------------------------

/** Written next to the readiness marker when a download completes, so a later
 * session can say WHERE the bytes went. Without it, the only thing a flipped
 * preference can observe is "the Cache API does not have this model", which
 * reads identically whether the weights were never downloaded or are sitting
 * in the shared store one preference away. */
export const TIER1_WEIGHT_STORE_KEY_PREFIX = "edge.tier1.weightStore.";

export function recordTier1WeightStore(modelId: string, backend: Tier1CacheBackend): void {
  safeLocalSet(TIER1_WEIGHT_STORE_KEY_PREFIX + modelId, backend);
}

/** The store a completed download put this model's weights in, or null when
 * that was never recorded — which is the true answer for every model
 * installed before this key existed, and is deliberately not defaulted to
 * "cache": guessing here would turn "we don't know" into a claim that drives
 * the destructive marker-clearing path below. */
export function getTier1ModelWeightStore(modelId: string): Tier1CacheBackend | null {
  const raw = safeLocalGet(TIER1_WEIGHT_STORE_KEY_PREFIX + modelId);
  return raw === "cache" || raw === "cross-origin" ? raw : null;
}

/** Every model id with a readiness marker on this device. */
function installedTier1MarkerIds(): string[] {
  const ids: string[] = [];
  try {
    if (typeof localStorage === "undefined") return ids;
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i);
      if (key && key.startsWith(TIER1_CACHED_KEY_PREFIX)) {
        ids.push(key.slice(TIER1_CACHED_KEY_PREFIX.length));
      }
    }
  } catch {
    // Best-effort - an unreadable localStorage means "nothing known", which
    // the impact copy below states as such rather than as "nothing at risk".
  }
  return ids;
}

export interface Tier1SharedWeightsChangeImpact {
  /** The backend that would really run right now (preference AND extension). */
  from: Tier1CacheBackend;
  /** The backend that would really run after the flip. */
  to: Tier1CacheBackend;
  /** False when the flip changes the stored preference but not the store that
   * actually runs — the no-extension case. */
  backendChanges: boolean;
  /** Installed models whose weights are recorded as living in `from`. After
   * the flip this page cannot reach them: they are not deleted and (for the
   * shared store) cannot be deleted from here, but running them again means
   * downloading them again into `to`. Flipping back makes them reachable. */
  strandedModelIds: string[];
  /** Installed models with no recorded store, so whether the flip strands
   * them is genuinely unknown until the next presence check. */
  unrecordedModelIds: string[];
  /** A model is loaded in this tab and will be released by the flip (the next
   * turn loads it again, from whichever store `to` names). */
  releasesLoadedEngine: boolean;
  /** Display-safe summary of the three fields above. Says what the flip costs
   * BEFORE it happens; states counts only, because this file knows no sizes
   * (the manifest does) and has measured no download. */
  message: string;
}

/**
 * What flipping the shared-weights preference to `next` would do, computed
 * without changing anything. Exists because the expensive half of this
 * feature is invisible at the moment of the click: weights downloaded into
 * the shared store stay on the disk when it is turned off, but nothing on
 * this page can read them, so the person is offered a fresh multi-GB download
 * of bytes they already have. That is exactly the surprise the feature was
 * gated to avoid, so it has to be said before the switch moves, not after.
 *
 * Synchronous, side-effect-free, safe to call from render.
 */
export function previewTier1SharedWeightsChange(next: boolean): Tier1SharedWeightsChangeImpact {
  const from = resolveTier1CacheBackend();
  const to: Tier1CacheBackend = next ? (crossOriginStorageAvailable() ? "cross-origin" : "cache") : "cache";
  const backendChanges = from !== to;
  const strandedModelIds: string[] = [];
  const unrecordedModelIds: string[] = [];
  if (backendChanges) {
    for (const id of installedTier1MarkerIds()) {
      const store = getTier1ModelWeightStore(id);
      if (store === null) unrecordedModelIds.push(id);
      else if (store === from) strandedModelIds.push(id);
    }
  }
  const releasesLoadedEngine = backendChanges && tier1Engine.instance !== null;

  let message: string;
  if (!backendChanges) {
    message = next
      ? "Shared model storage will stay off in practice: the Chrome Cross-Origin Storage extension isn't " +
        "available in this browser, so models keep downloading into this browser's own storage. Nothing " +
        "already downloaded is affected."
      : "This changes nothing about where models are stored on this device, and nothing already " +
        "downloaded is affected.";
  } else {
    const count = strandedModelIds.length;
    const plural = count === 1 ? "model" : "models";
    const stranded =
      count === 0
        ? ""
        : next
          ? ` ${count} ${plural} already downloaded into this browser's own storage will stop being used ` +
            `while this is on — the files stay on this device and can still be deleted from here, but ` +
            `running ${count === 1 ? "that model" : "those models"} again downloads ${count === 1 ? "it" : "them"} ` +
            `into the shared store. Turning this back off uses them again with no download.`
          : ` ${count} ${plural} downloaded into the shared store will stop being reachable from here — the ` +
            `files are not deleted, and this page cannot delete them, but running ` +
            `${count === 1 ? "that model" : "those models"} again means downloading ` +
            `${count === 1 ? "it" : "them"} a second time into this browser's own storage. Turning this back ` +
            `on uses them again with no download.`;
    const unknown =
      unrecordedModelIds.length === 0
        ? ""
        : ` ${unrecordedModelIds.length} ${unrecordedModelIds.length === 1 ? "model was" : "models were"} ` +
          `downloaded before this app recorded where weights went, so whether ${unrecordedModelIds.length === 1 ? "it stays" : "they stay"} ` +
          `usable can only be checked after the switch.`;
    const engine = releasesLoadedEngine
      ? " A model is loaded in this tab right now; it is released and the next reply loads it again."
      : "";
    message =
      (next
        ? "New model downloads will go to the Chrome Cross-Origin Storage extension's shared store."
        : "New model downloads will go back into this browser's own storage.") +
      stranded +
      unknown +
      engine;
  }

  return { from, to, backendChanges, strandedModelIds, unrecordedModelIds, releasesLoadedEngine, message };
}

/** The AppConfig for the backend that will REALLY run, always defined. Used
 * on the reload path, where an engine built earlier in this session may be
 * pointed at the store the preference has since moved away from - and where
 * "leave it alone" (the old `if (appConfig)` shape) could only ever push the
 * config in one direction, never back. */
export function tier1ResolvedAppConfig(webllm: { prebuiltAppConfig: AppConfig }): AppConfig {
  return { ...webllm.prebuiltAppConfig, cacheBackend: resolveTier1CacheBackend() };
}

/** The AppConfig a fresh load must run with, or undefined to leave WebLLM on
 * its own default. Only ever non-undefined when the shared store is both
 * preferred AND present, so the default path's call shape is untouched
 * (prebuiltAppConfig.cacheBackend is already "cache" in
 * @mlc-ai/web-llm@0.2.84, so the two agree on that path). */
export function tier1EngineAppConfig(webllm: { prebuiltAppConfig: AppConfig }): AppConfig | undefined {
  if (resolveTier1CacheBackend() !== "cross-origin") return undefined;
  return tier1ResolvedAppConfig(webllm);
}

// ---------------------------------------------------------------------------
// Weight-presence verification (E-0b rule 6)
// ---------------------------------------------------------------------------

/** Where @mlc-ai/web-llm@0.2.84 keeps model weights under its DEFAULT
 * cache backend (prebuiltAppConfig.cacheBackend === "cache" →
 * ArtifactCache → caches.open("webllm/model")); nothing here opts into
 * its IndexedDB/OPFS backends. The one other backend this file knows
 * about, "cross-origin", does not use this scope at all — so every read
 * below is Cache-API-specific and may only run when that is the effective
 * backend (Tier1CacheBackend). Entries are keyed by the full weight-shard URLs
 * (https://huggingface.co/mlc-ai/<model id>/resolve/main/…), and
 * tensor-cache.json lists every shard a complete download must have
 * (records[].dataPath) — the same source of truth the library's own
 * hasModelInCache() checks. Verified against the installed package;
 * re-verify on web-llm upgrades. */
export const WEBLLM_MODEL_CACHE_SCOPE = "webllm/model";

/** Per-model, once-per-session memo of the async presence check. A model
 * that finishes downloading this session is recorded as present directly
 * (startTier1Download), so the check itself runs at most once per model. */
export const weightPresence = new Map<string, Promise<boolean>>();

/**
 * Do the model's weights actually exist in WebLLM's store RIGHT NOW?
 *   true  → complete weight set present;
 *   false → definitively absent/incomplete (evicted);
 *   null  → could not determine.
 * Raw Cache API reads only — this must never import web-llm (the whole point
 * is answering readiness without pulling the library into the main path).
 *
 * BACKEND-AWARE, and that is the load-bearing part. The reads below can only
 * see the Cache API scope, so on any other effective backend the honest
 * answer is null. Returning false there would be a lie with a price: false is
 * the one value verifyTier1WeightsPresent() acts on destructively (it clears
 * the readiness marker), so a mis-answered "absent" on a device whose weights
 * are merely stored elsewhere re-prompts the person for a multi-GB download
 * they already paid for. null costs at most a fallback to another tier.
 *
 * @param backend the store to answer for; defaults to the one WebLLM would
 *   actually use right now. Callers that are genuinely asking about the Cache
 *   API itself (listInstalledTier1Models enumerates that scope and nothing
 *   else) pass "cache" explicitly.
 */
export async function checkWeightsOnDevice(
  modelId: string,
  backend: Tier1CacheBackend = resolveTier1CacheBackend()
): Promise<boolean | null> {
  try {
    if (backend !== "cache") return null;
    if (typeof caches === "undefined") return null;
    if (!(await caches.has(WEBLLM_MODEL_CACHE_SCOPE))) return false;
    const cache = await caches.open(WEBLLM_MODEL_CACHE_SCOPE);
    const keys = await cache.keys();
    const needle = `/${modelId}/`;
    const urls = new Set<string>();
    for (const req of keys) {
      if (req.url.includes(needle)) urls.add(req.url);
    }
    if (urls.size === 0) return false;
    // Completeness, not just presence: every shard tensor-cache.json lists
    // must still be cached (a partially-evicted model would otherwise pass
    // and re-download the missing shards mid-chat).
    let indexUrl: string | null = null;
    for (const u of urls) {
      if (/\/(tensor|ndarray)-cache\.json$/.test(u)) {
        indexUrl = u;
        break;
      }
    }
    if (!indexUrl) return false;
    const indexRes = await cache.match(indexUrl);
    if (!indexRes) return false;
    const index = (await indexRes.json()) as { records?: Array<{ dataPath?: string }> };
    const records = Array.isArray(index?.records) ? index.records : null;
    if (!records || records.length === 0) return false;
    for (const record of records) {
      if (!record || typeof record.dataPath !== "string" || !record.dataPath) continue;
      if (!urls.has(new URL(record.dataPath, indexUrl).href)) return false;
    }
    return true;
  } catch {
    return null;
  }
}

/** The memoized session-level presence check. Definitively-missing weights
 * (checkWeightsOnDevice returning exactly false) clear the stale localStorage
 * marker; tier 0 takes over from then on. Two kinds of result never clear it:
 * an indeterminate one (null — a transient Cache API failure, or a backend
 * this page cannot inspect), and a definitive absence for a model this device
 * recorded as installed into the OTHER store, which is "the preference moved",
 * not "the weights are gone". Neither can force a re-download opt-in. Do not
 * collapse the null and false branches. */
function verifyTier1WeightsPresent(modelId: string): Promise<boolean> {
  const existing = weightPresence.get(modelId);
  if (existing !== undefined) return existing;
  const check = (async () => {
    const present = await checkWeightsOnDevice(modelId);
    if (present === false) {
      // ...with one exception, and it is the whole reason the store is
      // recorded: weights this device downloaded into the OTHER store are
      // definitively absent from the effective one (false is the right answer
      // for the backend that will run) but they are not gone. Clearing the
      // marker here would forget a completed multi-GB download because a
      // preference moved, and offer it again as if it had never happened.
      // Not ready either way - only the marker survives.
      const installedInto = getTier1ModelWeightStore(modelId);
      if (installedInto !== null && installedInto !== resolveTier1CacheBackend()) return false;
      safeLocalRemove(TIER1_CACHED_KEY_PREFIX + modelId);
      return false;
    }
    return present === true;
  })().catch(() => false);
  weightPresence.set(modelId, check);
  return check;
}

/** Ready = marker present AND weights verified on device. Marker absent →
 * false immediately, no async cache work (the hot-path short-circuit). */
export async function isTier1ModelReady(modelId: string): Promise<boolean> {
  if (!isTier1ModelCached(modelId)) return false;
  return verifyTier1WeightsPresent(modelId);
}

export function hasAnyTier1Marker(): boolean {
  try {
    if (typeof localStorage === "undefined") return false;
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i);
      if (key && key.startsWith(TIER1_CACHED_KEY_PREFIX)) return true;
    }
  } catch {
    // Fall through.
  }
  return false;
}
