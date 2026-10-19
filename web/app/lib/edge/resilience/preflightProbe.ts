// On-device download PREFLIGHT — the browser-touching half (2026-09-19).
//
// Gathers the inputs preflight.ts decides on. Every browser API here is
// feature-detected and wrapped: Safari (incl. iOS) has no
// navigator.connection and historically a partial StorageManager; Firefox has
// no navigator.connection either; private windows may have no Cache API or a
// tiny quota. A probe that throws would turn "can't tell" into "can't
// download", so NOTHING in this file rejects — unknown is a valid answer and
// preflight.ts treats unknown as "don't block".
//
// No app imports (worker-safe, and cannot participate in an import cycle with
// tier1.ts / downloadManager.ts, which both call into here).

import type { PreflightInput } from "./preflight";

/** A probe that hasn't answered by now is "unknown", not "unreachable": slow
 * is not blocked, and refusing a download because a HEAD took 6s on hotel
 * wifi would be this code inventing a failure. */
export const HOST_PROBE_TIMEOUT_MS = 5_000;

export type HostProbeStatus = "reachable" | "unreachable" | "unknown";

/** Statuses a filtering proxy / captive portal answers INSTEAD of the host.
 * 407 = proxy auth, 451 = legally blocked, 403 on a public file = filtered
 * (Hugging Face serves public model files without auth). */
const BLOCKED_STATUSES = new Set([403, 407, 451]);

export function hostOf(url: string): string {
  try {
    return new URL(url).hostname;
  } catch {
    return url;
  }
}

/** Pure: map a probe outcome to a status. Exported for tests. */
export function interpretProbeOutcome(
  outcome: { kind: "response"; status: number } | { kind: "timeout" } | { kind: "error" }
): HostProbeStatus {
  if (outcome.kind === "timeout") return "unknown";
  if (outcome.kind === "error") return "unreachable";
  if (BLOCKED_STATUSES.has(outcome.status)) return "unreachable";
  // 5xx: the host answered and is unwell. The download would hit the same
  // wall, so say so before the person commits to it.
  if (outcome.status >= 500) return "unreachable";
  // 2xx/3xx/404/429 all prove the network path to the host works.
  return "reachable";
}

/**
 * HEAD one small file on the model host. `fetchImpl` is injectable for tests.
 * Never rejects. `cache: "no-store"` so a cached answer can't vouch for a
 * host that is unreachable right now.
 */
export async function probeModelHost(
  url: string,
  fetchImpl: typeof fetch | null = typeof fetch !== "undefined" ? fetch : null,
  timeoutMs = HOST_PROBE_TIMEOUT_MS
): Promise<HostProbeStatus> {
  if (!fetchImpl || !url) return "unknown";
  let timer: ReturnType<typeof setTimeout> | null = null;
  let timedOut = false;
  try {
    const controller = typeof AbortController !== "undefined" ? new AbortController() : null;
    const timeout = new Promise<"timeout">((resolve) => {
      timer = setTimeout(() => {
        timedOut = true;
        try {
          controller?.abort();
        } catch {
          // Best-effort.
        }
        resolve("timeout");
      }, timeoutMs);
    });
    const request = fetchImpl(url, {
      method: "HEAD",
      cache: "no-store",
      signal: controller?.signal,
    }).then(
      (res) => ({ kind: "response" as const, status: res.status }),
      () => ({ kind: "error" as const })
    );
    const winner = await Promise.race([request, timeout]);
    if (winner === "timeout" || timedOut) return interpretProbeOutcome({ kind: "timeout" });
    return interpretProbeOutcome(winner);
  } catch {
    return "unknown";
  } finally {
    if (timer) clearTimeout(timer);
  }
}

export async function readStorageEstimate(): Promise<PreflightInput["storage"]> {
  try {
    if (typeof navigator === "undefined" || !navigator.storage?.estimate) return null;
    const est = await navigator.storage.estimate();
    return { usageBytes: est.usage || 0, quotaBytes: est.quota || 0 };
  } catch {
    return null;
  }
}

/** persisted() only — it never prompts. The persist() REQUEST is made by the
 * caller after the person's explicit download click (Firefox shows a
 * permission prompt for it, which must not appear from a passive probe). */
export async function readPersisted(): Promise<boolean | undefined> {
  try {
    if (typeof navigator === "undefined" || !navigator.storage?.persisted) return undefined;
    return await navigator.storage.persisted();
  } catch {
    return undefined;
  }
}

export function readConnection(): PreflightInput["connection"] {
  try {
    const nav = (typeof navigator !== "undefined" ? navigator : undefined) as
      | (Navigator & { connection?: { saveData?: boolean; effectiveType?: string; metered?: boolean } })
      | undefined;
    const c = nav?.connection;
    return {
      saveData: !!c?.saveData,
      effectiveType: typeof c?.effectiveType === "string" ? c.effectiveType : null,
      metered: typeof c?.metered === "boolean" ? c.metered : undefined,
    };
  } catch {
    return { saveData: false, effectiveType: null };
  }
}

export function readOnline(): boolean | undefined {
  try {
    if (typeof navigator === "undefined" || typeof navigator.onLine !== "boolean") return undefined;
    return navigator.onLine;
  } catch {
    return undefined;
  }
}

export function wasmAvailable(): boolean {
  try {
    return typeof WebAssembly === "object" && typeof WebAssembly.instantiate === "function";
  } catch {
    return false;
  }
}

/** `caches` exists AND opens. Some private modes expose the global but reject
 * open() with a SecurityError — existence alone is not enough. */
export async function cacheStorageAvailable(): Promise<boolean> {
  try {
    if (typeof caches === "undefined") return false;
    await caches.has("edge-preflight-probe");
    return true;
  } catch {
    return false;
  }
}

export interface GatherPreflightOptions {
  modelBytes: number;
  edgeEnabled: boolean;
  requiresWebGpu: boolean;
  webgpuAvailable: boolean;
  /** One small file the download will need, on the host it comes from. */
  probeUrl: string;
  /** See PreflightInput.requiresCacheStorage. Omitted = true. */
  requiresCacheStorage?: boolean;
}

/** Gather everything in parallel. Never rejects. */
export async function gatherPreflightInput(options: GatherPreflightOptions): Promise<PreflightInput> {
  const online = readOnline();
  const [storage, persisted, cacheOk, hostStatus] = await Promise.all([
    readStorageEstimate(),
    readPersisted(),
    cacheStorageAvailable(),
    // Offline → don't spend the probe timeout proving it.
    online === false ? Promise.resolve<HostProbeStatus>("unknown") : probeModelHost(options.probeUrl),
  ]);
  return {
    modelBytes: options.modelBytes,
    edgeEnabled: options.edgeEnabled,
    requiresWebGpu: options.requiresWebGpu,
    webgpuAvailable: options.webgpuAvailable,
    wasmAvailable: wasmAvailable(),
    cacheStorageAvailable: cacheOk,
    requiresCacheStorage: options.requiresCacheStorage !== false,
    online,
    storage,
    persisted,
    connection: readConnection(),
    host: { name: hostOf(options.probeUrl), status: hostStatus },
  };
}
