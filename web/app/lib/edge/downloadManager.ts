// Download & setup manager (real byte-transfer engine)
// + the model-update channel (client side).
//
// ONE shared owner for acquisition of ALL on-device assets: the
// WebLLM text models, whisper, Kokoro, SmolVLM, the MiniLM embedder and
// the RIFE ONNX all plug in here WITHOUT API changes — no modality
// ships its own ad-hoc downloader.
//
// What is REAL as of Wave 2:
//   - manifest sourcing: GET {API_BASE}/api/edge/manifest (feature-detected —
//     a 404 falls back to NEXT_PUBLIC_EDGE_MODEL_MANIFEST_URL when set, else
//     no manifest = no downloads, exactly like Wave 1), ETag-cached with one
//     cheap conditional GET per session (F-1: no polling, no timers)
//   - the streaming byte-transfer engine: fetch + ReadableStream into Cache
//     API shards, HTTP Range resume at the last fully-cached shard, real
//     pause / resume / cancel via AbortController
//   - navigator.storage.estimate() headroom check before any transfer starts
//     (E-0b rule 5: refuse politely when quota is short)
//   - SHA-256 verification via Web Crypto when the manifest entry carries
//     hashes (per-shard `shardSha256` verified on arrival — the scalable path
//     for GB-class models — and/or a whole-asset `sha256`)
//   - F-1 double-buffer rule: each version downloads under its own cache key;
//     the OLD version's shards are evicted ONLY after the new one fully
//     verifies. A failed update is invisible — the previous version keeps
//     serving.
//   - F-1 update channel: once per session (flag on + opted in) the fetched
//     manifest version is compared to the locally-adopted one; a change with
//     installed assets flips an "update available" state the chip renders.
//     NOTHING auto-downloads — applyUpdate() runs only on a user click.
// Still future (unchanged API when it lands):
//   - moving the transfer loop into a dedicated Web Worker + Background Fetch
//     progressive enhancement (E-0b rule 1's strict form). The loop below is
//     already DOM-free and awaits in small chunks, so the move is mechanical.
//
// Worker-readiness: this module touches NO DOM. It uses only fetch, the Cache
// API, Web Crypto, navigator.storage and (guarded, optional) localStorage —
// all of which except localStorage exist in workers; localStorage use
// degrades silently.

import { edgeEnabled, getEdgeOptIn, type ModelManifest } from "./capabilities";
import {
  MANIFEST_API_URL,
  MANIFEST_FALLBACK_URL,
  MANIFEST_KNOWN_VERSION_KEY,
  INSTALLED_REGISTRY_KEY,
  PROGRESS_EMIT_INTERVAL_MS,
  CACHE_KEY_SEP,
} from "./downloadManager/constants";
import * as shardCache from "./downloadManager/shardCache";
import { cachedBytesForKey } from "./downloadManager/shardCache";
import type {
  AssetState,
  AssetStatus,
  DownloadManagerEvent,
  DownloadManagerListener,
  EnqueueRequest,
} from "./downloadManager/types";
export type {
  AssetState,
  AssetStatus,
  DownloadManagerEvent,
  DownloadManagerListener,
  EnqueueRequest,
  ManifestAssetEntry,
} from "./downloadManager/types";
import {
  safeLocalGet,
  safeLocalSet,
  safeLocalRemove,
  validateManifestIntegrity,
  normalizeVersion,
} from "./downloadManager/transferUtils";
export { declaredTotalBytes } from "./downloadManager/transferUtils";
import { fetchManifestFrom, readCachedManifest } from "./downloadManager/manifestSource";
import { readRegistry, staleActionableEntries, writeRegistry } from "./downloadManager/registry";
import { TransferEngine, type AssetPatch } from "./downloadManager/transferEngine";

// Collaborators under ./downloadManager/: manifestSource (ETag-cached manifest
// GETs), registry (installed versions + update staleness), transferEngine (the
// byte-transfer loop), shardCache (Cache API / quota) and transferUtils.

// ---------------------------------------------------------------------------
// The manager
// ---------------------------------------------------------------------------

