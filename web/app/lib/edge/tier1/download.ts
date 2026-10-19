// Explicit-opt-in tier-1 downloads: preflight, retry/watchdog orchestration and
// the completeness check that makes a finished download count as installed.
// Split out of ../tier1.ts. Nothing here runs from the routing path.

import {
  edgeEnabled,
  selectBestModel,
  type EdgeCapabilities,
  type ManifestModel,
} from "../capabilities";
import { EDGE_BRANDING } from "../branding";
import { downloadManager } from "../downloadManager";
import {
  classifyDownloadFailure,
  describeDownloadFailure,
  isTransientDownloadFailure,
  type EdgeDownloadFailureDescription,
  type EdgeDownloadFailureReason,
} from "../resilience/failures";
import { evaluatePreflight, type PreflightVerdict } from "../resilience/preflight";
import { gatherPreflightInput } from "../resilience/preflightProbe";
import { DownloadWatchdogError, runWithWatchdogs, waitForOnline, withRetry } from "../resilience/retry";
import { acquireEngine } from "./engineAcquire";
import { tier1Engine, teardownIdleTier1Engine, waitForEdgeActivityIdle } from "./engineState";
import { TIER1_LOAD_FAILURE_KEY_PREFIX, TIER1_STAMP_KEY_PREFIX, recordTier1InstallStamp } from "./installStamps";
import { deleteTier1Model } from "./installed";
import { fetchTier1Manifest } from "./manifest";
import { readOnlineFlag } from "./online";
import { formatSize, smallerFittingModelExists } from "./select";
import {
  TIER1_CACHED_KEY_PREFIX,
  checkWeightsOnDevice,
  recordTier1WeightStore,
  resolveTier1CacheBackend,
  safeLocalRemove,
  safeLocalSet,
  weightPresence,
} from "./weightStore";

/** Honest pre-download details copy (E-0b rule 2): exact size + source,
 * shown by the opt-in surface BEFORE the download starts. Model identity is
 * details-panel copy by design (brand-first rule — brand leads, model names
 * stay secondary). */
export function tier1DownloadDetails(model: ManifestModel): string {
  // detailName is where raw model identity is allowed to appear (details
  // panel only — brand-first rule).
  const identity = model.detailName ? ` ${model.detailName}` : "";
  // Same honesty rule applied to the experimental shared store: when it is
  // the backend that will actually run, say so before the click. Both claims
  // are read off @mlc-ai/web-llm@0.2.84 — ArtifactCrossOriginStorageCache
  // serves a shard the extension already holds and fetches the rest from the
  // same URL, and its lookups fetch the Hugging Face `/raw/` twin of that URL
  // to get the content hash — so neither is a size or speed promise.
  const shared =
    resolveTier1CacheBackend() === "cross-origin"
      ? ` Shared model storage is on: any part the Chrome Cross-Origin Storage extension already ` +
        `holds is reused instead of downloaded, the rest comes from the same CDN, and looking a ` +
        `part up asks that CDN for its checksum. Parts stored this way cannot be deleted from here, ` +
        `and turning shared model storage off later means downloading this model again - the files stay ` +
        `on the device but nothing here can reach them.`
      : "";
  return (
    `${model.displayName} — ${formatSize(model.sizeBytes)}, one-time download from the ` +
    `MLC model library CDN (mlc.ai / Hugging Face). Cached on this device and reused ` +
    `offline. Sized for common tasks — anything bigger falls back to hosted automatically.` +
    shared +
    identity
  );
}

export type Tier1DownloadResult =
  | { ok: true; model: ManifestModel }
  | {
      ok: false;
      code: "unsupported" | "no-model" | "no-storage" | "failed";
      detail: string;
      /** 2026-09-19: the typed WHY (resilience/failures.ts) and its ready-made
       * copy + single next action. `code`/`detail` are kept unchanged for the
       * callers that predate this (IntelligenceSourceControl, TrySandbox). */
      reason: EdgeDownloadFailureReason;
      failure: EdgeDownloadFailureDescription;
    };

/** The small file every WebLLM model load fetches first. Used as the
 * reachability probe because it is on the same host, behind the same proxy
 * rules, as the multi-GB weights — so "this answered" genuinely predicts
 * "the download can start". (URL shape: see WEBLLM_MODEL_CACHE_SCOPE's note.) */
