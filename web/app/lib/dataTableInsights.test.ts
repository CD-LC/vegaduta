import { describe, expect, it } from "vitest";
import { barHeights, columnStats, formatStat, HISTOGRAM_BINS } from "./dataTableInsights";
import type { DataTableRow } from "./dataTables";

const rows = (col: string, values: unknown[]): DataTableRow[] =>
  values.map((v, i) => ({ id: String(i), data: v === undefined ? {} : { [col]: v }, createdAt: "", updatedAt: "" }));

describe("columnStats", () => {
  it("summarises a number column with a histogram", () => {
    const s = columnStats({ name: "n", type: "number" }, rows("n", [1, 2, 3, 4, 10, undefined]));
    expect(s).toMatchObject({ filled: 5, empty: 1, distinct: 5, min: 1, max: 10, sum: 20, mean: 4 });
    expect(s.histogram).toHaveLength(HISTOGRAM_BINS);
    expect(s.histogram!.reduce((a, b) => a + b, 0)).toBe(5);
    expect(s.histogram![HISTOGRAM_BINS - 1]).toBe(1);
  });

  it("has no histogram when every value is the same", () => {
    const s = columnStats({ name: "n", type: "number" }, rows("n", [5, 5]));
    expect(s.histogram).toBeUndefined();
    expect(s.min).toBe(5);
  });

  it("counts booleans", () => {
    const s = columnStats({ name: "b", type: "boolean" }, rows("b", [true, false, true, undefined]));
    expect([s.trueCount, s.falseCount, s.empty]).toEqual([2, 1, 1]);
  });

  it("finds a date range across date-only and date-time values", () => {
    const s = columnStats({ name: "d", type: "date" }, rows("d", ["2026-03-05", "2026-01-02", "2026-03-05T10:00:00Z"]));
    expect(s.earliest).toBe("2026-01-02");
    expect(s.latest).toBe("2026-03-05T10:00:00Z");
  });

  it("ranks the most common text values, ties by name", () => {
    const s = columnStats({ name: "t", type: "text" }, rows("t", ["b", "a", "b", "c", "a", "b"]));
    expect(s.top).toEqual([
      { value: "b", count: 3 },
      { value: "a", count: 2 },
      { value: "c", count: 1 },
    ]);
    expect(s.distinct).toBe(3);
  });

  it("treats empty text as empty and handles no rows", () => {
    expect(columnStats({ name: "t", type: "text" }, rows("t", ["", null])).filled).toBe(0);
    expect(columnStats({ name: "n", type: "number" }, []).filled).toBe(0);
  });
});

describe("formatting", () => {
  it("formats stats", () => {
    expect(formatStat(1234.5678)).toBe("1,234.57");
    expect(formatStat(undefined)).toBe("-");
    expect(formatStat(Number.NaN)).toBe("-");
  });

  it("scales bars to the tallest", () => {
    expect(barHeights([1, 2, 4])).toEqual([25, 50, 100]);
    expect(barHeights([0, 0])).toEqual([0, 0]);
  });
});
