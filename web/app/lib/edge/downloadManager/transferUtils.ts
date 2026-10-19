// Pure helpers, error types and bookkeeping types of the download manager
// (extracted verbatim from downloadManager.ts; worker-safe, no app imports).

import { EDGE_BRANDING } from "../branding";
import type { ModelManifest } from "../capabilities";
import type { ManifestAssetEntry } from "../downloadManager";

export function safeLocalGet(key: string): string | null {
  try {
    if (typeof localStorage === "undefined") return null; // workers have no localStorage
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

/** Hex SHA-256 via Web Crypto. null = environment can't hash (no SubtleCrypto
 * — insecure dev contexts): verification FAILS OPEN there, matching the
 * edge-wide "failures fall through silently, never degrade" contract, while a
 * computed mismatch always fails closed. */
export async function sha256Hex(data: Uint8Array): Promise<string | null> {
  try {
    if (typeof crypto === "undefined" || !crypto.subtle) return null;
    const digest = await crypto.subtle.digest("SHA-256", data as BufferSource);
    return Array.from(new Uint8Array(digest))
      .map((b) => b.toString(16).padStart(2, "0"))
      .join("");
  } catch {
    return null;
  }
}

/** Finding #14 lint-style safety net: warns when a manifest entry has a
 * manager-downloadable `url` but publishes NEITHER a whole-asset `sha256` nor
 * per-shard `shardSha256` — meaning the transfer engine in this module would
 * install those bytes with no integrity verification at all. Every entry
 * shipping today has `url: null` (WebLLM's own fetcher owns those, bypassing
 * this path entirely — see isManagerDownloadable), so this never fires yet;
 * it exists to catch the day a manager-downloaded asset ships unverified.
 * Warn-only (console.warn) — never throws, never blocks the manifest load. */
export function warnIfUnverifiedManifestEntry(entry: ManifestAssetEntry): void {
  const hasUrl = typeof entry.url === "string" && entry.url.length > 0;
  if (!hasUrl) return;
  const hasWholeHash = typeof entry.sha256 === "string" && entry.sha256.length > 0;
  const hasShardHash = Array.isArray(entry.shardSha256) && entry.shardSha256.length > 0;
  if (!hasWholeHash && !hasShardHash) {
    console.warn(
      `[edge:downloadManager] manifest entry "${entry.id}" has a url but no sha256/shardSha256 — ` +
        "it would be downloaded with no integrity verification."
    );
  }
}

/** Callable at manifest-load time: runs warnIfUnverifiedManifestEntry over
 * every model in a resolved manifest. No-op for null/malformed input. */
export function validateManifestIntegrity(manifest: ModelManifest | null): void {
  if (!manifest || !Array.isArray(manifest.models)) return;
  for (const entry of manifest.models as ManifestAssetEntry[]) {
    warnIfUnverifiedManifestEntry(entry);
  }
}

/** Canonical manifest version is a STRING. The server is being aligned in
 * parallel, but normalize defensively at every comparison/storage point so an
 * integer payload can never break comparisons or localStorage round-trips. */
export function normalizeVersion(value: unknown): string {
  return value == null ? "" : String(value);
}

/** Minimal tolerant coercion of a fetched/cached manifest payload to the
 * canonical shape (version → string, per-model version → string). NOTE: a
 * shared `normalizeManifest()` is being added to capabilities.ts by a sibling
 * change — when it lands, route payloads through it instead of this local
 * fallback. Checked at time of writing: not exported yet. */
export function coerceManifest(raw: unknown): ModelManifest | null {
  if (!raw || typeof raw !== "object") return null;
  const body = raw as ModelManifest;
  if (!Array.isArray(body.models)) return null;
  return {
    ...body,
    version: normalizeVersion(body.version) || "0",
    models: (body.models as ManifestAssetEntry[]).map((m) => ({
      ...m,
      version: m.version != null ? normalizeVersion(m.version) : undefined,
    })),
  };
}

/** Pull exactly `count` bytes off the front of a chunk list (in place). */
export function takeBytes(chunks: Uint8Array[], count: number): Uint8Array {
  const out = new Uint8Array(count);
  let copied = 0;
  while (copied < count && chunks.length > 0) {
    const head = chunks[0];
    const need = count - copied;
    if (head.byteLength <= need) {
      out.set(head, copied);
      copied += head.byteLength;
      chunks.shift();
    } else {
      out.set(head.subarray(0, need), copied);
      chunks[0] = head.subarray(need);
      copied += need;
    }
  }
  return out;
}

export interface InstalledAssetRecord {
  version: string;
  cacheKey: string;
  totalBytes: number;
  installedAt: number;
}

export type InstalledRegistry = Record<string, InstalledAssetRecord>;

export interface TransferControl {
  abort: AbortController;
  intent: "run" | "pause" | "cancel";
  /** True only while startTransfer is parked on the concurrency gate. */
  waitingForSlot?: boolean;
  /** resume() arrived while a paused/cancelled transfer was still unwinding:
   * start a fresh one as soon as this one has let go of its slot. */
  restartWhenSettled?: boolean;
}

export type ManifestFetchResult =
  | { kind: "manifest"; manifest: ModelManifest }
  | { kind: "not-modified" }
  | { kind: "missing" } // 404 — source doesn't exist, feature-detect next one
  | { kind: "error" };

export class VerificationError extends Error {
  constructor() {
    super(EDGE_BRANDING.verificationFailedError);
    this.name = "VerificationError";
  }
}

/** The stream ended cleanly but short of the size the server declared. A
 * connection closed by a middlebox mid-body surfaces exactly like this — no
 * exception, just `done` early — and before 2026-09-19 an asset with no
 * published hash was then registered as INSTALLED with a truncated file. */
export class IncompleteTransferError extends Error {
  constructor(received: number, expected: number) {
    super(`transfer ended at ${received} of ${expected} bytes`);
    this.name = "IncompleteTransferError";
  }
}

/** A non-2xx answer from the asset host, kept typed so the failure classifier
 * can tell "blocked / host down" (retry later) from "gone" (404). */
export class TransferHttpError extends Error {
  status: number;
  constructor(status: number) {
    super(`asset host answered ${status}`);
    this.name = "TransferHttpError";
    this.status = status;
  }
}

/** Total asset size as the SERVER declares it for this response, or 0 when it
 * doesn't say. 206 → Content-Range "bytes a-b/total"; 200 → Content-Length.
 * The manifest's sizeBytes is deliberately NOT used for the completeness
 * check: it is display metadata and may be an estimate. */
export function declaredTotalBytes(
  res: { status: number; headers: { get(name: string): string | null } },
  startByte: number
): number {
  try {
    if (res.status === 206) {
      const m = /\/(\d+)\s*$/.exec(res.headers.get("content-range") || "");
      if (m) return Number(m[1]);
      const partLen = Number(res.headers.get("content-length"));
      return Number.isFinite(partLen) && partLen > 0 ? startByte + partLen : 0;
    }
    // A Content-Encoding'd body's Content-Length is the COMPRESSED size while
    // the reader yields decompressed bytes — not comparable, so don't try.
    if (res.headers.get("content-encoding")) return 0;
    const len = Number(res.headers.get("content-length"));
    return Number.isFinite(len) && len > 0 ? len : 0;
  } catch {
    return 0;
  }
}
