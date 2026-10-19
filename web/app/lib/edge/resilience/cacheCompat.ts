// Cached-model compatibility: install stamps + load-failure memory (2026-09-19).
//
// WHY. A downloaded model is files in the browser's cache, written for the
// engine version and registry entry that existed on download day. Both move:
// the app ships a new @mlc-ai/web-llm or @huggingface/transformers, or the
// registry (core's /api/edge/manifest) bumps a model. Commit eb9021ac
// (2026-09-17, transformers 4.2.0 -> 4.3.0) had to invalidate a stale ORT
// readiness record BY HAND because nothing recorded what an install was made
// against. For tier 1 the failure mode was worse than a wrong badge: the
// "downloaded" marker says ready, every turn tries the local engine, the load
// throws, the router falls through to hosted, the circuit breaker opens — and
// the person sees on-device "just not working", forever, with no explanation
// and no button, because from the UI's point of view the model IS installed.
//
// The rule encoded here is deliberately conservative:
//   - A version difference ALONE is not a problem. Weights normally survive
//     engine upgrades; demanding a multi-GB re-download on every library bump
//     would punish every user for a bug most of them will never hit.
//   - A load FAILURE plus a version difference is "version-mismatch": explain
//     it and offer re-download.
//   - Repeated load failures with NO known version difference mean the cached
//     files themselves are suspect: also offer re-download ("cache-unloadable").
//   - An install with NO stamp (made before stamps existed) has an UNKNOWN
//     version, not a different one. It is never called a version mismatch; it
//     falls under the repeated-failure rule like any same-version install.
//   - GPU-memory failures do not count when they can be recognised: the
//     library's DeviceLostError, or an error whose text says out-of-memory /
//     device lost. A GPU failure that carries neither is indistinguishable
//     from a bad file and DOES count — which is why one failure alone is never
//     enough to blame the files of a same-version or unstamped install.
//
// PURE MODULE: parsing + decisions only. tier1.ts owns the localStorage I/O.

import type { EdgeDownloadFailureReason } from "./failures";

export interface InstallStamp {
  /** Engine identity at install time, e.g. "web-llm@0.2.85". */
  runtime: string;
  /** Registry (manifest) version at install time. "" = unknown. */
  manifestVersion: string;
  installedAt: number;
}

export interface LoadFailureRecord {
  /** Consecutive failed loads of an installed model (reset on any success). */
  count: number;
  lastAt: number;
  /** Engine identity when the last failure happened. */
  runtime: string;
}

/** Same-version failures needed before the files themselves are blamed. One
 * failure can be a busy GPU or a backgrounded tab; two in a row is a pattern. */
export const SAME_VERSION_FAILURE_THRESHOLD = 2;

export type CacheCompatStatus =
  /** Nothing wrong that we know of. */
  | "ok"
  /** Failed to load AND was installed under a different engine/registry version. */
  | "version-mismatch"
  /** Failed to load repeatedly under the SAME version — the files are suspect. */
  | "corrupt";

export interface CacheCompatVerdict {
  status: CacheCompatStatus;
  /** The failure reason to render (describeDownloadFailure), or null when ok. */
  reason: EdgeDownloadFailureReason | null;
  offerRedownload: boolean;
  /** True only when a stamp exists AND names another engine/registry version.
   * An unstamped install is unknown, which is not "differs". */
  versionDiffers: boolean;
}

export function serializeStamp(stamp: InstallStamp): string {
  return JSON.stringify(stamp);
}

/** Tolerant: anything malformed reads as "no stamp". An install that predates
 * 2026-09-19 has none; evaluateCacheCompat treats that as "version unknown"
 * (never as a mismatch), and tier1.ts backfills a stamp on the next successful
 * load, since a load that works proves compatibility with the current engine. */
export function parseStamp(raw: string | null | undefined): InstallStamp | null {
  if (!raw) return null;
  try {
    const v = JSON.parse(raw) as Partial<InstallStamp> | null;
    if (!v || typeof v !== "object" || typeof v.runtime !== "string" || !v.runtime) return null;
    return {
      runtime: v.runtime,
      manifestVersion: v.manifestVersion == null ? "" : String(v.manifestVersion),
      installedAt: typeof v.installedAt === "number" && Number.isFinite(v.installedAt) ? v.installedAt : 0,
    };
  } catch {
    return null;
  }
}