function tier1ProbeUrl(modelId: string): string {
  return `https://huggingface.co/mlc-ai/${modelId}/resolve/main/mlc-chat-config.json`;
}

/** No liveness signal for this long = we stop WAITING (not the download —
 * WebLLM cannot be aborted). Deliberately LONG, and sized from how WebLLM
 * actually reports: it runs 4 parallel shard fetches and calls the progress
 * callback once per COMPLETED shard, never per byte. Shards are ~32 MB (some
 * exceed 100 MB), so the first event needs roughly 128 MB of transfer. The
 * previous 10-minute window therefore tripped on any link under ~1.7 Mbps
 * while the download was alive and would have finished. At 30 minutes the
 * same arithmetic gives ~0.6 Mbps. Not measured on real slow links.
 * A second liveness signal backs it up: see startStorageGrowthLiveness. */
const TIER1_DOWNLOAD_STALL_MS = 30 * 60 * 1000;
/** How often origin storage usage is sampled as a byte-level liveness signal. */
const TIER1_STORAGE_LIVENESS_POLL_MS = 30 * 1000;
/** Hard ceiling on how long this function WAITS. Not a cancel — see below. */
const TIER1_DOWNLOAD_OVERALL_MS = 3 * 60 * 60 * 1000;
/** Tries per click, including the first. A retry re-fetches only the shards
 * that are missing: WebLLM caches each shard as its own Cache API entry. */
const TIER1_DOWNLOAD_MAX_ATTEMPTS = 3;


/**
 * Byte-level liveness for a download whose only progress events are per
 * completed shard. While running, samples navigator.storage.estimate().usage
 * and calls `touch` whenever it has grown since the last sample: bytes landing
 * in this origin's storage mean the transfer is alive even though no shard has
 * finished. Best-effort by nature — a browser may only count a cache entry
 * once it is fully written, and the experimental shared store writes outside
 * this origin entirely — so it can only ever EXTEND the wait, never shorten
 * it; the long stall window above is what holds when this signal is silent.
 * Returns a stop function. Never throws, never rejects.
 */
function startStorageGrowthLiveness(touch: () => void): () => void {
  let stopped = false;
  let lastUsage: number | null = null;
  let timer: ReturnType<typeof setTimeout> | null = null;
  const sample = async (): Promise<void> => {
    if (stopped) return;
    try {
      if (typeof navigator !== "undefined" && navigator.storage?.estimate) {
        const usage = (await navigator.storage.estimate()).usage;
        if (typeof usage === "number" && Number.isFinite(usage)) {
          if (lastUsage !== null && usage > lastUsage && !stopped) touch();
          lastUsage = usage;
        }
      }
    } catch {
      // Unknown is not evidence either way.
    }
    if (!stopped) timer = setTimeout(() => void sample(), TIER1_STORAGE_LIVENESS_POLL_MS);
  };
  void sample();
  return () => {
    stopped = true;
    if (timer) clearTimeout(timer);
  };
}

/**
 * Everything that can be known BEFORE a byte moves: capability, storage
 * headroom, persistence, connection type, and whether the model host answers
 * from this network. Safe to call passively (it never prompts and downloads
 * nothing) so an opt-in surface can show blockers/warnings next to its button.
 * Never rejects.
 */
export async function preflightTier1Download(
  caps: EdgeCapabilities,
  model: ManifestModel
): Promise<PreflightVerdict> {
  try {
    const input = await gatherPreflightInput({
      modelBytes: model.sizeBytes,
      edgeEnabled: edgeEnabled(),
      requiresWebGpu: true,
      webgpuAvailable: !!caps.webgpu.available,
      probeUrl: tier1ProbeUrl(model.id),
      // The experimental shared store keeps weights outside this origin's
      // Cache API, so a window where `caches` rejects can still download.
      requiresCacheStorage: resolveTier1CacheBackend() === "cache",
    });
    return evaluatePreflight(input);
  } catch {
    // The probe is written never to throw; if it somehow does, "don't block"
    // is the safe answer — the download's own failure path is still typed.
    return { ok: true, blockers: [], warnings: [], freeBytes: null };
  }
}

/**
 * The "Download again" action for a model getTier1CacheHealth flags: evict the
 * old files, forget the failure history, download fresh. User-gesture only,
 * same as startTier1Download. Never rejects.
 */