class EdgeDownloadManager {
  private assets = new Map<string, AssetStatus>();
  private listeners = new Set<DownloadManagerListener>();
  private manifest: ModelManifest | null = null;
  private manifestFetched = false;
  private manifestPromise: Promise<ModelManifest | null> | null = null;
  private requests = new Map<string, EnqueueRequest>();
  private lastProgressEmit = new Map<string, number>();
  private updateChecked = false;
  private updateAvailable = false;
  /** Retry hooks for external-engine assets — see registerExternalRetry. */
  private externalRetry = new Map<string, () => void | Promise<unknown>>();
  /** The byte-transfer engine; it owns the in-flight transfer records. */
  private engine = new TransferEngine({
    getRequest: (id) => this.requests.get(id),
    getAsset: (id) => this.assets.get(id),
    loadManifest: async () => {
      if (!this.manifestFetched) await this.fetchManifest();
      return this.manifest;
    },
    patchAsset: (id, patch) => this.patchAsset(id, patch),
    transition: (id, decide) => this.transition(id, decide),
    hasStorageHeadroomFor: (bytes) => this.hasStorageHeadroomFor(bytes),
    countContiguousShards: (cacheKey) => this.countContiguousShards(cacheKey),
    recomputeUpdateState: () => this.recomputeUpdateState(),
  });

  // -- Subscription -------------------------------------------------------

  subscribe(listener: DownloadManagerListener): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  private emit(event: DownloadManagerEvent): void {
    for (const listener of this.listeners) {
      try {
        listener(event);
      } catch {
        // A broken listener must never take down the download pipeline.
      }
    }
  }

  listAssets(): AssetStatus[] {
    return Array.from(this.assets.values());
  }

  getAsset(id: string): AssetStatus | null {
    return this.assets.get(id) || null;
  }

  /**
   * Every asset THIS manager has fully verified and installed (its own
   * url-hosted transfers — WebLLM-fetched models live in WebLLM's cache and
   * are listed by tier1.ts listInstalledTier1Models instead), with MEASURED
   * Cache API sizes rather than registry estimates. Never rejects.
   */
  async listInstalledAssets(): Promise<Array<{ id: string; version: string; sizeBytes: number; installedAt: number }>> {
    try {
      const registry = readRegistry();
      const out: Array<{ id: string; version: string; sizeBytes: number; installedAt: number }> = [];
      for (const [id, record] of Object.entries(registry)) {
        const measured = await cachedBytesForKey(record.cacheKey);
        out.push({
          id,
          version: normalizeVersion(record.version),
          sizeBytes: measured || record.totalBytes,
          installedAt: record.installedAt,
        });
      }
      return out;
    } catch {
      return [];
    }
  }

  // -- Manifest (F-1: one conditional GET per session, ETag-cached) -------

  /**
   * Resolve the model manifest. Source order (feature-detected):
   *   1. GET {API_BASE}/api/edge/manifest — a 404 (route not shipped) or any
   *      failure falls through to
   *   2. NEXT_PUBLIC_EDGE_MODEL_MANIFEST_URL, when set
   *   3. the localStorage-cached copy, else null (= no downloads, Wave-1
   *      behavior).
   * ETag caching: If-None-Match is sent when the stored ETag came from the
   * same source URL; a 304 answers from the cached copy. With the edge flag
   * off no network call is ever made. Never rejects.
   */
  async fetchManifest(force = false): Promise<ModelManifest | null> {
    if (this.manifestFetched && !force) return this.manifest;
    if (this.manifestPromise !== null && !force) return this.manifestPromise;
    const promise = this.fetchManifestInner(force).finally(() => {
      this.manifestPromise = null;
    });
    this.manifestPromise = promise;
    return promise;
  }

