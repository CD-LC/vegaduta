// Preflight: blockers stop a download before a byte moves, warnings never do,
// and an UNKNOWN input is never a blocker (2026-09-19).

import { describe, expect, it, vi } from "vitest";
import { evaluatePreflight, fitsInStorage, freeStorageBytes, type PreflightInput } from "./preflight";
import { hostOf, interpretProbeOutcome, probeModelHost } from "./preflightProbe";

const GB = 1_000_000_000;

function input(overrides: Partial<PreflightInput> = {}): PreflightInput {
  return {
    modelBytes: 2 * GB,
    edgeEnabled: true,
    requiresWebGpu: true,
    webgpuAvailable: true,
    wasmAvailable: true,
    cacheStorageAvailable: true,
    online: true,
    storage: { usageBytes: 1 * GB, quotaBytes: 50 * GB },
    persisted: true,
    connection: { saveData: false, effectiveType: "4g" },
    host: { name: "huggingface.co", status: "reachable" },
    ...overrides,
  };
}

describe("evaluatePreflight", () => {
  it("a healthy device passes with no blockers and no warnings", () => {
    const v = evaluatePreflight(input());
    expect(v.ok).toBe(true);
    expect(v.blockers).toEqual([]);
    expect(v.warnings).toEqual([]);
    expect(v.freeBytes).toBe(49 * GB);
  });

  it("no Cache API blocks only a download that will actually write to it", () => {
    const blocked = evaluatePreflight(input({ cacheStorageAvailable: false }));
    expect(blocked.blockers.map((b) => b.reason)).toEqual(["no-cache-storage"]);
    // Omitted = required (the safe default for every Cache-API-backed engine).
    expect(evaluatePreflight(input({ cacheStorageAvailable: false, requiresCacheStorage: true })).ok).toBe(false);
    // Tier 1 on the shared cross-origin store never touches this origin's Cache API.
    expect(evaluatePreflight(input({ cacheStorageAvailable: false, requiresCacheStorage: false })).ok).toBe(true);
  });

  it("quota too small: refused up front, with both numbers for the message", () => {
    const v = evaluatePreflight(input({ storage: { usageBytes: 9 * GB, quotaBytes: 10 * GB } }));
    expect(v.ok).toBe(false);
    expect(v.blockers).toEqual([{ reason: "quota-too-small", neededBytes: 2 * GB, freeBytes: 1 * GB }]);
  });

  it("the 1.2x headroom is applied: exactly-fits is NOT enough", () => {
    // 2.2 GB free for a 2 GB model: 2 * 1.2 = 2.4 needed.
    expect(evaluatePreflight(input({ storage: { usageBytes: 0, quotaBytes: 2.2 * GB } })).ok).toBe(false);
    expect(evaluatePreflight(input({ storage: { usageBytes: 0, quotaBytes: 2.4 * GB } })).ok).toBe(true);
  });

  it("an unknown storage estimate warns but never blocks (Safari/private modes)", () => {
    for (const storage of [null, { usageBytes: 0, quotaBytes: 0 }]) {
      const v = evaluatePreflight(input({ storage }));
      expect(v.ok).toBe(true);
      expect(v.warnings.map((w) => w.code)).toContain("storage-unknown");
      expect(v.freeBytes).toBeNull();
    }
  });

  it("host unreachable blocks and names the host", () => {
    const v = evaluatePreflight(input({ host: { name: "huggingface.co", status: "unreachable" } }));
    expect(v.ok).toBe(false);
    expect(v.blockers).toEqual([{ reason: "host-unreachable", host: "huggingface.co" }]);
  });

  it("a probe that could not tell does NOT block", () => {
    expect(evaluatePreflight(input({ host: { name: "huggingface.co", status: "unknown" } })).ok).toBe(true);
  });

  it("offline is reported as offline, not as an unreachable host", () => {
    const v = evaluatePreflight(
      input({ online: false, host: { name: "huggingface.co", status: "unreachable" } })
    );
    expect(v.blockers.map((b) => b.reason)).toEqual(["offline"]);
  });

  it("no WebGPU blocks a model that needs it, and only such a model", () => {
    const needs = evaluatePreflight(input({ webgpuAvailable: false }));
    expect(needs.blockers[0].reason).toBe("no-webgpu");
    const wasmTier = evaluatePreflight(input({ webgpuAvailable: false, requiresWebGpu: false }));
    expect(wasmTier.ok).toBe(true);
  });

  it("orders blockers most-fundamental first so the shown one is the real one", () => {
    const v = evaluatePreflight(
      input({
        webgpuAvailable: false,
        cacheStorageAvailable: false,
        storage: { usageBytes: 9 * GB, quotaBytes: 10 * GB },
        online: false,
      })
    );
    expect(v.blockers.map((b) => b.reason)).toEqual([
      "no-webgpu",
      "no-cache-storage",
      "quota-too-small",
      "offline",
    ]);
  });

  it("edge disabled and no wasm are blockers", () => {
    expect(evaluatePreflight(input({ edgeEnabled: false })).blockers[0].reason).toBe("edge-disabled");
    expect(evaluatePreflight(input({ wasmAvailable: false })).blockers[0].reason).toBe("no-wasm");
  });

  it("saveData / metered warn with the size and never block", () => {
    const saver = evaluatePreflight(input({ connection: { saveData: true, effectiveType: "4g" } }));
    expect(saver.ok).toBe(true);
    expect(saver.warnings[0].code).toBe("save-data");
    expect(saver.warnings[0].message).toContain("2.0 GB");
    const metered = evaluatePreflight(
      input({ connection: { saveData: false, effectiveType: null, metered: true } })
    );
    expect(metered.warnings.map((w) => w.code)).toContain("save-data");
  });

  it("a 2g connection warns", () => {
    const v = evaluatePreflight(input({ connection: { saveData: false, effectiveType: "2g" } }));
    expect(v.ok).toBe(true);
    expect(v.warnings.map((w) => w.code)).toContain("slow-connection");
  });

  it("non-persistent storage and tight space warn", () => {
    const v = evaluatePreflight(
      input({ persisted: false, storage: { usageBytes: 0, quotaBytes: 3 * GB } })
    );
    expect(v.ok).toBe(true);
    expect(v.warnings.map((w) => w.code).sort()).toEqual(["not-persisted", "tight-storage"]);
    // Unsupported persisted() (undefined) is not a warning.
    expect(evaluatePreflight(input({ persisted: undefined })).warnings).toEqual([]);
  });

  it("an unknown model size skips the storage check entirely", () => {
    const v = evaluatePreflight(input({ modelBytes: 0, storage: { usageBytes: 10 * GB, quotaBytes: 10 * GB } }));
    expect(v.ok).toBe(true);
  });
});

