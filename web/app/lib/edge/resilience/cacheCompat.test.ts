// Cached-model compatibility rules (2026-09-19). The property that matters
// most is the NEGATIVE one: a library upgrade alone must never make a working
// install look broken (that would push a multi-GB re-download onto everyone).

import { describe, expect, it } from "vitest";
import {
  SAME_VERSION_FAILURE_THRESHOLD,
  evaluateCacheCompat,
  failureImplicatesCache,
  nextLoadFailure,
  parseLoadFailure,
  parseStamp,
  serializeStamp,
} from "./cacheCompat";

const NOW = "web-llm@0.2.85";
const OLD = "web-llm@0.2.79";

describe("evaluateCacheCompat", () => {
  it("a version difference with NO load failure is ok — upgrades alone never demand a re-download", () => {
    const v = evaluateCacheCompat({
      stamp: { runtime: OLD, manifestVersion: "3", installedAt: 1 },
      failure: null,
      currentRuntime: NOW,
      currentManifestVersion: "4",
    });
    expect(v.status).toBe("ok");
    expect(v.offerRedownload).toBe(false);
    expect(v.versionDiffers).toBe(true);
  });

  it("version mismatch: one load failure after an engine upgrade → explain + offer re-download", () => {
    const v = evaluateCacheCompat({
      stamp: { runtime: OLD, manifestVersion: "3", installedAt: 1 },
      failure: { count: 1, lastAt: 2, runtime: NOW },
      currentRuntime: NOW,
      currentManifestVersion: "3",
    });
    expect(v).toMatchObject({ status: "version-mismatch", reason: "version-mismatch", offerRedownload: true });
  });

  it("a registry version bump counts as a version difference too", () => {
    const v = evaluateCacheCompat({
      stamp: { runtime: NOW, manifestVersion: "3", installedAt: 1 },
      failure: { count: 1, lastAt: 2, runtime: NOW },
      currentRuntime: NOW,
      currentManifestVersion: "4",
    });
    expect(v.status).toBe("version-mismatch");
  });

  it("an install that predates stamping (no stamp) is version-UNKNOWN: one failure is noise, never a mismatch", () => {
    const v = evaluateCacheCompat({
      stamp: null,
      failure: { count: 1, lastAt: 2, runtime: NOW },
      currentRuntime: NOW,
      currentManifestVersion: "4",
    });
    expect(v).toMatchObject({ status: "ok", reason: null, offerRedownload: false, versionDiffers: false });
  });

  it("an unstamped install that KEEPS failing is flagged as unloadable files, still never as a version mismatch", () => {
    const v = evaluateCacheCompat({
      stamp: null,
      failure: { count: SAME_VERSION_FAILURE_THRESHOLD, lastAt: 2, runtime: NOW },
      currentRuntime: NOW,
      currentManifestVersion: "",
    });
    expect(v).toMatchObject({ status: "corrupt", reason: "cache-unloadable", offerRedownload: true });
  });

  it("same version: one failure is noise, a repeat blames the files", () => {
    const base = {
      stamp: { runtime: NOW, manifestVersion: "3", installedAt: 1 },
      currentRuntime: NOW,
      currentManifestVersion: "3",
    };
    expect(evaluateCacheCompat({ ...base, failure: { count: 1, lastAt: 2, runtime: NOW } }).status).toBe("ok");
    const repeated = evaluateCacheCompat({
      ...base,
      failure: { count: SAME_VERSION_FAILURE_THRESHOLD, lastAt: 2, runtime: NOW },
    });
    // NOT "incomplete": that copy promises a partial fetch, this verdict's only action evicts everything.
    expect(repeated).toMatchObject({ status: "corrupt", reason: "cache-unloadable", offerRedownload: true });
  });

  it("failures recorded under a PREVIOUS engine don't count against this one", () => {
    const v = evaluateCacheCompat({
      stamp: { runtime: OLD, manifestVersion: "3", installedAt: 1 },
      failure: { count: 9, lastAt: 2, runtime: OLD },
      currentRuntime: NOW,
      currentManifestVersion: "3",
    });
    expect(v.status).toBe("ok");
  });

  it("an unknown manifest version on either side is not evidence of a bump", () => {
    const v = evaluateCacheCompat({
      stamp: { runtime: NOW, manifestVersion: "", installedAt: 1 },
      failure: { count: 1, lastAt: 2, runtime: NOW },
      currentRuntime: NOW,
      currentManifestVersion: "4",
    });
    expect(v.status).toBe("ok");
    expect(v.versionDiffers).toBe(false);
  });
});

describe("load-failure bookkeeping", () => {
  it("counts consecutive failures under one runtime and restarts under a new one", () => {
    const first = nextLoadFailure(null, OLD, 10);
    expect(first).toEqual({ count: 1, lastAt: 10, runtime: OLD });
    expect(nextLoadFailure(first, OLD, 11).count).toBe(2);
    expect(nextLoadFailure(first, NOW, 12)).toEqual({ count: 1, lastAt: 12, runtime: NOW });
  });

  it("only engine-failed implicates the cache — and not its slow-start variant", () => {
    expect(failureImplicatesCache("engine-failed", "Cannot find model record")).toBe(true);
    expect(failureImplicatesCache("engine-failed", "engine-load-timeout")).toBe(false);
    // A WebGPU out-of-memory that is NOT WebLLM's DeviceLostError still lands in engine-failed.
    expect(failureImplicatesCache("engine-failed", "Out of memory: failed to allocate buffer")).toBe(false);
    expect(failureImplicatesCache("engine-failed", "GPUOutOfMemoryError")).toBe(false);
    expect(failureImplicatesCache("engine-failed", "The device was lost")).toBe(false);
    for (const code of ["device-lost", "aborted", "context-window-exceeded", "prompt-failed", undefined]) {
      expect(failureImplicatesCache(code)).toBe(false);
    }
  });
});

describe("parsing is tolerant", () => {
  it("round-trips a stamp and rejects junk as 'no stamp'", () => {
    const stamp = { runtime: NOW, manifestVersion: "7", installedAt: 123 };
    expect(parseStamp(serializeStamp(stamp))).toEqual(stamp);
    expect(parseStamp(null)).toBeNull();
    expect(parseStamp("")).toBeNull();
    expect(parseStamp("{not json")).toBeNull();
    expect(parseStamp("1")).toBeNull();
    expect(parseStamp(JSON.stringify({ runtime: "" }))).toBeNull();
    // Numeric manifest versions (older servers) are normalised to strings.
    expect(parseStamp(JSON.stringify({ runtime: NOW, manifestVersion: 7 }))?.manifestVersion).toBe("7");
  });

  it("a zero/garbage failure record reads as no failures", () => {
    expect(parseLoadFailure(JSON.stringify({ count: 0 }))).toBeNull();
    expect(parseLoadFailure("nope")).toBeNull();
    expect(parseLoadFailure(JSON.stringify({ count: "3" }))).toBeNull();
    expect(parseLoadFailure(JSON.stringify({ count: 2, lastAt: 5, runtime: NOW }))).toEqual({
      count: 2,
      lastAt: 5,
      runtime: NOW,
    });
  });
});