  private async fetchManifestInner(force: boolean): Promise<ModelManifest | null> {
    // Inertness guard: with the flag off (or no fetch — SSR), never touch the
    // network. Cached copy or null, exactly like Wave 1's no-URL path.
    if (!edgeEnabled() || typeof fetch === "undefined") {
      this.manifest = readCachedManifest();
      this.manifestFetched = true;
      validateManifestIntegrity(this.manifest);
      this.emit({ type: "manifest", manifest: this.manifest });
      return this.manifest;
    }
    const sources = [MANIFEST_API_URL];
    if (MANIFEST_FALLBACK_URL) sources.push(MANIFEST_FALLBACK_URL);
    let resolved: ModelManifest | null = null;
    for (const source of sources) {
      const result = await fetchManifestFrom(source, force);
      if (result.kind === "manifest") {
        resolved = result.manifest;
        break;
      }
      if (result.kind === "not-modified") {
        resolved = readCachedManifest();
        break;
      }
      // "missing" (404 — feature-detect) and "error" both fall through to the
      // next source. Errors never surface: edge degrades silently.
    }
    this.manifest = resolved ?? readCachedManifest();
    this.manifestFetched = true;
    validateManifestIntegrity(this.manifest);
    this.emit({ type: "manifest", manifest: this.manifest });
    this.maybeCheckForUpdates();
    return this.manifest;
  }

  getManifest(): ModelManifest | null {
    return this.manifest;
  }

  // -- F-1 update channel --------------------------------------------------

  /**
   * Once per session, flag on + opted in: compare the fetched manifest
   * version with the locally-adopted one. A change while verified installs
   * exist AND at least one of them is actionable (manager-downloadable and
   * stale) → "update available" (rendered by the chip; nothing downloads).
   * A change with nothing installed — or where nothing this manager can act
   * on is stale — is adopted silently: no dead "Update now" button.
   */
  private maybeCheckForUpdates(): void {
    if (this.updateChecked || !this.manifest) return;
    this.updateChecked = true;
    if (!edgeEnabled() || !getEdgeOptIn()) return;
    const manifestVersion = normalizeVersion(this.manifest.version);
    const known = safeLocalGet(MANIFEST_KNOWN_VERSION_KEY);
    const registry = readRegistry();
    const hasInstalls = Object.keys(registry).length > 0;
    if (!known || !hasInstalls) {
      safeLocalSet(MANIFEST_KNOWN_VERSION_KEY, manifestVersion);
      return;
    }
    if (normalizeVersion(known) === manifestVersion) return;
    if (staleActionableEntries(this.manifest).length > 0) {
      this.updateAvailable = true;
      this.emit({ type: "update", available: true, manifestVersion });
    } else {
      // Version bumped but nothing this manager can act on changed — adopt
      // quietly (external-engine assets refresh through their own installers).
      safeLocalSet(MANIFEST_KNOWN_VERSION_KEY, manifestVersion);
    }
  }

  getUpdateState(): { available: boolean; manifestVersion: string | null } {
    return {
      available: this.updateAvailable,
      manifestVersion: this.manifest ? normalizeVersion(this.manifest.version) : null,
    };
  }

  /**
   * User-initiated (F-1: never automatic): queue downloads for every
   * installed asset whose manifest target version differs AND that this
   * manager can download (non-null url). Null-url assets are skipped
   * entirely — they belong to external engines (WebLLM et al. via
   * reportExternalProgress) and enqueueing them here would only create
   * permanently-"queued" rows that never clear. Each queued download follows
   * the double-buffer rule — the running version keeps serving until the new
   * one verifies. Assets the user never installed are NOT downloaded.
   */
  async applyUpdate(): Promise<void> {
    const manifest = await this.fetchManifest();
    if (!manifest) return;
    const actionable = staleActionableEntries(manifest);
    for (const model of actionable) {
      this.enqueue({
        id: model.id,
        displayName: model.displayName,
        totalBytes: model.sizeBytes,
        url: model.url,
      });
    }
    if (actionable.length === 0) {
      // Nothing this manager can move — adopt the new version and clear the
      // state so the chip never shows a button that does nothing.
      const manifestVersion = normalizeVersion(manifest.version);
      safeLocalSet(MANIFEST_KNOWN_VERSION_KEY, manifestVersion);
      this.updateAvailable = false;
      this.emit({ type: "update", available: false, manifestVersion });
    }
  }

  /** After an install completes: when no actionable (manager-downloadable)
   * installed asset remains stale, the manifest version is adopted and the
   * update state clears. */
  private recomputeUpdateState(): void {
    if (!this.manifest) return;
    const manifestVersion = normalizeVersion(this.manifest.version);
    if (staleActionableEntries(this.manifest).length === 0) {
      safeLocalSet(MANIFEST_KNOWN_VERSION_KEY, manifestVersion);
      if (this.updateAvailable) {
        this.updateAvailable = false;
        this.emit({ type: "update", available: false, manifestVersion });
      }
    }
  }

