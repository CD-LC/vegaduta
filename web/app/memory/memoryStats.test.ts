import { describe, expect, it } from "vitest";
import type { MemoryFact } from "./memoryTypes";
import { factLabel, memoryStats } from "./memoryStats";

function fact(over: Partial<MemoryFact>): MemoryFact {
  return { id: "f1", scope: "agent", content: "likes tea", createdAt: "2026-09-01T00:00:00Z", lastUsedAt: "2026-09-01T00:00:00Z", useCount: 0, ...over };
}

describe("memoryStats", () => {
  it("is empty and claims nothing for no facts", () => {
    expect(memoryStats([])).toEqual({ total: 0, agent: 0, shared: 0, usageKnown: false, neverUsed: 0, used: [], newest: null, oldest: null });
    expect(memoryStats(null).total).toBe(0);
  });

  it("splits by scope so the two groups add up to the total", () => {
    const stats = memoryStats([fact({ id: "a" }), fact({ id: "b", scope: "shared" }), fact({ id: "c", scope: "shared" })]);
    expect([stats.total, stats.agent, stats.shared]).toEqual([3, 1, 2]);
  });

  it("counts a fact as never used by its use count, not by lastUsedAt (the server always sets that)", () => {
    const stats = memoryStats([
      fact({ id: "a", useCount: 0, lastUsedAt: "2026-09-01T00:00:00Z" }),
      fact({ id: "b", useCount: 3, lastUsedAt: "2026-09-20T00:00:00Z" }),
    ]);
    expect(stats.usageKnown).toBe(true);
    expect(stats.neverUsed).toBe(1);
    expect(stats.used.map((f) => f.id)).toEqual(["b"]);
  });

  it("ranks used facts most used first, newest first on a tie", () => {
    const stats = memoryStats([
      fact({ id: "low", useCount: 1 }),
      fact({ id: "tie-old", useCount: 5, createdAt: "2026-08-01T00:00:00Z" }),
      fact({ id: "tie-new", useCount: 5, createdAt: "2026-09-15T00:00:00Z" }),
      fact({ id: "top", useCount: 9 }),
      fact({ id: "never", useCount: 0 }),
    ]);
    expect(stats.used.map((f) => f.id)).toEqual(["top", "tie-new", "tie-old", "low"]);
  });

  it("does not call anything 'never used' when an older core sends no use counts", () => {
    const stats = memoryStats([fact({ id: "a", useCount: undefined }), fact({ id: "b", useCount: undefined })]);
    expect(stats.usageKnown).toBe(false);
    expect(stats.neverUsed).toBe(0);
    expect(stats.used).toEqual([]);
  });

  it("finds the newest and oldest dates and ignores unreadable ones", () => {
    const stats = memoryStats([
      fact({ id: "a", createdAt: "2026-09-10T00:00:00Z" }),
      fact({ id: "b", createdAt: "2026-07-02T00:00:00Z" }),
      fact({ id: "c", createdAt: "2026-10-01T12:00:00Z" }),
      fact({ id: "d", createdAt: "garbage" }),
      fact({ id: "e", createdAt: null }),
    ]);
    expect(stats.newest).toBe("2026-10-01T12:00:00Z");
    expect(stats.oldest).toBe("2026-07-02T00:00:00Z");
    expect(memoryStats([fact({ createdAt: null })]).newest).toBeNull();
  });
});

describe("factLabel", () => {
  it("collapses whitespace and leaves a short fact alone", () => {
    expect(factLabel("  likes\n  green   tea ")).toBe("likes green tea");
    expect(factLabel(null)).toBe("");
  });

  it("cuts a long fact to the limit with an ellipsis", () => {
    const label = factLabel("x".repeat(200), 20);
    expect(label).toHaveLength(20);
    expect(label.endsWith("...")).toBe(true);
  });
});