describe("storage helpers", () => {
  it("freeStorageBytes clamps and handles unknowns", () => {
    expect(freeStorageBytes(null)).toBeNull();
    expect(freeStorageBytes({ usageBytes: 5, quotaBytes: 0 })).toBeNull();
    expect(freeStorageBytes({ usageBytes: 20, quotaBytes: 10 })).toBe(0);
  });
  it("fitsInStorage is three-valued", () => {
    expect(fitsInStorage(100, null)).toBeNull();
    expect(fitsInStorage(100, { usageBytes: 0, quotaBytes: 1000 })).toBe(true);
    expect(fitsInStorage(100, { usageBytes: 950, quotaBytes: 1000 })).toBe(false);
  });
});

describe("host probe", () => {
  it("interprets outcomes: slow is unknown, errors and proxy blocks are unreachable", () => {
    expect(interpretProbeOutcome({ kind: "timeout" })).toBe("unknown");
    expect(interpretProbeOutcome({ kind: "error" })).toBe("unreachable");
    for (const status of [200, 302, 404, 429]) {
      expect(interpretProbeOutcome({ kind: "response", status })).toBe("reachable");
    }
    for (const status of [403, 407, 451, 500, 503]) {
      expect(interpretProbeOutcome({ kind: "response", status })).toBe("unreachable");
    }
  });

  it("probeModelHost: a rejecting fetch is unreachable, and it never throws", async () => {
    const failing = vi.fn(async () => {
      throw new TypeError("Failed to fetch");
    }) as unknown as typeof fetch;
    await expect(probeModelHost("https://huggingface.co/x", failing)).resolves.toBe("unreachable");
    const ok = vi.fn(async () => ({ status: 200 })) as unknown as typeof fetch;
    await expect(probeModelHost("https://huggingface.co/x", ok)).resolves.toBe("reachable");
    expect((ok as unknown as ReturnType<typeof vi.fn>).mock.calls[0][1]).toMatchObject({
      method: "HEAD",
      cache: "no-store",
    });
    await expect(probeModelHost("https://huggingface.co/x", null)).resolves.toBe("unknown");
    await expect(probeModelHost("", ok)).resolves.toBe("unknown");
  });

  it("probeModelHost: a fetch that never settles resolves 'unknown' at the timeout", async () => {
    vi.useFakeTimers();
    try {
      const hanging = vi.fn(() => new Promise(() => {})) as unknown as typeof fetch;
      const pending = probeModelHost("https://huggingface.co/x", hanging, 5_000);
      await vi.advanceTimersByTimeAsync(5_001);
      await expect(pending).resolves.toBe("unknown");
    } finally {
      vi.useRealTimers();
    }
  });

  it("hostOf tolerates junk", () => {
    expect(hostOf("https://huggingface.co/mlc-ai/x")).toBe("huggingface.co");
    expect(hostOf("not a url")).toBe("not a url");
  });
});