  // -- Installed registry (verified versions only - see ./downloadManager/registry) --

  /** The fully-verified installed version of an asset, or null. Always a
   * normalized string (defensive against pre-normalization registry writes). */
  installedVersion(assetId: string): string | null {
    const record = readRegistry()[assetId];
    return record ? normalizeVersion(record.version) : null;
  }

  // -- Queue / lifecycle ---------------------------------------------------

  /**
   * Queue an asset and start the transfer engine on it. With no URL (neither
   * the request's nor a manifest entry's) the entry stays queued and no bytes
   * move — the Wave-1 behavior, kept so callers never need feature checks.
   */
  enqueue(request: EnqueueRequest): AssetStatus {
    const existing = this.assets.get(request.id);
    if (
      existing &&
      (existing.state === "ready" || existing.state === "downloading" || existing.state === "verifying")
    ) {
      return existing;
    }
    this.requests.set(request.id, request);
    const status: AssetStatus = {
      id: request.id,
      displayName: request.displayName,
      state: "queued",
      receivedBytes: existing?.receivedBytes || 0,
      totalBytes: request.totalBytes,
      error: null,
      failureReason: null,
      // Ownership of the bytes doesn't change because a retry re-queued the row.
      external: existing?.external,
      updatedAt: Date.now(),
    };
    this.assets.set(request.id, status);
    this.emit({ type: "asset", asset: status });
    void this.engine.startTransfer(request.id);
    return status;
  }

  /** Pause a download in flight: the in-flight fetch aborts after the last
   * fully-cached shard; resume restarts there via a Range request. */
  pause(id: string): void {
    // 2026-09-19: external engines cannot be paused from here. Flipping the
    // row to "paused" while WebLLM kept downloading was a lie that the next
    // progress event silently undid.
    if (this.assets.get(id)?.external) return;
    const control = this.engine.control(id);
    if (control) {
      control.intent = "pause";
      control.restartWhenSettled = false;
      control.abort.abort();
    }
    this.transition(id, (a) => (a.state === "downloading" || a.state === "queued" ? "paused" : null));
  }

  /** Resume a paused or failed download at the last cached shard.
   *
   * 2026-09-19 bug fix: for an EXTERNAL asset (every model shipping today —
   * WebLLM text models, whisper, Kokoro, SmolVLM) this used to flip the row
   * to "queued" and call startTransfer, which returns immediately because
   * there is no url. Result: the chip's and the marketplace card's "Retry"
   * button turned a legible error into a permanent "Queued" spinner and
   * downloaded nothing. External assets now retry through the hook their
   * owning engine registered; with no hook the error stays on screen, which
   * is at least true. */
  resume(id: string): void {
    if (this.assets.get(id)?.external) {
      const retry = this.externalRetry.get(id);
      if (!retry) return;
      try {
        void Promise.resolve(retry()).catch(() => {
          // The owning engine reports its own failure through
          // reportExternalProgress; nothing to add here.
        });
      } catch {
        // Same.
      }
      return;
    }
    // A transfer record can still exist for this id: startTransfer registers
    // it BEFORE waiting for a concurrency slot, and a paused/cancelled
    // transfer keeps it until its abort has unwound. startTransfer refuses to
    // run twice for one id, so without this the resume was dropped and the row
    // fell back to "paused" — after the person had explicitly resumed it.
    const control = this.engine.control(id);
    if (control && control.intent === "cancel") return; // resume never revived a cancelled row
    if (control && control.intent === "pause") {
      if (control.waitingForSlot) {
        // Still parked on the gate: simply un-pause it in place.
        control.intent = "run";
        control.abort = new AbortController();
      } else {
        control.restartWhenSettled = true;
      }
      this.transition(id, (a) => (a.state === "paused" ? "queued" : null));
      return;
    }
    this.transition(id, (a) => (a.state === "paused" || a.state === "error" ? "queued" : null));
    void this.engine.startTransfer(id);
  }

