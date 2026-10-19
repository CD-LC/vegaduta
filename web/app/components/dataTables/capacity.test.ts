import { describe, expect, it } from "vitest";
import { capacity, capacityOverview } from "./capacity";

describe("capacity", () => {
  it("is ok below 80% of the limit", () => {
    expect(capacity(0, 100)).toEqual({ used: 0, max: 100, rate: 0, level: "ok" });
    expect(capacity(79, 100).level).toBe("ok");
    // 79.6% rounds to 80 but is not there yet.
    expect(capacity(796, 1000).level).toBe("ok");
  });

  it("is near from exactly 80% and full only at the limit", () => {
    expect(capacity(80, 100).level).toBe("near");
    expect(capacity(99, 100).level).toBe("near");
    // 99.999% rounds to 100 but one row still fits.
    expect(capacity(99_999, 100_000).level).toBe("near");
    expect(capacity(100, 100).level).toBe("full");
  });

  it("stays full, with the rate clamped, when the count is over the limit", () => {
    expect(capacity(150, 100)).toEqual({ used: 150, max: 100, rate: 1, level: "full" });
  });

  it("has no rate when there is no usable limit", () => {
    expect(capacity(5, 0)).toEqual({ used: 5, max: 0, rate: null, level: "unknown" });
    expect(capacity(5, -1).level).toBe("unknown");
    expect(capacity(5, Number.NaN).rate).toBeNull();
  });

  it("treats a bad count as zero rather than drawing NaN", () => {
    expect(capacity(Number.NaN, 100)).toEqual({ used: 0, max: 100, rate: 0, level: "ok" });
    expect(capacity(-3, 100).used).toBe(0);
  });
});

describe("capacityOverview", () => {
  it("counts tables against maxTables and flags near and full tables separately", () => {
    const overview = capacityOverview([10, 80, 95, 100], { maxTables: 5, maxRowsPerTable: 100 });
    expect(overview.tables).toEqual({ used: 4, max: 5, rate: 0.8, level: "near" });
    expect(overview.nearCount).toBe(2);
    expect(overview.fullCount).toBe(1);
  });

  it("flags nothing when the row limit is unknown", () => {
    const overview = capacityOverview([10, 1_000_000], { maxTables: 0, maxRowsPerTable: 0 });
    expect(overview.tables.rate).toBeNull();
    expect(overview.nearCount + overview.fullCount).toBe(0);
  });
});
