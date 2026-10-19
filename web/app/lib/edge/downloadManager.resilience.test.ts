// downloadManager's own transfer engine under failure (2026-09-19).
//
// No model ships through this engine yet (every manifest url is null — WebLLM
// and the transformers.js workers move today's bytes), which is exactly why it
// had never met a bad network. These tests pin what it must do the day an
// asset does ship through it:
//   - a stream that ends SHORT is never registered as installed;
//   - a dropped connection resumes with Range instead of failing the transfer;
//   - permanent failures are not retried, and carry a typed reason;
//   - the manifest fetch cannot hang forever.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { declaredTotalBytes } from "./downloadManager";

const ASSET_ID = "test-asset";
const ASSET_URL = "https://models.example/test-asset.bin";
const BYTES = new Uint8Array([1, 2, 3, 4, 5, 6, 7, 8, 9, 10]);

function fakeCacheStorage() {
  const store = new Map<string, Uint8Array>();
  const abs = (u: string) => (u.startsWith("http") ? u : `http://localhost${u}`);
  const cache = {
    async put(req: string, res: Response) {
      store.set(abs(req), new Uint8Array(await res.arrayBuffer()));
    },
    async match(req: string | { url: string }) {
      const key = abs(typeof req === "string" ? req : req.url);
      const hit = store.get(key);
      return hit ? new Response(hit.slice().buffer as ArrayBuffer) : undefined;
    },
    async keys() {
      return Array.from(store.keys()).map((url) => ({ url }));
    },
    async delete(req: string | { url: string }) {
      return store.delete(abs(typeof req === "string" ? req : req.url));
    },
  };
  return { store, api: { open: async () => cache, has: async () => true } };
}

/** A Response-like whose body yields `chunks` and then either ends or throws. */
function streamResponse(
  status: number,
  headers: Record<string, string>,
  chunks: Uint8Array[],
  failWith?: Error
) {
  let i = 0;
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: (name: string) => headers[name.toLowerCase()] ?? null },
    body: {
      getReader: () => ({
        read: async () => {
          if (i < chunks.length) return { done: false, value: chunks[i++] };
          if (failWith) throw failWith;
          return { done: true, value: undefined };
        },
      }),
    },
  };
}

describe("declaredTotalBytes", () => {
  const res = (status: number, headers: Record<string, string>) => ({
    status,
    headers: { get: (n: string) => headers[n.toLowerCase()] ?? null },
  });
  it("reads the total from Content-Range on a 206", () => {
    expect(declaredTotalBytes(res(206, { "content-range": "bytes 4-9/10" }), 4)).toBe(10);
  });
  it("falls back to start + Content-Length on a 206 with an unknown total", () => {
    expect(declaredTotalBytes(res(206, { "content-range": "bytes 4-9/*", "content-length": "6" }), 4)).toBe(10);
  });
  it("uses Content-Length on a 200, unless the body is content-encoded", () => {
    expect(declaredTotalBytes(res(200, { "content-length": "10" }), 0)).toBe(10);
    expect(declaredTotalBytes(res(200, { "content-length": "10", "content-encoding": "gzip" }), 0)).toBe(0);
  });
  it("answers 0 (= cannot check) when the server says nothing", () => {
    expect(declaredTotalBytes(res(200, {}), 0)).toBe(0);
  });
});