  /** An external engine (tier1.ts startTier1Download, …) registers how to
   * re-run ITS download for an asset id, so every surface's Retry button works
   * without knowing which engine owns the row. Re-registering replaces. */
  registerExternalRetry(id: string, retry: () => void | Promise<unknown>): void {
    this.externalRetry.set(id, retry);
  }

  /** Should a Retry/Resume control be shown for this row? */
  canRetry(id: string): boolean {
    const asset = this.assets.get(id);
    if (!asset) return false;
    if (asset.external) return this.externalRetry.has(id);
    return asset.state === "paused" || asset.state === "error";
  }

  /** Cancel a download. Cached shards are kept (cheap resume later); use
   * deleteAsset() for a full evict. */
  cancel(id: string): void {
    if (this.assets.get(id)?.external) return; // see pause()
    const control = this.engine.control(id);
    if (control) {
      control.intent = "cancel";
      control.restartWhenSettled = false;
      control.abort.abort();
    }
    this.transition(id, (a) =>
      a.state === "downloading" || a.state === "queued" || a.state === "paused" ? "cancelled" : null
    );
  }

  private transition(id: string, decide: (a: AssetStatus) => AssetState | null): void {
    const asset = this.assets.get(id);
    if (!asset) return;
    const next = decide(asset);
    if (!next) return;
    const updated: AssetStatus = { ...asset, state: next, updatedAt: Date.now() };
    this.assets.set(id, updated);
    this.emit({ type: "asset", asset: updated });
  }

  /** Internal engine-side status write. State changes always emit; byte-only
   * progress is throttled to PROGRESS_EMIT_INTERVAL_MS. */
  private patchAsset(id: string, patch: AssetPatch): void {
    const existing = this.assets.get(id);
    if (!existing) return;
    const updated: AssetStatus = {
      ...existing,
      ...patch,
      error: patch.error !== undefined ? patch.error : patch.state ? null : existing.error,
      // Same lifetime as `error`: a state change that names no reason clears it.
      failureReason:
        patch.failureReason !== undefined ? patch.failureReason : patch.state ? null : (existing.failureReason ?? null),
      updatedAt: Date.now(),
    };
    this.assets.set(id, updated);
    const stateChanged = updated.state !== existing.state;
    const now = Date.now();
    const last = this.lastProgressEmit.get(id) || 0;
    if (stateChanged || now - last >= PROGRESS_EMIT_INTERVAL_MS) {
      this.lastProgressEmit.set(id, now);
      this.emit({ type: "asset", asset: updated });
    }
  }

  // -- The transfer engine (Wave 2) lives in ./downloadManager/transferEngine --

  /**
   * Progress reporting from a source this manager doesn't drive itself —
   * tier0.ts maps the Chrome Prompt API create() monitor's downloadprogress
   * events here, and tier1.ts's progressReporter maps every WebLLM
   * InitProgressReport here during model warm-up. WebLLM can fire these at a
   * high frequency across a multi-second-to-multi-minute load, so — same as
   * patchAsset's own engine-side writes — byte-only progress is throttled to
   * PROGRESS_EMIT_INTERVAL_MS; a STATE change (crucially the final "ready" or
   * any terminal state) always emits immediately regardless of throttle, so
   * the UI never gets stuck showing a stale in-progress percentage.
   */
  reportExternalProgress(
    id: string,
    displayName: string,
    patch: Partial<Pick<AssetStatus, "state" | "receivedBytes" | "totalBytes" | "error" | "failureReason">>
  ): void {
    const existing = this.assets.get(id);
    const updated: AssetStatus = {
      id,
      displayName,
      state: patch.state ?? existing?.state ?? "downloading",
      receivedBytes: patch.receivedBytes ?? existing?.receivedBytes ?? 0,
      totalBytes: patch.totalBytes ?? existing?.totalBytes ?? 0,
      error: patch.error ?? null,
      // Like `error`: present only on the report that names it.
      failureReason: patch.failureReason ?? null,
      // Whoever reports through here owns the bytes — see AssetStatus.external.
      external: true,
      updatedAt: Date.now(),
    };
    this.assets.set(id, updated);
    const stateChanged = !existing || updated.state !== existing.state;
    const now = Date.now();
    const last = this.lastProgressEmit.get(id) || 0;
    if (stateChanged || now - last >= PROGRESS_EMIT_INTERVAL_MS) {
      this.lastProgressEmit.set(id, now);
      this.emit({ type: "asset", asset: updated });
    }
  }