export async function redownloadTier1Model(
  caps: EdgeCapabilities,
  modelId: string
): Promise<Tier1DownloadResult> {
  try {
    await deleteTier1Model(modelId);
  } catch {
    // deleteTier1Model doesn't reject; belt and braces — a failed eviction
    // just means the fresh download overwrites entry by entry.
  }
  safeLocalRemove(TIER1_LOAD_FAILURE_KEY_PREFIX + modelId);
  safeLocalRemove(TIER1_STAMP_KEY_PREFIX + modelId);
  return startTier1Download(caps, modelId);
}

function tier1DownloadFailure(
  code: "unsupported" | "no-model" | "no-storage" | "failed",
  reason: EdgeDownloadFailureReason,
  detail: string,
  ctx: Parameters<typeof describeDownloadFailure>[1] = {}
): Tier1DownloadResult {
  return { ok: false, code, detail, reason, failure: describeDownloadFailure(reason, ctx) };
}

/** Everything that makes a finished download count as installed. Shared by
 * the normal path and the late-completion path (a load that outlived the
 * watchdog and then succeeded anyway). Returns false — and marks nothing —
 * when the cached file set is definitively incomplete. */
async function finalizeTier1Install(model: ManifestModel): Promise<boolean> {
  // Completeness before the marker (2026-09-19). The engine loading is strong
  // evidence, not proof: a Cache API write can fail quietly after the bytes
  // were already handed to the GPU, and the marker would then promise an
  // offline-capable model that re-downloads shards mid-chat. Only a
  // DEFINITIVE false blocks; null (a backend this page cannot inspect, or a
  // transient cache read failure) is accepted, for the reasons spelled out on
  // checkWeightsOnDevice.
  const backend = tier1Engine.cacheBackend || resolveTier1CacheBackend();
  if ((await checkWeightsOnDevice(model.id, backend)) === false) return false;
  safeLocalSet(TIER1_CACHED_KEY_PREFIX + model.id, "1");
  // WHERE the bytes went, recorded next to the marker that says they exist.
  // tier1Engine.cacheBackend is what the load actually ran on (the SW path records
  // "cache" explicitly, the in-page path records the resolved backend), so
  // this is an observation rather than a re-read of the preference, which
  // can move before anyone asks. previewTier1SharedWeightsChange() is the
  // only reason it is kept: without it, a preference flip cannot tell "never
  // downloaded" from "downloaded into the store you are about to leave".
  recordTier1WeightStore(model.id, backend);
  recordTier1InstallStamp(model.id);
  // The engine just loaded these weights — record presence directly so the
  // session's readiness memo doesn't have to re-scan the cache.
  weightPresence.set(model.id, Promise.resolve(true));
  // Ask the browser to protect the weights from cache eviction (rule 5).
  void downloadManager.requestPersistentStorage();
  return true;
}

/**
 * Download + verify a tier-1 model. MUST only be called after the user's
 * explicit opt-in through the chip/banner/picker flow (E-0b rule 2) — this
 * function itself never prompts and never auto-runs. Progress is visible in
 * the shared EdgeSetupChip via downloadManager. Never rejects.
 *
 * 2026-09-19 — fail-proofing. The weights come from Hugging Face, a host this
 * platform does not operate, so every step assumes it can fail for someone:
 *   1. PREFLIGHT before any byte: capability, storage headroom, Cache API,
 *      online, and a HEAD against the model host. A blocker returns a typed
 *      reason immediately instead of a generic failure deep into a transfer.
 *   2. RETRY with backoff on transient network failures (resumable for free).
 *      A drop that leaves navigator.onLine false first waits, bounded, for the
 *      link to return (retry.ts waitForOnline) instead of retrying into it.
 *   3. WATCHDOGS: a stall window and an overall ceiling bound how long this
 *      function WAITS. WebLLM cannot be cancelled, so a load that outlives the
 *      watchdog is left running; if it later succeeds it is finalized and the
 *      row moves to "ready", which is what surfaces showing the "stopped
 *      waiting" message key off to clear it.
 *   4. COMPLETENESS is verified before the "installed" marker is written. An
 *      incomplete set also releases the warm engine, so the next attempt
 *      really re-runs the load and fetches the missing shards.
 *   5. Every failure is typed (result.reason) and carries its own copy and
 *      single next action (result.failure); the asset row gets the same reason.
 *
 * @param modelId a manifest model id; omitted → the auto-selected best fit.
 */
