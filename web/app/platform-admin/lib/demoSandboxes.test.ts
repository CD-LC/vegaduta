import { describe, it, expect } from "vitest";
import {
  RECLAIM_BATCH_LIMIT,
  normalizeInventory,
  reclaimBatch,
  reclaimSummary,
  type DemoSandboxInventory,
} from "./demoSandboxes";

describe("normalizeInventory", () => {
  it("passes a well-formed body through", () => {
    const raw: DemoSandboxInventory = {
      shared: { id: "s", name: "Demo", schemaName: "tenant_demo" },
      reclaimable: [
        {
          id: "a",
          name: "a",
          schemaName: "x",
          status: "ACTIVE",
          createdAt: "2026-01-01T00:00:00Z",
          lastSessionAt: null,
        },
      ],
      total: 230,
    };
    expect(normalizeInventory(raw)).toEqual(raw);
  });

  it("tolerates a missing shared tenant and missing total", () => {
    expect(normalizeInventory({ shared: null, reclaimable: [] })).toEqual({ shared: null, reclaimable: [], total: 0 });
    expect(normalizeInventory(null)).toEqual({ shared: null, reclaimable: [], total: 0 });
  });
});

describe("reclaimBatch", () => {
  it("never includes the shared tenant and caps at the server batch limit", () => {
    const ids = Array.from({ length: 80 }, (_, i) => `id-${i}`);
    const batch = reclaimBatch(["shared", ...ids], "shared");
    expect(batch).not.toContain("shared");
    expect(batch).toHaveLength(RECLAIM_BATCH_LIMIT);
    expect(batch[0]).toBe("id-0");
  });

  it("empty selection stays empty (meaning: server picks the next batch)", () => {
    expect(reclaimBatch([], "shared")).toEqual([]);
  });
});

describe("reclaimSummary", () => {
  it("reports erased, failed and remaining", () => {
    expect(reclaimSummary({ erased: ["a", "b"], failed: [], remaining: 10 })).toBe("2 erased · 10 remaining");
    expect(reclaimSummary({ erased: [], failed: [{ id: "c", name: "c", error: "x" }], remaining: 1 })).toBe(
      "0 erased · 1 failed · 1 remaining"
    );
  });
});
