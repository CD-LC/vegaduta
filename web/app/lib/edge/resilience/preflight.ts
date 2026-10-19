// On-device download PREFLIGHT — the pure decision half (2026-09-19).
//
// WHY. The old pre-download gate was two checks inside startTier1Download:
// "WebGPU?" and "quota - usage >= size * 1.2?". Everything else was discovered
// the hard way, mid-transfer: a blocked model host surfaced as a generic
// "interrupted" after the person had already opted in; a private window with
// no Cache API failed at the first write; a phone on data-saver was never told
// it was about to pull gigabytes over a metered link. Failing at 90% of a
// multi-GB download is the worst possible moment to find out.
//
// This file decides; preflightProbe.ts gathers. The split is deliberate: every
// branch below is unit-testable with plain objects (no navigator, no fetch),
// and the probe stays a thin, feature-detecting shell.
//
// Blockers stop the download before a byte moves. Warnings never stop it —
// they are shown next to the button so the click stays an informed opt-in
// (E-0b rule 2). An UNKNOWN input is never a blocker: browsers that hide
// storage.estimate() or the connection type would otherwise never be able to
// download at all (same reasoning as downloadManager.hasStorageHeadroomFor).
//
// PURE MODULE: no DOM, no network, no app imports.

import type { EdgeDownloadFailureReason } from "./failures";
import { formatBytesPlain } from "./failures";

/** Same 1.2x headroom factor downloadManager.hasStorageHeadroomFor has always
 * used: the Cache API stores response metadata alongside the bytes, and a
 * model that fits with zero slack makes the NEXT write on this origin fail. */
export const STORAGE_SAFETY_FACTOR = 1.2;

/** effectiveType values where a multi-hundred-MB download is unrealistic. */
const SLOW_EFFECTIVE_TYPES = new Set(["slow-2g", "2g"]);

export interface PreflightInput {
  /** Bytes the model needs. 0/undefined = unknown (storage check is skipped). */
  modelBytes: number;
  edgeEnabled: boolean;
  /** Does THIS model need WebGPU (tier 1 / WebLLM does; the wasm tiers do not). */
  requiresWebGpu: boolean;
  webgpuAvailable: boolean;
  wasmAvailable: boolean;
  cacheStorageAvailable: boolean;
  /** Will THIS download write to the Cache API? Default true. False when the
   * engine stores weights elsewhere (tier 1's experimental cross-origin shared
   * store), where a missing Cache API is not a reason to refuse. */
  requiresCacheStorage?: boolean;
  /** navigator.onLine; undefined = the browser doesn't say. */
  online?: boolean;
  /** storage.estimate(); null = unsupported / threw. */
  storage: { usageBytes: number; quotaBytes: number } | null;
  /** Result of navigator.storage.persisted()/persist(); undefined = unsupported. */
  persisted?: boolean;
  connection: { saveData: boolean; effectiveType: string | null; metered?: boolean };
  /** Reachability probe of the model host. "unknown" = probe could not tell
   * (it is NOT treated as unreachable — see preflightProbe.ts). */
  host: { name: string; status: "reachable" | "unreachable" | "unknown" };
}

export type PreflightWarningCode = "save-data" | "slow-connection" | "not-persisted" | "storage-unknown" | "tight-storage";

export interface PreflightWarning {
  code: PreflightWarningCode;
  message: string;
}

export interface PreflightBlocker {
  reason: EdgeDownloadFailureReason;
  /** Context the failure copy needs (describeDownloadFailure's ctx). */
  neededBytes?: number;
  freeBytes?: number;
  host?: string;
}

export interface PreflightVerdict {
  ok: boolean;
  /** First blocker is the one to show — ordered most-fundamental first, so a
   * browser with no WebGPU is never told to "free up space". */
  blockers: PreflightBlocker[];
  warnings: PreflightWarning[];
  /** Bytes free for this origin, when the browser says. */
  freeBytes: number | null;
}

/** Free bytes for this origin, or null when the browser doesn't report a quota. */
export function freeStorageBytes(storage: PreflightInput["storage"]): number | null {
  if (!storage || !(storage.quotaBytes > 0)) return null;
  return Math.max(0, storage.quotaBytes - Math.max(0, storage.usageBytes || 0));
}

/** Does `modelBytes` fit, with headroom? null = cannot tell. */
export function fitsInStorage(
  modelBytes: number,
  storage: PreflightInput["storage"],
  safetyFactor = STORAGE_SAFETY_FACTOR
): boolean | null {
  const free = freeStorageBytes(storage);
  if (free === null || !(modelBytes > 0)) return null;
  return free >= modelBytes * safetyFactor;
}

export function evaluatePreflight(input: PreflightInput): PreflightVerdict {
  const blockers: PreflightBlocker[] = [];
  const warnings: PreflightWarning[] = [];
  const free = freeStorageBytes(input.storage);

  // -- Blockers, most fundamental first ------------------------------------
  if (!input.edgeEnabled) blockers.push({ reason: "edge-disabled" });
  if (!input.wasmAvailable) blockers.push({ reason: "no-wasm" });
  if (input.requiresWebGpu && !input.webgpuAvailable) blockers.push({ reason: "no-webgpu" });
  if (input.requiresCacheStorage !== false && !input.cacheStorageAvailable) blockers.push({ reason: "no-cache-storage" });

  const fits = fitsInStorage(input.modelBytes, input.storage);
  if (fits === false) {
    blockers.push({
      reason: "quota-too-small",
      neededBytes: input.modelBytes,
      freeBytes: free ?? undefined,
    });
  }

  // Connectivity last: it is the one blocker that fixes itself.
  if (input.online === false) {
    blockers.push({ reason: "offline" });
  } else if (input.host.status === "unreachable") {
    blockers.push({ reason: "host-unreachable", host: input.host.name });
  }

  // -- Warnings (never block) ----------------------------------------------
  const size = input.modelBytes > 0 ? formatBytesPlain(input.modelBytes) : "a large";
  if (input.connection.saveData || input.connection.metered) {
    warnings.push({
      code: "save-data",
      message: `Data saver is on — this is ${input.modelBytes > 0 ? `a ${size}` : size} download.`,
    });
  }
  if (input.connection.effectiveType && SLOW_EFFECTIVE_TYPES.has(input.connection.effectiveType)) {
    warnings.push({
      code: "slow-connection",
      message: "This connection looks slow — the download may take a long time.",
    });
  }
  if (fits === null && input.modelBytes > 0) {
    warnings.push({
      code: "storage-unknown",
      message: "This browser doesn't report free space, so it couldn't be checked first.",
    });
  } else if (fits === true && free !== null && free < input.modelBytes * 2) {
    warnings.push({
      code: "tight-storage",
      message: "Free space is tight — the browser may remove the model later to make room.",
    });
  }
  if (input.persisted === false) {
    warnings.push({
      code: "not-persisted",
      message: "The browser may clear downloaded models when the device runs low on space.",
    });
  }

  return { ok: blockers.length === 0, blockers, warnings, freeBytes: free };
}
