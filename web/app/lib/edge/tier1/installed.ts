// Installed-model management (the marketplace's list / delete / status
// surface). Split out of ../tier1.ts. All raw Cache API reads except delete,
// which prefers WebLLM's own deleteModelAllInfoInCache - acceptable to
// dynamic-import there because deletion is a rare, explicit management click,
// never the routing hot path.

import { modelFits, type EdgeCapabilities, type ManifestModel } from "../capabilities";
import { downloadManager, type AssetStatus } from "../downloadManager";
import { loadWebLlm } from "./engineAcquire";
import { tier1Engine } from "./engineState";
import { TIER1_LOAD_FAILURE_KEY_PREFIX, TIER1_STAMP_KEY_PREFIX } from "./installStamps";
import { fetchTier1Manifest } from "./manifest";
import {
  TIER1_CACHED_KEY_PREFIX,
  TIER1_WEIGHT_STORE_KEY_PREFIX,
  WEBLLM_MODEL_CACHE_SCOPE,
  checkWeightsOnDevice,
  getTier1WeightBackendState,
  safeLocalRemove,
  weightPresence,
} from "./weightStore";


/** UI-facing per-model lifecycle state. "downloading" covers paused transfers
 * too — WebLLM's own fetcher (the engine that moves tier-1 bytes) cannot
 * pause/resume, so a distinct paused state would be a dead UI affordance
 * here; byte-level progress {receivedBytes,totalBytes} rides the
 * downloadManager AssetStatus stream as always. */
export type Tier1InstallState =
  | "not-installed"
  | "queued"
  | "downloading"
  | "verifying"
  | "ready"
  /** Presence could not be determined — the effective backend is one this
   * page cannot inspect, or the Cache API read failed. NOT a synonym for
   * "not-installed": offering a download here is how someone ends up paying
   * for multi-GB weights twice, so the UI must say it does not know. */
  | "unknown"
  | "failed";

export interface InstalledTier1Model {
  id: string;
  /** Manifest display name when the id is still published, else the raw id
   * (a model can outlive its manifest entry on a device). */
  displayName: string;
  /** MEASURED bytes in WebLLM's weight cache (Cache API), not the manifest
   * estimate — this is the honest "space used on this device" number. */
  sizeBytes: number;
  /** False when shards were partially evicted (tensor-cache.json incomplete)
   * — the UI should offer re-download or delete, never "ready". */
  complete: boolean;
  /** Can this entry actually be removed from the device by deleteTier1Model?
   * True for everything this list reports, because the list enumerates the
   * Cache API scope and that scope is deletable. Weights held by the
   * cross-origin store are neither enumerable nor deletable from this page
   * (getTier1WeightBackendState explains why) — they never appear here at
   * all, which is exactly why the manager UI has to state the backend rather
   * than let an empty list stand in for "nothing is installed". */
  deletable: boolean;
}

/** WebLLM weight-shard URLs look like
 * https://huggingface.co/mlc-ai/<model id>/resolve/main/<file> — the segment
 * before /resolve/ is the model id (MLC repo naming == prebuilt model_id). */
function modelIdOfWeightUrl(url: string): string | null {
  const m = /\/([^/]+)\/resolve\//.exec(url);
  if (!m) return null;
  try {
    return decodeURIComponent(m[1]);
  } catch {
    return null;
  }
}

/** Measured response size without materializing GB-class bodies: the
 * content-length header when present (the normal case for cached responses),
 * else the blob's size. */
async function responseSize(res: Response): Promise<number> {
  const len = Number(res.headers.get("content-length"));
  if (Number.isFinite(len) && len > 0) return len;
  try {
    return (await res.blob()).size;
  } catch {
    return 0;
  }
}

/**
 * Every tier-1 model with weights in the Cache API scope on THIS device, with
 * measured sizes — the "Manage on-device models" list. Never rejects; an
 * environment without the Cache API answers [].
 *
 * Deliberately Cache-API-only: those are the bytes this page can measure and
 * delete. When getTier1WeightBackendState().effective is "cross-origin" this
 * list is therefore NOT the whole picture, and [] does not mean "no models"
 * — it means "none in the store I can see". The manager must render the
 * backend state alongside it.
 */
