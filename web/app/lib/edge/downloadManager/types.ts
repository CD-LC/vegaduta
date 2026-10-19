// Public types of the edge download manager. Split out of ../downloadManager.ts,
// which re-exports every name here, so existing imports keep working.

import type { ManifestModel, ModelManifest } from "../capabilities";
import type { EdgeDownloadFailureReason } from "../resilience/failures";

export type AssetState = "queued" | "downloading" | "paused" | "verifying" | "ready" | "error" | "cancelled";

export interface AssetStatus {
  id: string;
  displayName: string;
  state: AssetState;
  /** Real bytes when known. For unknown-size sources (Chrome's built-in model
   * monitor reports a 0..1 fraction) totalBytes is 1 and receivedBytes is the
   * fraction — consumers should render a percentage, and only render byte
   * counts when totalBytes is large enough to be a real size. */
  receivedBytes: number;
  totalBytes: number;
  error: string | null;
  updatedAt: number;
  /** 2026-09-19: WHY it failed, typed (resilience/failures.ts). `error` stays
   * the one-line human string the compact rows already render; this is what a
   * surface with room for a sentence and a button passes to
   * describeDownloadFailure(). null/absent when not in an error state. */
  failureReason?: EdgeDownloadFailureReason | null;
  /** 2026-09-19: true when an EXTERNAL engine moves this asset's bytes
   * (WebLLM, transformers.js workers, Chrome's built-in model) and this
   * manager only mirrors its progress. pause()/cancel() cannot stop such a
   * transfer, so they refuse rather than lie — UIs hide those buttons on
   * external rows, and show Retry only when canRetry() says a handler exists. */
  external?: boolean;
}

export interface EnqueueRequest {
  id: string;
  displayName: string;
  totalBytes: number;
  /** Where the bytes live (the static model route). When absent, the manifest
   * entry's `url` is used; with neither, the entry stays queued (no engine). */
  url?: string;
}

/** The F-1 manifest publishes more per model than the selection logic in
 * capabilities.ts needs (which owns the base ManifestModel type — read-only
 * to this module). The transfer/verify/update fields are typed here,
 * structurally, so the JSON parse needs no changes anywhere else. */
export interface ManifestAssetEntry extends ManifestModel {
  /** Per-model semver (F-1). Falls back to the manifest-level version. */
  version?: string;
  /** Absolute (or same-origin) URL of the raw asset bytes. */
  url?: string;
  /** Lowercase hex SHA-256 of the WHOLE asset. Best reserved for small assets
   * (RIFE ~25 MB, MiniLM, Kokoro) — whole-asset verification assembles the
   * bytes in memory. GB-class models should publish shardSha256 instead. */
  sha256?: string;
  /** Lowercase hex SHA-256 per shard, index-aligned — verified on arrival. */
  shardSha256?: string[];
  /** Server's shard size in bytes when it publishes shard hashes. */
  shardBytes?: number;
  /** F-1 release channel ("stable" | "beta"). Informational here. */
  channel?: string;
}

export type DownloadManagerEvent =
  | { type: "asset"; asset: AssetStatus }
  | { type: "manifest"; manifest: ModelManifest | null }
  | { type: "update"; available: boolean; manifestVersion: string | null };

export type DownloadManagerListener = (event: DownloadManagerEvent) => void;
