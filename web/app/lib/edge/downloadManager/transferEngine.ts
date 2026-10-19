// The Wave-2 byte-transfer engine behind ../downloadManager.ts (split out of it):
// bounded-concurrency transfers with retry/backoff, stall and overall watchdogs,
// Range resume at the last cached shard, verification and the F-1 double-buffer
// install. The manager owns asset state and hands the engine a TransferHost.
import type { ModelManifest } from "../capabilities";
import { EDGE_BRANDING } from "../branding";
// Pure/timer-only resilience modules with no app imports, so the engine stays
// worker-ready like the manager (see its header).
import {
  classifyDownloadFailure,
  downloadFailureOneLine,
  isTransientDownloadFailure,
  type EdgeDownloadFailureReason,
} from "../resilience/failures";
import {
  ConcurrencyGate,
  DownloadWatchdogError,
  backoffDelayMs,
  defaultSleep,
  waitForOnline,
} from "../resilience/retry";
import {
  DEFAULT_SHARD_BYTES,
  MAX_CONCURRENT_TRANSFERS,
  TRANSFER_MAX_ATTEMPTS,
  TRANSFER_OVERALL_MS,
  TRANSFER_STALL_MS,
  makeCacheKey,
} from "./constants";
import { cachedBytesForKey, deleteShards, verifyAndPutShard, verifyWholeAsset } from "./shardCache";
import type { AssetState, AssetStatus, EnqueueRequest, ManifestAssetEntry } from "./types";
import {
  IncompleteTransferError,
  TransferHttpError,
  VerificationError,
  declaredTotalBytes,
  normalizeVersion,
  takeBytes,
  type TransferControl,
} from "./transferUtils";
import { readRegistry, writeRegistry } from "./registry";

/** The status fields the engine writes while it moves bytes. */
export type AssetPatch = Partial<
  Pick<AssetStatus, "state" | "receivedBytes" | "totalBytes" | "error" | "failureReason">
>;

/** What the engine needs from the manager that owns the asset rows. */
export interface TransferHost {
  getRequest(id: string): EnqueueRequest | undefined;
  getAsset(id: string): AssetStatus | undefined;
  /** The manifest, fetched first when this session has not fetched it yet. */
  loadManifest(): Promise<ModelManifest | null>;
  patchAsset(id: string, patch: AssetPatch): void;
  transition(id: string, decide: (a: AssetStatus) => AssetState | null): void;
  hasStorageHeadroomFor(bytes: number): Promise<boolean>;
  countContiguousShards(cacheKey: string): Promise<number>;
  recomputeUpdateState(): void;
}

export class TransferEngine {
  private transfers = new Map<string, TransferControl>();
  private transferGate = new ConcurrencyGate(MAX_CONCURRENT_TRANSFERS);

  constructor(private readonly host: TransferHost) {}

  /** The live transfer record for an asset, if one is registered. */
  control(id: string): TransferControl | undefined {
    return this.transfers.get(id);
  }