export async function listInstalledTier1Models(): Promise<InstalledTier1Model[]> {
  try {
    if (typeof caches === "undefined") return [];
    if (!(await caches.has(WEBLLM_MODEL_CACHE_SCOPE))) return [];
    const cache = await caches.open(WEBLLM_MODEL_CACHE_SCOPE);
    const keys = await cache.keys();
    const byModel = new Map<string, Request[]>();
    for (const req of keys) {
      const id = modelIdOfWeightUrl(req.url);
      if (!id) continue;
      const list = byModel.get(id);
      if (list) list.push(req);
      else byModel.set(id, [req]);
    }
    if (byModel.size === 0) return [];
    // Display names from the manifest when available (fetchTier1Manifest never
    // rejects and answers its session cache after the first call).
    const manifest = await fetchTier1Manifest();
    const nameOf = new Map(manifest.models.map((m) => [m.id, m.displayName]));
    const installed: InstalledTier1Model[] = [];
    for (const [id, reqs] of byModel) {
      let sizeBytes = 0;
      for (const req of reqs) {
        const hit = await cache.match(req);
        if (hit) sizeBytes += await responseSize(hit);
      }
      installed.push({
        id,
        displayName: nameOf.get(id) || id,
        sizeBytes,
        // Explicitly the Cache API question: this loop is enumerating that
        // scope, so "complete" must be judged against it and not against
        // whichever backend the engine happens to be configured for.
        complete: (await checkWeightsOnDevice(id, "cache")) === true,
        deletable: true,
      });
    }
    installed.sort((a, b) => b.sizeBytes - a.sizeBytes);
    return installed;
  } catch {
    return [];
  }
}

/**
 * One model's lifecycle state RIGHT NOW: an in-flight downloadManager entry
 * wins (queued/downloading/verifying/failed), else the cache decides
 * (ready/not-installed). Never rejects.
 */
export async function getTier1ModelInstallState(modelId: string): Promise<Tier1InstallState> {
  try {
    const asset = downloadManager.getAsset(modelId);
    if (asset) {
      if (asset.state === "queued") return "queued";
      if (asset.state === "downloading" || asset.state === "paused") return "downloading";
      if (asset.state === "verifying") return "verifying";
      if (asset.state === "error") return "failed";
      // "ready"/"cancelled" fall through to the cache truth below — a session
      // row alone is not proof the weights are still on disk.
    }
    const present = await checkWeightsOnDevice(modelId);
    if (present === true) return "ready";
    if (present === false) return "not-installed";
    return "unknown";
  } catch {
    return "unknown";
  }
}

/** One marketplace row: the manifest entry joined with this device's state. */
export interface Tier1MarketplaceEntry {
  model: ManifestModel;
  /** Device meets the model's published minimums (informational — a user may
   * still force-download; the router's fall-through catches hard failures). */
  fits: boolean;
  state: Tier1InstallState;
  /** MEASURED on-device bytes (0 when not installed). */
  installedBytes: number;
  /** Live download row when one is in flight — receivedBytes/totalBytes give
   * the progress bar; subscribe to downloadManager for updates. */
  activeDownload: AssetStatus | null;
}

/**
 * The full tier-1 marketplace: every serveable manifest model with fits +
 * install state + measured size, ready for the picker/marketplace UI. One
 * call per render is fine — the manifest is session-cached and the Cache API
 * reads are header-only. Never rejects.
 */
export async function listTier1Marketplace(caps: EdgeCapabilities): Promise<Tier1MarketplaceEntry[]> {
  try {
    const manifest = await fetchTier1Manifest();
    const tier1Models = manifest.models.filter((m) => m.tier === 1);
    if (tier1Models.length === 0) return [];
    const installed = await listInstalledTier1Models();
    const installedById = new Map(installed.map((m) => [m.id, m]));
    const entries: Tier1MarketplaceEntry[] = [];
    for (const model of tier1Models) {
      entries.push({
        model,
        fits: modelFits(model, caps),
        state: await getTier1ModelInstallState(model.id),
        installedBytes: installedById.get(model.id)?.sizeBytes || 0,
        activeDownload: downloadManager.getAsset(model.id),
      });
    }
    return entries;
  } catch {
    return [];
  }
}

