import { describe, expect, it } from "vitest";
import { toolCardTotals } from "./helpers";

describe("toolCardTotals", () => {
  const catalog = [{ name: "a" }, { name: "b" }, { name: "c" }];

  it("counts one connect card for Google when it is not connected", () => {
    const r = toolCardTotals({ builtInCount: 3, googleToolCount: 4, googleConnected: false, catalog, servers: [] });
    expect(r).toEqual({ total: 3 + 1 + 3, enabled: 3 });
  });

  it("counts the four Google tools when connected", () => {
    const r = toolCardTotals({ builtInCount: 3, googleToolCount: 4, googleConnected: true, catalog, servers: [] });
    expect(r).toEqual({ total: 3 + 4 + 3, enabled: 3 + 4 });
  });

  it("adds custom servers once and counts enabled ones", () => {
    const servers = [
      { name: "a", enabled: true }, // matches a catalog entry: no extra card
      { name: "mine", enabled: true }, // custom card
      { name: "other", enabled: false },
    ];
    const r = toolCardTotals({ builtInCount: 2, googleToolCount: 4, googleConnected: false, catalog, servers });
    expect(r.total).toBe(2 + 1 + 3 + 2);
    expect(r.enabled).toBe(2 + 2);
  });
});
