// startTier1Download's failure paths, end to end (2026-09-19).
//
// WHY THIS FILE EXISTS. The weights come from Hugging Face — a host this
// platform does not run. Before today every way that could go wrong produced
// the same result: { code: "failed" } and the string "Download interrupted —
// resume to continue", with a Retry button that (for WebLLM rows) did nothing.
// These tests pin the replacement contract:
//   - a blocker found BEFORE the download never starts the engine at all;
//   - every failure carries a typed reason + copy + one action;
//   - transient network failures are retried, permanent ones are not;
//   - "installed" is only ever written for a complete file set;
//   - an installed model that stops loading is detected and re-downloadable;
//   - the shared Retry path (downloadManager.resume) actually re-runs it.
//
// Harness mirrors tier1.weights.test.ts: real web-llm model id, fake Cache
// API, fetch mock, vi.resetModules() per test.

import { existsSync, readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const MODEL_A = "Hermes-2-Pro-Llama-3-8B-q4f16_1-MLC";
const MARKER_A = `edge.tier1.cached.${MODEL_A}`;
const BASE = `https://huggingface.co/mlc-ai/${MODEL_A}/resolve/main/`;
const COMPLETE = [`${BASE}ndarray-cache.json`, `${BASE}params_shard_0.bin`];

function fakeCaches(urls: string[]) {
  const cache = {
    async keys() {
      return urls.map((url) => ({ url }));
    },
    async match(req: string | { url: string }) {
      const url = typeof req === "string" ? req : req.url;
      if (!urls.includes(url)) return undefined;
      return {
        headers: { get: () => "1000" },
        json: async () => ({ records: [{ dataPath: "params_shard_0.bin" }] }),
      };
    },
    async delete() {
      return true;
    },
  };
  return { has: async () => true, open: async () => cache };
}

function buildCaps(webgpu = true) {
  return {
    version: 1 as const,
    detectedAt: 0,
    webgpu: { available: webgpu, maxBufferSize: 1_000_000_000, maxStorageBufferBindingSize: 1_000_000_000 },
    promptApi: { shape: "none" as const, availability: "unavailable" as const },
    deviceMemoryGB: 8,
    hardwareConcurrency: 8,
    connection: { saveData: false, effectiveType: "4g" },
  };
}

function makeFakeEngine() {
  return {
    setInitProgressCallback: vi.fn(),
    setAppConfig: vi.fn(),
    interruptGenerate: vi.fn(),
    unload: vi.fn(async () => {}),
    reload: vi.fn(async () => {}),
    chat: { completions: { create: vi.fn(async () => ({ choices: [{ message: { content: "" } }] })) } },
  };
}

function setStorageEstimate(estimate: { usage: number; quota: number } | null): void {
  Object.defineProperty(globalThis.navigator, "storage", {
    configurable: true,
    value: estimate
      ? { estimate: async () => estimate, persist: async () => true, persisted: async () => true }
      : undefined,
  });
}

function setOnline(online: boolean): void {
  Object.defineProperty(globalThis.navigator, "onLine", { configurable: true, get: () => online });
}

describe("edge/tier1 - download resilience", () => {
  let createEngineMock: ReturnType<typeof vi.fn<(...args: any[]) => Promise<any>>>;
  /** What a HEAD against huggingface.co does in this test. */
  let hostProbe: () => Promise<{ ok: boolean; status: number }>;

  beforeEach(() => {
    vi.resetModules();
    localStorage.clear();
    process.env.NEXT_PUBLIC_EDGE_AGENTS = "1";
    setOnline(true);
    setStorageEstimate({ usage: 0, quota: 100_000_000_000 });
    (globalThis as any).caches = fakeCaches(COMPLETE);
    hostProbe = async () => ({ ok: true, status: 200 });
    (globalThis as any).fetch = vi.fn(async (url: string) => {
      if (String(url).includes("/api/edge/manifest")) {
        return {
          ok: true,
          json: async () => ({
            version: "1",
            models: [
              {
                id: MODEL_A,
                displayName: "Model A",
                sizeBytes: 1_000_000_000,
                minDeviceMemoryGB: 0,
                minMaxBufferSize: 0,
                tier: 1,
                speedTier: "fast",
              },
            ],
          }),
        };
      }
      if (String(url).startsWith("https://huggingface.co/")) return hostProbe();
      return { ok: false, status: 404, json: async () => ({}) };
    });
    createEngineMock = vi.fn(async () => makeFakeEngine());
    vi.doMock("@mlc-ai/web-llm", () => ({
      CreateMLCEngine: createEngineMock,
      deleteModelAllInfoInCache: vi.fn(async () => {}),
      prebuiltAppConfig: { cacheBackend: "cache", model_list: [] },
    }));
    // Backoff sleeps are real timers; make them instant.
    vi.doMock("./resilience/retry", async () => {
      const actual = await vi.importActual<typeof import("./resilience/retry")>("./resilience/retry");
      return {
        ...actual,
        withRetry: (task: any, options: any) => actual.withRetry(task, { ...options, sleep: async () => {} }),
      };
    });
  });

  afterEach(() => {
    delete (globalThis as any).caches;
    setStorageEstimate(null);
    vi.doUnmock("@mlc-ai/web-llm");
    vi.doUnmock("./resilience/retry");
  });

  it("TIER1_RUNTIME_STAMP matches the installed @mlc-ai/web-llm (update it with the upgrade)", async () => {
    const require_ = createRequire(import.meta.url);
    let pkgPath: string | null = null;
    try {
      pkgPath = require_.resolve("@mlc-ai/web-llm/package.json");
    } catch {
      pkgPath = null;
    }
    if (!pkgPath || !existsSync(pkgPath)) return; // fresh clone — nothing to pin
    const version = JSON.parse(readFileSync(pkgPath, "utf8")).version as string;
    vi.doUnmock("@mlc-ai/web-llm");
    const { TIER1_RUNTIME_STAMP } = await import("./tier1");
    // If this fails you upgraded web-llm: bump TIER1_RUNTIME_STAMP in tier1.ts
    // so installs made under the old engine are recognisable as such.
    expect(TIER1_RUNTIME_STAMP).toBe(`web-llm@${version}`);
  });

  it("happy path: installs, stamps the engine version, and reports healthy", async () => {
    const mod = await import("./tier1");
    const result = await mod.startTier1Download(buildCaps() as any, MODEL_A);
    expect(result.ok).toBe(true);
    expect(localStorage.getItem(MARKER_A)).toBe("1");
    const stamp = JSON.parse(localStorage.getItem(`edge.tier1.stamp.${MODEL_A}`) || "{}");
    expect(stamp.runtime).toBe(mod.TIER1_RUNTIME_STAMP);
    expect(stamp.manifestVersion).toBe("1");
    expect(mod.getTier1CacheHealth(MODEL_A).status).toBe("ok");
  });

  it("no WebGPU: typed, offers hosted, never touches the engine or the network host", async () => {
    const mod = await import("./tier1");
    const result = await mod.startTier1Download(buildCaps(false) as any, MODEL_A);
    expect(result).toMatchObject({ ok: false, code: "unsupported", reason: "no-webgpu" });
    if (result.ok === false) {
      expect(result.failure.action).toBe("use-hosted");
      expect(result.failure.retryable).toBe(false);
    }
    expect(createEngineMock).not.toHaveBeenCalled();
  });

  it("quota too small: refused BEFORE the download with both sizes in the message", async () => {
    setStorageEstimate({ usage: 9_500_000_000, quota: 10_000_000_000 });
    const mod = await import("./tier1");
    const { downloadManager } = await import("./downloadManager");
    const result = await mod.startTier1Download(buildCaps() as any, MODEL_A);
    expect(result).toMatchObject({ ok: false, code: "no-storage", reason: "quota-too-small" });
    if (result.ok === false) {
      expect(result.failure.message).toContain("1.0 GB");
      expect(result.failure.message).toContain("500 MB");
      expect(result.failure.action).toBe("free-space"); // the manifest has no smaller model
    }
    expect(createEngineMock).not.toHaveBeenCalled();
    expect(localStorage.getItem(MARKER_A)).toBeNull();
    expect(downloadManager.getAsset(MODEL_A)).toMatchObject({ state: "error", failureReason: "quota-too-small" });
  });

  it("host unreachable: the HEAD probe fails, the engine is never started, the host is named", async () => {
    hostProbe = async () => {
      throw new TypeError("Failed to fetch");
    };
    const mod = await import("./tier1");
    const result = await mod.startTier1Download(buildCaps() as any, MODEL_A);
    expect(result).toMatchObject({ ok: false, reason: "host-unreachable" });
    if (result.ok === false) expect(result.failure.message).toContain("huggingface.co");
    expect(createEngineMock).not.toHaveBeenCalled();
  });

  it("offline: says so, and does not blame the host", async () => {
    setOnline(false);
    const mod = await import("./tier1");
    const result = await mod.startTier1Download(buildCaps() as any, MODEL_A);
    expect(result).toMatchObject({ ok: false, reason: "offline" });
    expect(createEngineMock).not.toHaveBeenCalled();
  });

  it("a dropped connection mid-download is retried and then succeeds", async () => {
    createEngineMock
      .mockRejectedValueOnce(new TypeError("Failed to fetch"))
      .mockRejectedValueOnce(new TypeError("Failed to fetch"));
    const mod = await import("./tier1");
    const result = await mod.startTier1Download(buildCaps() as any, MODEL_A);
    expect(result.ok).toBe(true);
    expect(createEngineMock).toHaveBeenCalledTimes(3);
  });

  it("a connection that keeps dropping ends as network-interrupted after bounded retries", async () => {
    createEngineMock.mockRejectedValue(new TypeError("Failed to fetch"));
    const mod = await import("./tier1");
    const { downloadManager } = await import("./downloadManager");
    const result = await mod.startTier1Download(buildCaps() as any, MODEL_A);
    expect(result).toMatchObject({ ok: false, code: "failed", reason: "network-interrupted" });
    if (result.ok === false) expect(result.failure.action).toBe("retry");
    expect(createEngineMock).toHaveBeenCalledTimes(3);
    expect(localStorage.getItem(MARKER_A)).toBeNull();
    expect(downloadManager.getAsset(MODEL_A)).toMatchObject({
      state: "error",
      failureReason: "network-interrupted",
      external: true,
    });
  });

  it("a full disk mid-download is NOT retried", async () => {
    createEngineMock.mockRejectedValue(Object.assign(new Error("Quota exceeded."), { name: "QuotaExceededError" }));
    const mod = await import("./tier1");
    const result = await mod.startTier1Download(buildCaps() as any, MODEL_A);
    expect(result).toMatchObject({ ok: false, reason: "storage-write-failed" });
    expect(createEngineMock).toHaveBeenCalledTimes(1);
  });

  it("partial download: the engine loaded but the cached file set is incomplete → never marked installed", async () => {
    // tensor index present, the shard it lists is not.
    (globalThis as any).caches = fakeCaches([COMPLETE[0]]);
    const mod = await import("./tier1");
    const result = await mod.startTier1Download(buildCaps() as any, MODEL_A);
    expect(result).toMatchObject({ ok: false, reason: "incomplete" });
    expect(localStorage.getItem(MARKER_A)).toBeNull();
    expect(await mod.getTier1RunModel(buildCaps() as any)).toBeNull();
  });

  it("the shared Retry button (downloadManager.resume) re-runs the WebLLM download instead of parking on 'queued'", async () => {
    createEngineMock.mockRejectedValue(new TypeError("Failed to fetch"));
    const mod = await import("./tier1");
    const { downloadManager } = await import("./downloadManager");
    await mod.startTier1Download(buildCaps() as any, MODEL_A);
    expect(downloadManager.canRetry(MODEL_A)).toBe(true);

    createEngineMock.mockReset();
    createEngineMock.mockImplementation(async () => makeFakeEngine());
    downloadManager.resume(MODEL_A);
    await vi.waitFor(() => expect(downloadManager.getAsset(MODEL_A)?.state).toBe("ready"));
    expect(localStorage.getItem(MARKER_A)).toBe("1");
  });

  it("pause/cancel are refused on a WebLLM row rather than faking a state WebLLM will not honour", async () => {
    createEngineMock.mockRejectedValue(new TypeError("Failed to fetch"));
    const mod = await import("./tier1");
    const { downloadManager } = await import("./downloadManager");
    await mod.startTier1Download(buildCaps() as any, MODEL_A);
    downloadManager.pause(MODEL_A);
    downloadManager.cancel(MODEL_A);
    expect(downloadManager.getAsset(MODEL_A)?.state).toBe("error");
  });

  it("version mismatch: a model STAMPED under an older engine that stops loading is flagged, and re-download clears it", async () => {
    localStorage.setItem(MARKER_A, "1");
    localStorage.setItem(
      `edge.tier1.stamp.${MODEL_A}`,
      JSON.stringify({ runtime: "web-llm@0.0.1", manifestVersion: "1", installedAt: 1 })
    );
    createEngineMock.mockRejectedValue(new Error("Cannot find model record in appConfig"));
    const mod = await import("./tier1");
    expect(mod.getTier1CacheHealth(MODEL_A).status).toBe("ok"); // no failure yet → never flagged on version alone
    await mod.startTier1Download(buildCaps() as any, MODEL_A);
    const health = mod.getTier1CacheHealth(MODEL_A);
    expect(health).toMatchObject({ status: "version-mismatch", reason: "version-mismatch", offerRedownload: true });

    createEngineMock.mockReset();
    createEngineMock.mockImplementation(async () => makeFakeEngine());
    const again = await mod.redownloadTier1Model(buildCaps() as any, MODEL_A);
    expect(again.ok).toBe(true);
    expect(mod.getTier1CacheHealth(MODEL_A).status).toBe("ok");
    expect(localStorage.getItem(`edge.tier1.loadFailure.${MODEL_A}`)).toBeNull();
  });

  it("GPU memory loss is never blamed on the cached files", async () => {
    localStorage.setItem(MARKER_A, "1");
    createEngineMock.mockRejectedValue(Object.assign(new Error("device lost"), { name: "DeviceLostError" }));
    const mod = await import("./tier1");
    const result = await mod.startTier1Download(buildCaps() as any, MODEL_A);
    expect(result).toMatchObject({ ok: false, reason: "device-memory" });
    expect(mod.getTier1CacheHealth(MODEL_A).status).toBe("ok");
  });

  // ---- Review fixes (2026-09-19) -------------------------------------------

  it("a legacy install with NO stamp is never called a version mismatch: one failure is ignored, a repeat is 'keeps failing'", async () => {
    localStorage.setItem(MARKER_A, "1"); // installed before stamps existed
    createEngineMock.mockRejectedValue(new Error("some generic engine failure"));
    const mod = await import("./tier1");

    await mod.startTier1Download(buildCaps() as any, MODEL_A);
    expect(mod.getTier1CacheHealth(MODEL_A)).toMatchObject({ status: "ok", offerRedownload: false });

    await mod.startTier1Download(buildCaps() as any, MODEL_A);
    const health = mod.getTier1CacheHealth(MODEL_A);
    expect(health).toMatchObject({ status: "corrupt", reason: "cache-unloadable", offerRedownload: true });
    // The copy for the only action offered must say that it evicts and
    // re-fetches everything — not "fetches only what's missing".
    const { describeDownloadFailure } = await import("./resilience/failures");
    const copy = describeDownloadFailure(health.reason!, { neededBytes: 1_000_000_000 });
    expect(copy.action).toBe("redownload");
    expect(copy.message).toContain("whole model");
    expect(copy.message).toContain("1.0 GB");
    expect(copy.message).not.toMatch(/only what'?s missing/i);
  });

  it("a generic out-of-memory Error (not DeviceLostError) is not counted against the cached files", async () => {
    localStorage.setItem(MARKER_A, "1");
    createEngineMock.mockRejectedValue(new Error("Out of memory: buffer allocation failed"));
    const mod = await import("./tier1");
    await mod.startTier1Download(buildCaps() as any, MODEL_A);
    await mod.startTier1Download(buildCaps() as any, MODEL_A);
    expect(localStorage.getItem(`edge.tier1.loadFailure.${MODEL_A}`)).toBeNull();
    expect(mod.getTier1CacheHealth(MODEL_A).status).toBe("ok");
  });

  it("a successful load backfills a missing stamp and moves an older stamp forward", async () => {
    localStorage.setItem(MARKER_A, "1");
    const mod = await import("./tier1");
    const { setModelOverride } = await import("./capabilities");
    setModelOverride(MODEL_A);
    const stampKey = `edge.tier1.stamp.${MODEL_A}`;

    await mod.createTier1Session(buildCaps() as any);
    expect(JSON.parse(localStorage.getItem(stampKey) || "{}").runtime).toBe(mod.TIER1_RUNTIME_STAMP);

    // An install stamped under an older engine that loads fine now: the stamp
    // follows, so a later unrelated failure cannot be blamed on "an earlier engine".
    localStorage.setItem(stampKey, JSON.stringify({ runtime: "web-llm@0.0.1", manifestVersion: "1", installedAt: 7 }));
    vi.resetModules(); // a fresh page: no warm engine, so the session below really loads
    const fresh = await import("./tier1");
    const caps2 = await import("./capabilities");
    caps2.setModelOverride(MODEL_A);
    await fresh.createTier1Session(buildCaps() as any);
    const moved = JSON.parse(localStorage.getItem(stampKey) || "{}");
    expect(moved.runtime).toBe(fresh.TIER1_RUNTIME_STAMP);
    expect(moved.installedAt).toBe(7); // the install date is history, not rewritten
  });

  it("incomplete: the warm engine is released, so the retry really runs CreateMLCEngine again", async () => {
    (globalThis as any).caches = fakeCaches([COMPLETE[0]]); // index present, its shard missing
    const engines: Array<ReturnType<typeof makeFakeEngine>> = [];
    createEngineMock.mockImplementation(async () => {
      const e = makeFakeEngine();
      engines.push(e);
      return e;
    });
    const mod = await import("./tier1");
    const first = await mod.startTier1Download(buildCaps() as any, MODEL_A);
    expect(first).toMatchObject({ ok: false, reason: "incomplete" });
    expect(createEngineMock).toHaveBeenCalledTimes(1);
    expect(engines[0].unload).toHaveBeenCalledTimes(1);
    expect(mod.isTier1EngineWarm(MODEL_A)).toBe(false);

    // The missing shard arrives on the retry (WebLLM fetches what the cache lacks).
    (globalThis as any).caches = fakeCaches(COMPLETE);
    const second = await mod.startTier1Download(buildCaps() as any, MODEL_A);
    expect(createEngineMock).toHaveBeenCalledTimes(2); // before the fix: 1 — the warm engine short-circuited
    expect(second.ok).toBe(true);
    expect(localStorage.getItem(MARKER_A)).toBe("1");
  });

  it("a drop that leaves navigator.onLine false waits for the link and then retries", async () => {
    createEngineMock.mockImplementationOnce(async () => {
      setOnline(false); // wifi roam: the fetch rejects while the browser reports offline
      setTimeout(() => {
        setOnline(true);
        window.dispatchEvent(new Event("online"));
      }, 20);
      throw new TypeError("Failed to fetch");
    });
    const mod = await import("./tier1");
    const result = await mod.startTier1Download(buildCaps() as any, MODEL_A);
    expect(result.ok).toBe(true);
    expect(createEngineMock).toHaveBeenCalledTimes(2);
  });

  describe("stall watchdog (fake timers)", () => {
    beforeEach(() => {
      vi.useFakeTimers();
    });
    afterEach(() => {
      vi.useRealTimers();
    });

    function deferredEngine() {
      let resolve!: (e: ReturnType<typeof makeFakeEngine>) => void;
      const promise = new Promise<ReturnType<typeof makeFakeEngine>>((r) => (resolve = r));
      return { promise, resolve };
    }

    it("a silent load stops being WAITED on after the stall window, and a late completion still installs and flips the row to ready", async () => {
      const load = deferredEngine();
      createEngineMock.mockImplementation(() => load.promise);
      const mod = await import("./tier1");
      const { downloadManager } = await import("./downloadManager");
      let settled: Awaited<ReturnType<typeof mod.startTier1Download>> | null = null;
      void mod.startTier1Download(buildCaps() as any, MODEL_A).then((r) => (settled = r));

      // 10 minutes was the old window: a slow-but-alive link must survive it.
      await vi.advanceTimersByTimeAsync(11 * 60 * 1000);
      expect(settled).toBeNull();

      await vi.advanceTimersByTimeAsync(20 * 60 * 1000);
      expect(settled).toMatchObject({ ok: false, reason: "stalled" });
      const failure = (settled as any).failure;
      expect(failure.message).toMatch(/stopped waiting/i);
      expect(failure.message).not.toMatch(/it was stopped/i); // nothing is stopped: WebLLM has no abort
      expect(downloadManager.getAsset(MODEL_A)).toMatchObject({ state: "error", failureReason: "stalled" });
      expect(localStorage.getItem(MARKER_A)).toBeNull();

      // The download was alive all along and finishes.
      load.resolve(makeFakeEngine());
      await vi.advanceTimersByTimeAsync(1000);
      expect(localStorage.getItem(MARKER_A)).toBe("1");
      expect(downloadManager.getAsset(MODEL_A)?.state).toBe("ready");
    });

    it("bytes landing in storage keep the wait alive even though no shard has completed", async () => {
      let usage = 0;
      Object.defineProperty(globalThis.navigator, "storage", {
        configurable: true,
        value: {
          estimate: async () => ({ usage: (usage += 1_000_000), quota: 100_000_000_000 }),
          persist: async () => true,
          persisted: async () => true,
        },
      });
      const load = deferredEngine();
      createEngineMock.mockImplementation(() => load.promise);
      const mod = await import("./tier1");
      let settled: Awaited<ReturnType<typeof mod.startTier1Download>> | null = null;
      void mod.startTier1Download(buildCaps() as any, MODEL_A).then((r) => (settled = r));

      await vi.advanceTimersByTimeAsync(90 * 60 * 1000); // three stall windows, zero shard events
      expect(settled).toBeNull();

      load.resolve(makeFakeEngine());
      await vi.advanceTimersByTimeAsync(1000);
      expect(settled).toMatchObject({ ok: true });
    });
  });
});