  async startTransfer(id: string): Promise<void> {
    if (this.transfers.has(id)) return;
    const request = this.host.getRequest(id);
    if (!request || typeof fetch === "undefined" || typeof caches === "undefined") return;
    // The manifest entry supplies url/version/hashes; make sure it's loaded.
    const manifest = await this.host.loadManifest();
    const asset = this.host.getAsset(id);
    if (!asset || (asset.state !== "queued" && asset.state !== "downloading")) return;

    const entry = (manifest?.models as ManifestAssetEntry[] | undefined)?.find((m) => m.id === id) ?? null;
    const url = request.url || entry?.url || "";
    if (!url) return; // No byte source → stays queued (Wave-1 behavior).

    // Normalized string version at the storage boundary: cache keys and the
    // install registry must round-trip identically to later comparisons.
    const version = normalizeVersion(entry?.version ?? manifest?.version ?? undefined) || "0";
    const cacheKey = makeCacheKey(id, version);
    const totalBytes = request.totalBytes || entry?.sizeBytes || 0;
    const shardBytes = entry?.shardBytes && entry.shardBytes > 0 ? entry.shardBytes : DEFAULT_SHARD_BYTES;

    // Already installed and verified at this exact version → ready, no bytes.
    const preRegistry = readRegistry();
    if (preRegistry[id] && normalizeVersion(preRegistry[id].version) === version) {
      this.host.patchAsset(id, {
        state: "ready",
        receivedBytes: totalBytes || preRegistry[id].totalBytes,
        totalBytes: totalBytes || preRegistry[id].totalBytes,
      });
      return;
    }

    // E-0b rule 5: refuse politely when storage headroom is short.
    if (totalBytes > 0 && !(await this.host.hasStorageHeadroomFor(totalBytes))) {
      this.host.patchAsset(id, {
        state: "error",
        error: EDGE_BRANDING.insufficientStorageError,
        failureReason: "quota-too-small",
      });
      return;
    }

    const control: TransferControl = { abort: new AbortController(), intent: "run" };
    this.transfers.set(id, control);
    // 2026-09-19: bounded concurrency. The row stays "queued" while it waits
    // for a slot; pause()/cancel() during the wait are honoured below.
    control.waitingForSlot = true;
    const release = await this.transferGate.acquire();
    control.waitingForSlot = false;
    /** Which watchdog fired, if one did — read by the failure classifier. */
    let watchdog: "stalled" | "timed-out" | null = null;
    const overallTimer = setTimeout(() => {
      watchdog = "timed-out";
      control.abort.abort();
    }, TRANSFER_OVERALL_MS);
    try {
      if (control.intent !== "run") throw new Error("stopped while queued");
      // 2026-09-19: automatic retry with backoff. A dropped connection used to
      // end the transfer in "error" and wait for a click, although resuming is
      // nearly free (Range from the last cached shard). Only TRANSIENT reasons
      // are retried — a full disk or a checksum mismatch fails the same way
      // every time, and retrying it silently only delays the explanation.
      for (let attempt = 1; ; attempt += 1) {
        try {
          await this.runTransferAttempt(id, url, cacheKey, totalBytes, shardBytes, entry, control, (kind) => {
            watchdog = kind;
          });
          break;
        } catch (err) {
          if (control.intent !== "run" || watchdog) throw err;
          const reason = classifyDownloadFailure({
            error: err,
            online: typeof navigator !== "undefined" ? navigator.onLine : undefined,
            httpStatus: err instanceof TransferHttpError ? err.status : undefined,
          });
          // A stream that ended short is a dropped connection that didn't
          // throw — as transient as one that did.
          const transient = err instanceof IncompleteTransferError || isTransientDownloadFailure(reason);
          if (attempt >= TRANSFER_MAX_ATTEMPTS || !transient) throw err;
          // The aborted controller cannot be reused; pause/cancel still reach
          // the NEW one because they look `control` up by id and abort
          // whatever `control.abort` currently is.
          control.abort = new AbortController();
          await defaultSleep(backoffDelayMs(attempt));
          if (control.intent !== "run") throw err;
          // "offline" is transient (a wifi roam rejects the fetch with
          // navigator.onLine false) but retrying INTO a dead link just burns
          // the attempts: wait, bounded, for the browser to report the link
          // back. Pause/cancel abort the wait; if it never returns, the
          // pending error surfaces as "You're offline".
          if (!(await waitForOnline({ signal: control.abort.signal }))) throw err;
          if (control.intent !== "run") throw err;
        }
      }

      // Verification (whole-asset hash, when published).
      this.host.patchAsset(id, { state: "verifying" });
      const shardCount = await this.host.countContiguousShards(cacheKey);
      if (entry?.sha256) {
        const ok = await verifyWholeAsset(cacheKey, shardCount, entry.sha256);
        if (!ok) throw new VerificationError();
      }

      // F-1 double-buffer: ONLY now — after full verification — does the old
      // version get evicted and the registry point at the new one.
      const registry = readRegistry();
      const previous = registry[id];
      registry[id] = {
        version,
        cacheKey,
        totalBytes: totalBytes || (await cachedBytesForKey(cacheKey)),
        installedAt: Date.now(),
      };
      writeRegistry(registry);
      if (previous && previous.cacheKey !== cacheKey) {
        await deleteShards(previous.cacheKey);
      }
      const finalBytes = totalBytes || registry[id].totalBytes;
      this.host.patchAsset(id, { state: "ready", receivedBytes: finalBytes, totalBytes: finalBytes });
      this.host.recomputeUpdateState();
    } catch (err) {
      if (control.intent === "pause") {
        this.host.patchAsset(id, { state: "paused" });
      } else if (control.intent === "cancel") {
        this.host.patchAsset(id, { state: "cancelled" });
      } else {
        const reason: EdgeDownloadFailureReason =
          err instanceof IncompleteTransferError
            ? "incomplete"
            : classifyDownloadFailure({
                error: err,
                watchdog: watchdog ?? undefined,
                online: typeof navigator !== "undefined" ? navigator.onLine : undefined,
                httpStatus: err instanceof TransferHttpError ? err.status : undefined,
              });
        if (reason === "integrity-failed") {
          // Corrupt download: drop the NEW version's shards (the old install
          // is untouched — F-1's "a failed update is invisible"). Every other
          // failure KEEPS its shards: they were each verified/written whole,
          // the install registry does not point at them, and getShard() reads
          // through the registry — so a partial set can never be loaded as a
          // model, only resumed.
          await deleteShards(cacheKey);
        }
        this.host.patchAsset(id, {
          state: "error",
          error:
            reason === "integrity-failed"
              ? EDGE_BRANDING.verificationFailedError
              : reason === "network-interrupted"
                ? EDGE_BRANDING.interruptedError
                : downloadFailureOneLine(reason),
          failureReason: reason,
        });
      }
    } finally {
      clearTimeout(overallTimer);
      release();
      this.transfers.delete(id);
      // resume() arrived while this paused transfer was still unwinding — see
      // resume(). The catch above has just written "paused"; honour the resume.
      if (control.restartWhenSettled) {
        this.host.transition(id, (a) => (a.state === "paused" ? "queued" : null));
        void this.startTransfer(id);
      }
    }
  }