export async function startTier1Download(
  caps: EdgeCapabilities,
  modelId?: string
): Promise<Tier1DownloadResult> {
  if (typeof window === "undefined" || !edgeEnabled()) {
    return tier1DownloadFailure("unsupported", "edge-disabled", "edge disabled");
  }
  if (!caps.webgpu.available) {
    return tier1DownloadFailure("unsupported", "no-webgpu", "WebGPU unavailable");
  }
  const manifest = await fetchTier1Manifest();
  const tier1Models = manifest.models.filter((m) => m.tier === 1);
  let model: ManifestModel | null = null;
  if (modelId) {
    model = tier1Models.find((m) => m.id === modelId) || null;
  } else {
    // EDGE-C call-site wiring: a download-time auto-pick has no turn in hand,
    // so the complexity signal is an explicit empty one (messageLength 0 =>
    // "simple" => smallest fast-tagged model). That IS the intended download
    // default — suggest the lightest useful model, let the person upgrade —
    // and matches what this call already did once speedTier tags existed.
    const selection = selectBestModel(
      { ...manifest, models: tier1Models }, caps, undefined, { messageLength: 0 }
    );
    model = selection.kind === "model" ? selection.model : null;
  }
  if (!model) {
    return tier1DownloadFailure("no-model", "model-unavailable", "no matching model in the manifest");
  }
  const target = model;
  const smallerModelAvailable = smallerFittingModelExists(manifest, caps, target);
  // Every surface's Retry button (chip, marketplace card) goes through
  // downloadManager.resume(), which has no idea WebLLM owns these bytes —
  // this hook is what makes that button do something. See resume()'s comment.
  downloadManager.registerExternalRetry(target.id, () => startTier1Download(caps, target.id));

  const fail = (
    code: "unsupported" | "no-storage" | "failed",
    reason: EdgeDownloadFailureReason,
    detail: string,
    ctx: Parameters<typeof describeDownloadFailure>[1] = {}
  ): Tier1DownloadResult => {
    const result = tier1DownloadFailure(code, reason, detail, { smallerModelAvailable, ...ctx });
    downloadManager.reportExternalProgress(target.id, target.displayName, {
      state: "error",
      // The compact rows keep their established strings for the two cases
      // they already had; everything else gets the new one-line title.
      error:
        reason === "quota-too-small"
          ? EDGE_BRANDING.insufficientStorageError
          : reason === "network-interrupted"
            ? EDGE_BRANDING.interruptedError
            : result.ok === false
              ? result.failure.title
              : null,
      failureReason: reason,
    });
    return result;
  };

  const verdict = await preflightTier1Download(caps, target);
  if (!verdict.ok) {
    const blocker = verdict.blockers[0];
    const code =
      blocker.reason === "quota-too-small"
        ? "no-storage"
        : blocker.reason === "no-webgpu" || blocker.reason === "no-wasm" || blocker.reason === "edge-disabled"
          ? "unsupported"
          : "failed";
    return fail(code, blocker.reason, `preflight: ${blocker.reason}`, {
      neededBytes: blocker.neededBytes,
      freeBytes: blocker.freeBytes,
      host: blocker.host,
    });
  }
  // Ask for persistence BEFORE the transfer, not only after it: the shards of
  // a half-finished download are exactly what an eviction would cost most.
  // The click that got us here is the gesture Firefox's prompt requires.
  void downloadManager.requestPersistentStorage();

  // Enter the shared queue/state machine so the chip shows this download
  // alongside every other on-device asset.
  downloadManager.enqueue({ id: target.id, displayName: target.displayName, totalBytes: target.sizeBytes });
  downloadManager.reportExternalProgress(target.id, target.displayName, { state: "queued" });

  // The engine loaded but the cached file set is incomplete. The warm engine
  // MUST go: acquireEngine returns a warm instance for the same model without
  // fetching anything, so every retry would re-check the same cache and report
  // "incomplete" again within a second — a Finish-download button that moves
  // no bytes. After the unload the next attempt runs CreateMLCEngine, which
  // fetches the shards that are missing from the cache.
  const releaseAfterIncompleteInstall = async (): Promise<void> => {
    try {
      weightPresence.delete(target.id);
      if (tier1Engine.modelId !== target.id) return;
      await waitForEdgeActivityIdle();
      await teardownIdleTier1Engine("incomplete-install");
    } catch {
      // Best-effort: the failure result below is still correct without it.
    }
  };

  let watchdogTouch: (() => void) | null = null;
  const unsubscribe = downloadManager.subscribe((event) => {
    if (event.type !== "asset" || event.asset.id !== target.id) return;
    if (event.asset.state === "downloading" || event.asset.state === "verifying") watchdogTouch?.();
  });
  const stopLiveness = startStorageGrowthLiveness(() => watchdogTouch?.());
  try {
    await withRetry<void, unknown>(
      async () => {
        // viaServiceWorker: false — a brand-new/not-yet-cached model's download
        // keeps the direct in-page path (see AcquireEngineOptions's own doc
        // comment for why).
        const load = acquireEngine(target, { viaServiceWorker: false });
        try {
          await runWithWatchdogs(
            (touch) => {
              watchdogTouch = touch;
              return load;
            },
            { stallMs: TIER1_DOWNLOAD_STALL_MS, overallMs: TIER1_DOWNLOAD_OVERALL_MS }
          );
        } catch (err) {
          if (err instanceof DownloadWatchdogError) {
            // We stopped WAITING; WebLLM may still be working. If it does
            // finish, count it — otherwise the person would be told "failed"
            // and then find the model ready, or worse, re-download it.
            // acquireEngine has already moved the row to "ready" by the time
            // this runs, which is what lets a surface still showing the
            // "stopped waiting" message clear it. If the file set turns out
            // incomplete, the row must not keep claiming ready.
            load.then(
              (): void => {
                void (async () => {
                  try {
                    // acquireEngine reported "ready" BEFORE the completeness
                    // check and the install marker. Surfaces that gate on the
                    // marker (the setup nudge) need one more row event after
                    // it exists; a same-state report may be throttled away,
                    // so the row honestly passes through "verifying" first.
                    downloadManager.reportExternalProgress(target.id, target.displayName, { state: "verifying" });
                    if (await finalizeTier1Install(target)) {
                      downloadManager.reportExternalProgress(target.id, target.displayName, {
                        state: "ready",
                        receivedBytes: target.sizeBytes,
                        totalBytes: target.sizeBytes,
                      });
                      return;
                    }
                    await releaseAfterIncompleteInstall();
                    fail("failed", "incomplete", "cached weight set is incomplete after a late load");
                  } catch {
                    // Could not tell — claim nothing about the install, but do
                    // not strand the row on "verifying": the engine IS loaded,
                    // which is what "ready" meant before this handler ran.
                    downloadManager.reportExternalProgress(target.id, target.displayName, { state: "ready" });
                  }
                })();
              },
              (): void => {}
            );
          }
          throw err;
        }
      },
      {
        maxAttempts: TIER1_DOWNLOAD_MAX_ATTEMPTS,
        shouldRetry: (err) =>
          !(err instanceof DownloadWatchdogError) &&
          isTransientDownloadFailure(classifyDownloadFailure({ error: err, online: readOnlineFlag() })),
        // A wifi roam / lid close fails the fetch with navigator.onLine false.
        // Wait (bounded) for the link instead of burning the attempts while
        // it is down; if it never returns, the pending error is rethrown and
        // classified "offline" below.
        beforeNextAttempt: () => waitForOnline(),
        onRetry: () => {
          downloadManager.reportExternalProgress(target.id, target.displayName, { state: "downloading" });
        },
      }
    );
    if (!(await finalizeTier1Install(target))) {
      await releaseAfterIncompleteInstall();
      return fail("failed", "incomplete", "cached weight set is incomplete after load");
    }
    return { ok: true, model: target };
  } catch (err) {
    const detail = err instanceof Error ? err.message : String(err);
    const reason = classifyDownloadFailure({
      error: err,
      online: readOnlineFlag(),
      watchdog: err instanceof DownloadWatchdogError ? err.kind : undefined,
    });
    return fail(reason === "quota-too-small" ? "no-storage" : "failed", reason, detail);
  } finally {
    stopLiveness();
    unsubscribe();
  }
}
