// Install stamps + load-failure memory (resilience/cacheCompat.ts). Split out
// of ../tier1.ts.

import {
  evaluateCacheCompat,
  failureImplicatesCache,
  nextLoadFailure,
  parseLoadFailure,
  parseStamp,
  serializeStamp,
  type CacheCompatVerdict,
} from "../resilience/cacheCompat";
import type { Tier1Error } from "./errors";
import { getCachedTier1ManifestVersion } from "./manifest";
import { readOnlineFlag } from "./online";
import { isTier1ModelCached, safeLocalGet, safeLocalRemove, safeLocalSet } from "./weightStore";

/** Engine identity recorded next to every install. MUST match the installed
 * @mlc-ai/web-llm version — tier1.resilience.test.ts reads package.json and
 * fails when an upgrade forgets this line. Hard-coded rather than imported
 * because the bundle rule above forbids a static import of the package, and
 * the routing hot path must be able to read it without loading the library. */
export const TIER1_RUNTIME_STAMP = "web-llm@0.2.85";

export const TIER1_STAMP_KEY_PREFIX = "edge.tier1.stamp.";
export const TIER1_LOAD_FAILURE_KEY_PREFIX = "edge.tier1.loadFailure.";

export function recordTier1InstallStamp(modelId: string): void {
  safeLocalSet(
    TIER1_STAMP_KEY_PREFIX + modelId,
    serializeStamp({
      runtime: TIER1_RUNTIME_STAMP,
      manifestVersion: getCachedTier1ManifestVersion(),
      installedAt: Date.now(),
    })
  );
  safeLocalRemove(TIER1_LOAD_FAILURE_KEY_PREFIX + modelId);
}

/** Called from acquireEngine's catch. Counts failures that MAY say something
 * about the cached FILES of a model this device believes is installed.
 * Skipped: a first download, an offline load, the slow-start timeout, and GPU
 * memory loss WHEN RECOGNISABLE — WebLLM's DeviceLostError (code
 * "device-lost") or an "engine-failed" whose original message says out of
 * memory / device lost (failureImplicatesCache). A GPU failure that arrives as
 * a generic Error with neither marker cannot be told apart from a bad file and
 * is counted; cacheCompat absorbs that by never acting on a single failure
 * unless an install stamp proves the engine version changed. */
export function noteTier1LoadFailure(modelId: string, err: Tier1Error): void {
  try {
    if (!isTier1ModelCached(modelId)) return; // a failed first download isn't a cache problem
    if (!failureImplicatesCache(err.code, err.detail)) return;
    if (readOnlineFlag() === false) return; // offline load of a partially-evicted model: not the files' fault
    const previous = parseLoadFailure(safeLocalGet(TIER1_LOAD_FAILURE_KEY_PREFIX + modelId));
    safeLocalSet(
      TIER1_LOAD_FAILURE_KEY_PREFIX + modelId,
      JSON.stringify(nextLoadFailure(previous, TIER1_RUNTIME_STAMP, Date.now()))
    );
  } catch {
    // Bookkeeping must never mask the real load error.
  }
}

export function noteTier1LoadSuccess(modelId: string): void {
  safeLocalRemove(TIER1_LOAD_FAILURE_KEY_PREFIX + modelId);
  // A load that just succeeded proves these files work with the CURRENT
  // engine and registry, which is what the stamp is for. So (a) backfill a
  // stamp for installs made before stamps existed (2026-09-19), and (b) move
  // an older stamp forward — otherwise a model that has loaded fine since an
  // upgrade would still be told "downloaded for an earlier engine" after one
  // later, unrelated failure. Only for a model already marked installed: a
  // first download is stamped by finalizeTier1Install, after its completeness
  // check.
  try {
    if (!isTier1ModelCached(modelId)) return;
    const existing = parseStamp(safeLocalGet(TIER1_STAMP_KEY_PREFIX + modelId));
    const manifestVersion = getCachedTier1ManifestVersion();
    const current =
      !!existing &&
      existing.runtime === TIER1_RUNTIME_STAMP &&
      (!manifestVersion || existing.manifestVersion === manifestVersion);
    if (current) return;
    safeLocalSet(
      TIER1_STAMP_KEY_PREFIX + modelId,
      serializeStamp({
        runtime: TIER1_RUNTIME_STAMP,
        manifestVersion: manifestVersion || existing?.manifestVersion || "",
        installedAt: existing?.installedAt || Date.now(),
      })
    );
  } catch {
    // Bookkeeping must never fail a successful load.
  }
}

/**
 * Is an INSTALLED model stuck? Answers "version-mismatch" when it has failed
 * to load since an engine/registry version change, "corrupt" when it keeps
 * failing under the same version, else "ok". Synchronous and cheap
 * (localStorage only) so a list can call it per row. See cacheCompat.ts for
 * why a version difference alone is never treated as a problem.
 */
export function getTier1CacheHealth(modelId: string): CacheCompatVerdict {
  return evaluateCacheCompat({
    stamp: parseStamp(safeLocalGet(TIER1_STAMP_KEY_PREFIX + modelId)),
    failure: parseLoadFailure(safeLocalGet(TIER1_LOAD_FAILURE_KEY_PREFIX + modelId)),
    currentRuntime: TIER1_RUNTIME_STAMP,
    currentManifestVersion: getCachedTier1ManifestVersion(),
  });
}