  /**
   * ONE attempt at moving the missing bytes of an asset into the shard cache.
   * Resumes at the first gap in the contiguous shard run, so calling it again
   * after a failure continues rather than restarts. Throws on any failure;
   * the caller classifies. `onWatchdog` reports a stall before the abort it
   * triggers surfaces as an AbortError.
   */
  private async runTransferAttempt(
    id: string,
    url: string,
    cacheKey: string,
    totalBytes: number,
    shardBytes: number,
    entry: ManifestAssetEntry | null,
    control: TransferControl,
    onWatchdog: (kind: "stalled") => void
  ): Promise<void> {
    // Resume point (E-0b rule 4): the first gap in the contiguous shard run.
    let shardIndex = await this.host.countContiguousShards(cacheKey);
    let startByte = shardIndex * shardBytes;
    if (totalBytes > 0 && startByte > totalBytes) {
      startByte = totalBytes;
    }
    this.host.patchAsset(id, { state: "downloading", receivedBytes: startByte, totalBytes });

    const fullyCached = totalBytes > 0 && startByte >= totalBytes && shardIndex > 0;
    if (fullyCached) return;

    const headers: Record<string, string> = {};
    if (startByte > 0) headers.Range = `bytes=${startByte}-`;
    const signal = control.abort.signal;
    const res = await fetch(url, { headers, signal });
    if (startByte > 0 && res.status === 200) {
      // Server ignored Range — start over from zero.
      await deleteShards(cacheKey);
      shardIndex = 0;
      startByte = 0;
      this.host.patchAsset(id, { receivedBytes: 0 });
    } else if (res.status === 416) {
      // Range exactly at EOF — everything is already cached, verify in caller.
      return;
    } else if (!res.ok && res.status !== 206) {
      throw new TransferHttpError(res.status);
    }
    if (!res.body) throw new Error("response has no readable body");
    const expectedTotal = declaredTotalBytes(res, startByte);
    const reader = res.body.getReader();
    const chunks: Uint8Array[] = [];
    let pendingLen = 0;
    let received = startByte;
    for (;;) {
      // Stall watchdog: a read that yields nothing for TRANSFER_STALL_MS is a
      // dead socket. Abort it (this engine CAN be cancelled, unlike WebLLM's)
      // so the connection is released rather than leaked.
      let stallTimer: ReturnType<typeof setTimeout> | null = null;
      const stalled = new Promise<never>((_, reject) => {
        stallTimer = setTimeout(() => {
          onWatchdog("stalled");
          try {
            control.abort.abort();
          } catch {
            // Best-effort.
          }
          reject(new DownloadWatchdogError("stalled"));
        }, TRANSFER_STALL_MS);
      });
      let step: ReadableStreamReadResult<Uint8Array>;
      try {
        step = await Promise.race([reader.read(), stalled]);
      } finally {
        if (stallTimer) clearTimeout(stallTimer);
      }
      if (step.done) break;
      const value = step.value;
      if (value && value.byteLength > 0) {
        chunks.push(value);
        pendingLen += value.byteLength;
        received += value.byteLength;
      }
      while (pendingLen >= shardBytes) {
        const shard = takeBytes(chunks, shardBytes);
        pendingLen -= shardBytes;
        await verifyAndPutShard(cacheKey, shardIndex, shard, entry);
        shardIndex += 1;
      }
      this.host.patchAsset(id, { receivedBytes: received, totalBytes });
    }
    // Completeness BEFORE the tail is cached: a short stream must not leave a
    // short final shard behind, because the resume point is computed as
    // shardIndex * shardBytes and a truncated shard in the middle of the run
    // would silently corrupt the assembled asset.
    if (expectedTotal > 0 && received < expectedTotal) {
      throw new IncompleteTransferError(received, expectedTotal);
    }
    if (pendingLen > 0) {
      const shard = takeBytes(chunks, pendingLen);
      await verifyAndPutShard(cacheKey, shardIndex, shard, entry);
    }
  }
}