describe("edge/downloadManager - transfer engine failure handling", () => {
  let cacheStorage: ReturnType<typeof fakeCacheStorage>;
  let assetResponses: Array<() => unknown>;
  let assetRequests: Array<Record<string, string>>;

  async function loadManager() {
    const mod = await import("./downloadManager");
    return mod.downloadManager;
  }

  async function settle(manager: Awaited<ReturnType<typeof loadManager>>) {
    await vi.waitFor(() => {
      const state = manager.getAsset(ASSET_ID)?.state;
      expect(state === "ready" || state === "error").toBe(true);
    });
    return manager.getAsset(ASSET_ID)!;
  }

  beforeEach(() => {
    vi.resetModules();
    localStorage.clear();
    process.env.NEXT_PUBLIC_EDGE_AGENTS = "1";
    cacheStorage = fakeCacheStorage();
    (globalThis as any).caches = cacheStorage.api;
    assetResponses = [];
    assetRequests = [];
    (globalThis as any).fetch = vi.fn(async (url: string, init?: { headers?: Record<string, string> }) => {
      if (String(url).includes("/api/edge/manifest")) {
        return {
          ok: true,
          status: 200,
          headers: { get: (): string | null => null },
          json: async () => ({
            version: "1",
            models: [{ id: ASSET_ID, displayName: "Test asset", sizeBytes: 10, tier: 2, url: ASSET_URL, shardBytes: 4 }],
          }),
        };
      }
      if (url === ASSET_URL) {
        assetRequests.push(init?.headers || {});
        const next = assetResponses.shift();
        if (!next) throw new TypeError("Failed to fetch");
        return next();
      }
      return { ok: false, status: 404, headers: { get: (): string | null => null }, json: async () => ({}) };
    });
    // Instant backoff.
    vi.doMock("./resilience/retry", async () => {
      const actual = await vi.importActual<typeof import("./resilience/retry")>("./resilience/retry");
      return { ...actual, defaultSleep: async () => {} };
    });
  });

  afterEach(() => {
    delete (globalThis as any).caches;
    vi.doUnmock("./resilience/retry");
  });

  it("downloads, shards, and registers a complete asset", async () => {
    assetResponses.push(() => streamResponse(200, { "content-length": "10" }, [BYTES]));
    const manager = await loadManager();
    manager.enqueue({ id: ASSET_ID, displayName: "Test asset", totalBytes: 10 });
    const asset = await settle(manager);
    expect(asset.state).toBe("ready");
    expect(manager.installedVersion(ASSET_ID)).toBe("1");
    expect(cacheStorage.store.size).toBe(3); // 4 + 4 + 2
  });

  it("a dropped connection resumes with Range at the last whole shard and completes", async () => {
    assetResponses.push(() =>
      streamResponse(200, { "content-length": "10" }, [BYTES.slice(0, 6)], new TypeError("Failed to fetch"))
    );
    assetResponses.push(() =>
      streamResponse(206, { "content-range": "bytes 4-9/10", "content-length": "6" }, [BYTES.slice(4)])
    );
    const manager = await loadManager();
    manager.enqueue({ id: ASSET_ID, displayName: "Test asset", totalBytes: 10 });
    const asset = await settle(manager);
    expect(asset.state).toBe("ready");
    expect(assetRequests).toHaveLength(2);
    // Bytes 4-5 arrived but never filled a shard, so they are re-requested.
    expect(assetRequests[1].Range).toBe("bytes=4-");
    const first = await manager.getShard(ASSET_ID, 0);
    const last = await manager.getShard(ASSET_ID, 2);
    expect(Array.from(new Uint8Array(first!))).toEqual([1, 2, 3, 4]);
    expect(Array.from(new Uint8Array(last!))).toEqual([9, 10]);
  });

  it("a stream that keeps ending short is NEVER registered as installed", async () => {
    // Clean `done` at 6 of a declared 10 bytes, every time (a middlebox
    // closing the body early). Resumes ask for the rest and get cut again.
    assetResponses.push(() => streamResponse(200, { "content-length": "10" }, [BYTES.slice(0, 6)]));
    for (let i = 0; i < 3; i += 1) {
      assetResponses.push(() =>
        streamResponse(206, { "content-range": "bytes 4-9/10", "content-length": "6" }, [BYTES.slice(4, 6)])
      );
    }
    const manager = await loadManager();
    manager.enqueue({ id: ASSET_ID, displayName: "Test asset", totalBytes: 10 });
    const asset = await settle(manager);
    expect(asset).toMatchObject({ state: "error", failureReason: "incomplete" });
    expect(manager.installedVersion(ASSET_ID)).toBeNull();
    // Consumers read through the install registry, so the partial shard run
    // is invisible to them — it can be resumed, never loaded.
    expect(await manager.listInstalledAssets()).toEqual([]);
    expect(assetRequests).toHaveLength(4); // bounded
  });

  it("a blocked host (403) is retried a bounded number of times and typed host-unreachable", async () => {
    for (let i = 0; i < 4; i += 1) assetResponses.push(() => streamResponse(403, {}, []));
    const manager = await loadManager();
    manager.enqueue({ id: ASSET_ID, displayName: "Test asset", totalBytes: 10 });
    const asset = await settle(manager);
    expect(asset).toMatchObject({ state: "error", failureReason: "host-unreachable" });
    expect(assetRequests).toHaveLength(4);
    expect(manager.canRetry(ASSET_ID)).toBe(true);
  });

  it("a missing file (404) is not retried", async () => {
    assetResponses.push(() => streamResponse(404, {}, []));
    const manager = await loadManager();
    manager.enqueue({ id: ASSET_ID, displayName: "Test asset", totalBytes: 10 });
    const asset = await settle(manager);
    expect(asset).toMatchObject({ state: "error", failureReason: "model-unavailable" });
    expect(assetRequests).toHaveLength(1);
  });

  it("short storage is refused before any request, with a typed reason", async () => {
    Object.defineProperty(globalThis.navigator, "storage", {
      configurable: true,
      value: { estimate: async () => ({ usage: 95, quota: 100 }) },
    });
    try {
      const manager = await loadManager();
      manager.enqueue({ id: ASSET_ID, displayName: "Test asset", totalBytes: 10 });
      const asset = await settle(manager);
      expect(asset).toMatchObject({ state: "error", failureReason: "quota-too-small" });
      expect(assetRequests).toHaveLength(0);
    } finally {
      Object.defineProperty(globalThis.navigator, "storage", { configurable: true, value: undefined });
    }
  });

  it("an external row with no retry hook keeps its error instead of parking on 'queued'", async () => {
    const manager = await loadManager();
    manager.reportExternalProgress("kokoro", "Voice", { state: "error", error: "x", failureReason: "stalled" });
    expect(manager.canRetry("kokoro")).toBe(false);
    manager.resume("kokoro");
    expect(manager.getAsset("kokoro")).toMatchObject({ state: "error", failureReason: "stalled", external: true });
    const retry = vi.fn();
    manager.registerExternalRetry("kokoro", retry);
    expect(manager.canRetry("kokoro")).toBe(true);
    manager.resume("kokoro");
    expect(retry).toHaveBeenCalledTimes(1);
  });

  it("a drop that leaves navigator.onLine false waits for the link, then resumes with Range", async () => {
    let online = true;
    Object.defineProperty(globalThis.navigator, "onLine", { configurable: true, get: () => online });
    try {
      assetResponses.push(() => {
        online = false; // wifi roam: the body read rejects while the browser reports offline
        setTimeout(() => {
          online = true;
          window.dispatchEvent(new Event("online"));
        }, 20);
        return streamResponse(200, { "content-length": "10" }, [BYTES.slice(0, 4)], new TypeError("Failed to fetch"));
      });
      assetResponses.push(() =>
        streamResponse(206, { "content-range": "bytes 4-9/10", "content-length": "6" }, [BYTES.slice(4)])
      );
      const manager = await loadManager();
      manager.enqueue({ id: ASSET_ID, displayName: "Test asset", totalBytes: 10 });
      const asset = await settle(manager);
      expect(asset.state).toBe("ready"); // before: failed at once with "You're offline", no retry
      expect(assetRequests).toHaveLength(2);
    } finally {
      Object.defineProperty(globalThis.navigator, "onLine", { configurable: true, get: () => true });
    }
  });

  it("pause then resume while waiting for a transfer slot ends up downloading, not silently paused", async () => {
    const baseFetch = (globalThis as any).fetch;
    (globalThis as any).fetch = vi.fn((url: string, init?: { signal?: AbortSignal }) => {
      if (String(url).includes("/block-")) {
        // Holds its slot until aborted.
        return new Promise((_resolve, reject) => {
          init?.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
        });
      }
      return baseFetch(url, init);
    });
    assetResponses.push(() => streamResponse(200, { "content-length": "10" }, [BYTES]));
    const manager = await loadManager();
    manager.enqueue({ id: "block-1", displayName: "B1", totalBytes: 10, url: "https://models.example/block-1.bin" });
    manager.enqueue({ id: "block-2", displayName: "B2", totalBytes: 10, url: "https://models.example/block-2.bin" });
    await vi.waitFor(() => expect(manager.getAsset("block-2")?.state).toBe("downloading"));

    manager.enqueue({ id: ASSET_ID, displayName: "Test asset", totalBytes: 10 });
    // Let it reach the gate (both slots are taken, so it parks there).
    await new Promise((r) => setTimeout(r, 20));
    expect(assetRequests).toHaveLength(0);

    manager.pause(ASSET_ID);
    expect(manager.getAsset(ASSET_ID)?.state).toBe("paused");
    manager.resume(ASSET_ID);
    expect(manager.getAsset(ASSET_ID)?.state).toBe("queued");

    manager.cancel("block-1"); // frees a slot
    const asset = await settle(manager);
    expect(asset.state).toBe("ready");
    manager.cancel("block-2");
  });

  it("the manifest fetch is bounded: a server that never answers falls back instead of hanging", async () => {
    vi.useFakeTimers();
    try {
      (globalThis as any).fetch = vi.fn(
        (_url: string, init?: { signal?: AbortSignal }) =>
          new Promise((_resolve, reject) => {
            init?.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
          })
      );
      const manager = await loadManager();
      const pending = manager.fetchManifest();
      await vi.advanceTimersByTimeAsync(8_100);
      await expect(pending).resolves.toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });
});