  // -- Storage (E-0b rule 5) and per-asset shards -------------------------
  // The stateless Cache API / quota work lives in ./downloadManager/shardCache;
  // these are the public entry points consumers already use.

  /** navigator.storage.estimate(), or null where unsupported. Never rejects. */
  estimateStorage(): Promise<{ usageBytes: number; quotaBytes: number } | null> {
    return shardCache.estimateStorage();
  }

  /** Pre-download quota check; unknown estimate answers true. */
  hasStorageHeadroomFor(bytes: number, safetyFactor = 1.2): Promise<boolean> {
    return shardCache.hasStorageHeadroomFor(bytes, safetyFactor);
  }

  /** Ask the browser to protect the cached models from eviction. Best-effort. */
  requestPersistentStorage(): Promise<boolean> {
    return shardCache.requestPersistentStorage();
  }

  /** Store one shard under a versioned cache key; false on any failure. */
  putShard(cacheKey: string, shardIndex: number, data: ArrayBuffer | Blob): Promise<boolean> {
    return shardCache.putShard(cacheKey, shardIndex, data);
  }

  /** Read one shard of the INSTALLED version of a logical asset id: reads
   * through the registry so consumers never see a half-downloaded newer
   * version (F-1 double-buffer). Raw cache keys also resolve, for engine
   * internals. */
  getShard(assetIdOrCacheKey: string, shardIndex: number): Promise<ArrayBuffer | null> {
    return shardCache.getShardByKey(this.resolveCacheKey(assetIdOrCacheKey), shardIndex);
  }

  /** A raw cache key (contains the separator) passes through; a logical id
   * resolves to its installed version's key when one exists. */
  private resolveCacheKey(assetIdOrCacheKey: string): string {
    if (assetIdOrCacheKey.includes(CACHE_KEY_SEP)) return assetIdOrCacheKey;
    return readRegistry()[assetIdOrCacheKey]?.cacheKey ?? assetIdOrCacheKey;
  }

  /** How many contiguous shards are cached under a cache key. */
  countContiguousShards(cacheKey: string): Promise<number> {
    return shardCache.countContiguousShards(cacheKey);
  }

  /** Total cached bytes across EVERY version of one logical asset. */
  cachedBytes(assetId: string): Promise<number> {
    return shardCache.cachedBytes(assetId);
  }

  /** Fully evict a logical asset: every version's shards, the install-registry
   * entry, and its queue entry. */
  async deleteAsset(assetId: string): Promise<void> {
    const control = this.engine.control(assetId);
    if (control) {
      control.intent = "cancel";
      control.abort.abort();
    }
    await shardCache.deleteAssetShards(assetId);
    const registry = readRegistry();
    if (registry[assetId]) {
      delete registry[assetId];
      if (Object.keys(registry).length === 0) safeLocalRemove(INSTALLED_REGISTRY_KEY);
      else writeRegistry(registry);
    }
    const asset = this.assets.get(assetId);
    if (asset) {
      this.assets.delete(assetId);
      this.emit({
        type: "asset",
        asset: { ...asset, state: "cancelled", receivedBytes: 0, updatedAt: Date.now() },
      });
    }
  }
}

/** Module singleton — construction is side-effect free, so importing this on
 * the server (SSR) is harmless. */
export const downloadManager = new EdgeDownloadManager();

/** UI convenience: an AssetStatus as {loaded,total,pct}. pct is 0..100 and
 * honest about unknowns — a totalBytes of 0 answers 0, and the fraction-style
 * statuses (totalBytes 1, see AssetStatus) render correctly since the
 * fraction IS the ratio. */
export function assetProgress(asset: AssetStatus): { loaded: number; total: number; pct: number } {
  const total = asset.totalBytes;
  const loaded = asset.receivedBytes;
  const pct = total > 0 ? Math.max(0, Math.min(100, Math.round((loaded / total) * 100))) : 0;
  return { loaded, total, pct };
}