/**
 * Fully evict a tier-1 model from this device: WebLLM's weight/config/wasm
 * caches, the readiness marker, the session memo, and the shared chip's
 * status row. Unloads the engine first when this exact model is live (the
 * next turn falls through to the remaining ladder). User-initiated only —
 * nothing in the routing path ever calls this. Never rejects; false means
 * NOTHING WAS DELETED — either the environment has no Cache API at all, or
 * the effective weight store is one this page cannot delete from (it refuses
 * up front rather than reporting a success it did not achieve).
 *
 * SCOPE: the Cache API only. The deleteModelAllInfoInCache call below is left
 * without an appConfig on purpose, so it runs against prebuiltAppConfig,
 * whose cacheBackend is "cache" (verified in @mlc-ai/web-llm@0.2.84) — the
 * same scopes the raw fallback walks. Weights the cross-origin store holds
 * are NOT removed and cannot be: see getTier1WeightBackendState(). A UI that
 * offers a delete button should still read .deletable so it can explain the
 * refusal, but it no longer has to: a false return with the row and the
 * marker intact is the honest outcome either way.
 */
export async function deleteTier1Model(modelId: string): Promise<boolean> {
  if (typeof window === "undefined" || typeof caches === "undefined") return false;
  // The effective store cannot be deleted from here (CrossOriginStorage
  // .delete() is an empty function and deleteTensorCache() early-returns for
  // that cache class, both in @mlc-ai/web-llm@0.2.84). Running the deletes
  // below would succeed against Cache API scopes that hold none of this
  // model's bytes and then clear the marker - reporting a delete that freed
  // nothing and losing the record that the weights exist at all. Refuse
  // instead, and leave every marker alone: false here means "not deleted",
  // which is exactly what happened.
  if (!getTier1WeightBackendState().deletable) return false;
  // Live engine on this model → release the GPU memory before the files go.
  if (tier1Engine.instance && tier1Engine.modelId === modelId) {
    try {
      await tier1Engine.instance.unload();
    } catch {
      // Best-effort — the cache deletes below still proceed.
    }
    tier1Engine.modelId = null; // instance stays for a later reload of another model
  }
  let deleted = false;
  try {
    // Preferred: the library's own eviction — it also clears the config and
    // wasm scopes that raw id-matching can't reach (wasm URLs carry the lib
    // name, not the model id).
    const webllm = await loadWebLlm();
    await webllm.deleteModelAllInfoInCache(modelId);
    deleted = true;
  } catch {
    // Fallback: raw Cache API deletes on the scopes whose URLs embed the id.
    try {
      for (const scope of [WEBLLM_MODEL_CACHE_SCOPE, "webllm/config"]) {
        if (!(await caches.has(scope))) continue;
        const cache = await caches.open(scope);
        const needle = `/${modelId}/`;
        for (const req of await cache.keys()) {
          if (req.url.includes(needle)) await cache.delete(req);
        }
      }
      deleted = true;
    } catch {
      // Cache API failed mid-delete — markers are still cleared below so the
      // router re-verifies instead of trusting a half-deleted install.
    }
  }
  safeLocalRemove(TIER1_CACHED_KEY_PREFIX + modelId);
  // 2026-09-19: the install stamp and load-failure history describe files that
  // no longer exist.
  safeLocalRemove(TIER1_STAMP_KEY_PREFIX + modelId);
  safeLocalRemove(TIER1_LOAD_FAILURE_KEY_PREFIX + modelId);
  safeLocalRemove(TIER1_WEIGHT_STORE_KEY_PREFIX + modelId);
  weightPresence.delete(modelId);
  // Clear the chip's status row + any downloadManager-side registry entry so
  // the model doesn't linger as "Ready" in the shared download list.
  void downloadManager.deleteAsset(modelId);
  return deleted;
}
