// Tier 2's "is the model really on this device?" answer (split out of tier2.ts):
// the localStorage install markers, the Cache API walk that actually decides,
// and the session memo the router reads on every turn. Never downloads.

import { TIER2_MODEL_ID, TIER2_REQUIRED_HUB_FILES, TRANSFORMERS_CACHE_KEY } from "./tier2Shared";

// ---------------------------------------------------------------------------
// localStorage helpers (same guarded shape as every sibling edge module)
// ---------------------------------------------------------------------------

function safeLocalGet(key: string): string | null {
  try {
    if (typeof localStorage === "undefined") return null;
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

function safeLocalSet(key: string, value: string): void {
  try {
    if (typeof localStorage === "undefined") return;
    localStorage.setItem(key, value);
  } catch {
    // Best-effort.
  }
}

function safeLocalRemove(key: string): void {
  try {
    if (typeof localStorage === "undefined") return;
    localStorage.removeItem(key);
  } catch {
    // Best-effort.
  }
}

/** Set only after a load completed on this device. Like tier1.ts's marker its
 * ABSENCE is trustworthy ("never installed here" — the cheap synchronous
 * short-circuit) while its presence is only a hint; the Cache API walk is
 * what actually decides, and it clears this marker when it proves it stale. */
const TIER2_CACHED_KEY = "edge.tier2.cached." + TIER2_MODEL_ID;
/** The ONNX Runtime asset URLs the successful install actually used, recorded
 * so readiness can check those exact entries rather than pattern-matching a
 * CDN URL whose version it would be guessing at.
 *
 * THE `-ort2` SUFFIX IS A DELIBERATE ONE-TIME INVALIDATION (2026-09-17,
 * @huggingface/transformers 4.2.0 -> 4.3.0). The library builds its wasm URLs
 * as `https://cdn.jsdelivr.net/npm/onnxruntime-web@${version}/dist/...` from
 * the ORT version IT ships - we never pin it - and that version moved
 * 1.26.0-dev.20260416 -> 1.31.0-dev.20260914. Every recorded URL therefore now
 * points at bytes the runtime will no longer ask for.
 *
 * Without this suffix the failure is silent and bad: tier2FilesOnDevice()
 * would find the OLD urls still cached, report ready, let tierRouter route a
 * turn to tier 2, and the runtime would then fetch tens of MB from
 * cdn.jsdelivr.net mid-conversation - precisely the "surprise download" that
 * check's own comment says it exists to prevent. Offline, it would just fail.
 *
 * Changing the key makes readRecordedRuntimeUrls() return [], which
 * tier2FilesOnDevice() already treats as "no install has ever completed here:
 * not ready". The user is told tier 2 needs installing and re-installs
 * explicitly. That is honest - the bytes genuinely did change - and it is the
 * conservative direction: the cost of being wrong here is one extra click,
 * versus an unattended multi-MB download on someone's phone data.
 *
 * Bump this suffix again on any future upgrade that moves the ORT version. */
const TIER2_RUNTIME_URLS_KEY = "edge.tier2.runtimeAssets-ort2." + TIER2_MODEL_ID;

export function isTier2ModelMarked(): boolean {
  return safeLocalGet(TIER2_CACHED_KEY) === "1";
}

function readRecordedRuntimeUrls(): string[] {
  try {
    const raw = safeLocalGet(TIER2_RUNTIME_URLS_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw) as unknown;
    if (!Array.isArray(parsed)) return [];
    return parsed.filter((u): u is string => typeof u === "string" && /^https?:/i.test(u));
  } catch {
    return [];
  }
}

// ---------------------------------------------------------------------------
// Is the model REALLY on this device? (the Cache API, not a flag)
// ---------------------------------------------------------------------------

/**
 * Walks the real Cache API entries — modelled on tier1.ts's
 * checkWeightsOnDevice() and indexer.ts's embedderWeightsOnDevice().
 *
 *   true  → every required file is cached, so a load moves no bytes;
 *   false → definitively missing (a load WOULD need the network);
 *   null  → could not tell (no Cache API, a private mode that throws).
 *
 * Callers treat null as NOT ready: one extra click costs nothing when the
 * files turn out to be there, and guessing the other way is exactly the
 * unattended download this check exists to prevent.
 */
export async function tier2FilesOnDevice(): Promise<boolean | null> {
  try {
    if (typeof caches === "undefined") return null;
    if (!(await caches.has(TRANSFORMERS_CACHE_KEY))) return false;
    const cache = await caches.open(TRANSFORMERS_CACHE_KEY);
    const keys = await cache.keys();
    const urls: string[] = [];
    for (const request of keys) urls.push(request.url);

    // 1. The model repo's own files. Entries are keyed by the full hub URL
    //    (https://huggingface.co/<model>/resolve/<revision>/<file>), so a
    //    substring on the repo id plus a suffix on the file name identifies
    //    them without rebuilding the library's URL template here.
    const needle = `/${TIER2_MODEL_ID}/`;
    const modelUrls = urls.filter((url) => url.includes(needle));
    if (modelUrls.length === 0) return false;
    for (const file of TIER2_REQUIRED_HUB_FILES) {
      if (!modelUrls.some((url) => url.endsWith(`/${file}`))) return false;
    }

    // 2. The ONNX Runtime wasm assets. Without these a "ready" device would
    //    still fetch tens of MB from cdn.jsdelivr.net on its first turn —
    //    a surprise download by any honest reading, so it gates routing too.
    //    No recorded URLs means no install has ever completed here: not ready.
    const runtimeUrls = readRecordedRuntimeUrls();
    if (runtimeUrls.length === 0) return false;
    for (const url of runtimeUrls) {
      if (!urls.includes(url)) return false;
    }
    return true;
  } catch {
    return null;
  }
}

/** Session memo — a router asks this on every turn and cache.keys() is not
 * free. Reset by an install so the answer flips without a reload. */
let tier2ReadinessProbe: Promise<boolean> | null = null;

/** Throw away everything this module believes about the model being present.
 * Called when a cache-only load disagrees with the readiness walk — the load
 * is the stronger evidence, and leaving the marker in place would keep the
 * router advertising an on-device tier that cannot answer. */
export function forgetTier2Readiness(): void {
  safeLocalRemove(TIER2_CACHED_KEY);
  tier2ReadinessProbe = null;
}

/** The memoised Cache API walk behind isTier2Ready(): clears a marker that
 * outlived its files. Callers gate on the cheap checks first. */
export function probeTier2Readiness(): Promise<boolean> {
  if (tier2ReadinessProbe === null) {
    tier2ReadinessProbe = (async () => {
      const present = await tier2FilesOnDevice();
      if (present === false) {
        // The marker outlived the files (eviction). Clear it so nothing
        // downstream trusts it — tier1.ts does the same for the same reason.
        safeLocalRemove(TIER2_CACHED_KEY);
        return false;
      }
      return present === true;
    })().catch(() => false);
  }
  return tier2ReadinessProbe;
}

/** Drop the memo so the next readiness question re-walks the cache. */
export function resetTier2ReadinessProbe(): void {
  tier2ReadinessProbe = null;
}

/** After a successful install: mark the model, record the runtime asset URLs
 * the readiness gate must insist on, and make the next caller see "ready"
 * without a reload or a cache re-walk. */
export function markTier2Installed(runtimeAssetUrls: string[]): void {
  safeLocalSet(TIER2_CACHED_KEY, "1");
  // Recorded so the readiness gate can insist on these exact entries rather
  // than pattern-matching a CDN URL whose version it would be guessing at.
  safeLocalSet(TIER2_RUNTIME_URLS_KEY, JSON.stringify(runtimeAssetUrls));
  // The probe's old answer is now wrong; make the next caller see the truth
  // without waiting for a reload or re-walking the cache.
  tier2ReadinessProbe = Promise.resolve(true);
}
