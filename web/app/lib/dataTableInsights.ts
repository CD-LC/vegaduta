// Per-column statistics for the Data Tables "Insights" panel, computed in the browser from rows already fetched
// through GET /api/data-tables/{id}/rows (the API has no aggregate endpoint). Pure, so unit-tested
// (dataTableInsights.test.ts). The caller says how many rows the sample covers; nothing here pretends to be exact
// for a table larger than the sample.

import type { DataTableColumn, DataTableColumnType, DataTableRow } from "./dataTables";

export const HISTOGRAM_BINS = 8;
export const TOP_VALUES = 5;

export interface TopValue {
  value: string;
  count: number;
}

export interface ColumnStats {
  name: string;
  type: DataTableColumnType;
  filled: number;
  empty: number;
  distinct: number;
  // number
  min?: number;
  max?: number;
  sum?: number;
  mean?: number;
  /** Row counts per equal-width bin between min and max (number columns with at least two distinct values). */
  histogram?: number[];
  // boolean
  trueCount?: number;
  falseCount?: number;
  // date
  earliest?: string;
  latest?: string;
  // text, boolean, date, json: the most common values
  top?: TopValue[];
}

function isEmptyCell(value: unknown): boolean {
  return value === null || value === undefined || value === "";
}

function keyOf(value: unknown): string {
  return typeof value === "string" ? value : JSON.stringify(value);
}

function histogramOf(values: number[], min: number, max: number): number[] {
  const bins = new Array<number>(HISTOGRAM_BINS).fill(0);
  const span = max - min;
  for (const v of values) {
    const i = Math.min(HISTOGRAM_BINS - 1, Math.floor(((v - min) / span) * HISTOGRAM_BINS));
    bins[i]++;
  }
  return bins;
}

export function columnStats(column: DataTableColumn, rows: DataTableRow[]): ColumnStats {
  const cells = rows.map((r) => r.data[column.name]);
  const filledCells = cells.filter((c) => !isEmptyCell(c));
  const counts = new Map<string, number>();
  for (const c of filledCells) {
    const k = keyOf(c);
    counts.set(k, (counts.get(k) ?? 0) + 1);
  }
  const stats: ColumnStats = {
    name: column.name,
    type: column.type,
    filled: filledCells.length,
    empty: cells.length - filledCells.length,
    distinct: counts.size,
  };

  if (column.type === "number") {
    const nums = filledCells.map(Number).filter((n) => Number.isFinite(n));
    if (nums.length > 0) {
      const min = Math.min(...nums);
      const max = Math.max(...nums);
      const sum = nums.reduce((a, b) => a + b, 0);
      stats.min = min;
      stats.max = max;
      stats.sum = sum;
      stats.mean = sum / nums.length;
      if (max > min) stats.histogram = histogramOf(nums, min, max);
    }
  } else if (column.type === "boolean") {
    stats.trueCount = filledCells.filter((c) => c === true).length;
    stats.falseCount = filledCells.filter((c) => c === false).length;
  } else if (column.type === "date") {
    const dated = filledCells
      .map((c) => ({ text: String(c), time: Date.parse(String(c)) }))
      .filter((d) => !Number.isNaN(d.time))
      .sort((a, b) => a.time - b.time);
    if (dated.length > 0) {
      stats.earliest = dated[0].text;
      stats.latest = dated[dated.length - 1].text;
    }
  }

  if (column.type !== "number" && column.type !== "boolean") {
    stats.top = [...counts.entries()]
      .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
      .slice(0, TOP_VALUES)
      .map(([value, count]) => ({ value, count }));
  }
  return stats;
}

export function tableStats(columns: DataTableColumn[], rows: DataTableRow[]): ColumnStats[] {
  return columns.map((c) => columnStats(c, rows));
}

/** Compact display of a statistic: integers as is, others to at most 2 decimals, large values with separators. */
export function formatStat(n: number | undefined): string {
  if (n === undefined || !Number.isFinite(n)) return "-";
  return n.toLocaleString("en-US", { maximumFractionDigits: 2 });
}

/** Bar heights (0-100) for a histogram, tallest = 100; an all-zero histogram gives zeros. */
export function barHeights(histogram: number[]): number[] {
  const peak = Math.max(0, ...histogram);
  return histogram.map((n) => (peak === 0 ? 0 : Math.round((n / peak) * 100)));
}