export function parseLoadFailure(raw: string | null | undefined): LoadFailureRecord | null {
  if (!raw) return null;
  try {
    const v = JSON.parse(raw) as Partial<LoadFailureRecord> | null;
    if (!v || typeof v !== "object") return null;
    const count = typeof v.count === "number" && Number.isFinite(v.count) ? Math.max(0, Math.floor(v.count)) : 0;
    if (count === 0) return null;
    return {
      count,
      lastAt: typeof v.lastAt === "number" && Number.isFinite(v.lastAt) ? v.lastAt : 0,
      runtime: typeof v.runtime === "string" ? v.runtime : "",
    };
  } catch {
    return null;
  }
}

/** Next record after one more failed load. A failure under a NEW runtime
 * restarts the count — failures observed under the old engine say nothing
 * about this one. */
export function nextLoadFailure(previous: LoadFailureRecord | null, runtime: string, now: number): LoadFailureRecord {
  const carry = previous && previous.runtime === runtime ? previous.count : 0;
  return { count: carry + 1, lastAt: now, runtime };
}

/** GPU memory exhaustion / device loss reported as a plain Error. WebLLM's
 * dedicated DeviceLostError is mapped to the "device-lost" code upstream, but
 * a WebGPU out-of-memory can also arrive as a generic Error (or a
 * GPUOutOfMemoryError message) that lands in "engine-failed". Same pattern as
 * failures.ts classifyDownloadFailure's device-memory branch. */
const GPU_MEMORY_DETAIL = /device.{0,12}lost|out of memory|outofmemory|\boom\b/i;

/** Does this failure say anything about the cached FILES? A recognisable GPU
 * memory loss, a user abort, an over-long prompt and a slow start do not. An
 * engine failure with no recognisable cause does (it may still be the GPU —
 * see the header: that is why a single failure never condemns the files). */
export function failureImplicatesCache(code: string | undefined, detail?: string | null): boolean {
  if (code !== "engine-failed") return false;
  // withEngineLoadDeadline's own marker — slow, not broken.
  if (detail === "engine-load-timeout") return false;
  if (detail && GPU_MEMORY_DETAIL.test(detail)) return false;
  return true;
}

export function evaluateCacheCompat(input: {
  stamp: InstallStamp | null;
  failure: LoadFailureRecord | null;
  currentRuntime: string;
  currentManifestVersion: string;
}): CacheCompatVerdict {
  const { stamp, failure, currentRuntime, currentManifestVersion } = input;
  // No stamp = installed before stamps existed = version UNKNOWN. Claiming a
  // mismatch there told every pre-2026-09-19 install "downloaded for an
  // earlier engine" after one failed load of any cause, with a button that
  // evicts gigabytes. Unknown falls through to the repeated-failure rule.
  const runtimeDiffers = !!stamp && stamp.runtime !== currentRuntime;
  // An unknown manifest version on either side is not evidence of a bump.
  const manifestDiffers =
    !!stamp && !!stamp.manifestVersion && !!currentManifestVersion && stamp.manifestVersion !== currentManifestVersion;
  const versionDiffers = runtimeDiffers || manifestDiffers;
  // Only failures seen under the CURRENT engine count.
  const failures = failure && failure.runtime === currentRuntime ? failure.count : 0;
  if (failures === 0) return { status: "ok", reason: null, offerRedownload: false, versionDiffers };
  if (versionDiffers) {
    return { status: "version-mismatch", reason: "version-mismatch", offerRedownload: true, versionDiffers };
  }
  if (failures >= SAME_VERSION_FAILURE_THRESHOLD) {
    // Its OWN reason, not "incomplete": the only action offered for this
    // verdict evicts and re-downloads the whole model, and the "incomplete"
    // copy promises the opposite ("fetches only what's missing").
    return { status: "corrupt", reason: "cache-unloadable", offerRedownload: true, versionDiffers };
  }
  return { status: "ok", reason: null, offerRedownload: false, versionDiffers };
}
