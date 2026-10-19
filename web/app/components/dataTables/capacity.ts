// How full the Data Tables allowance is. Pure (no React, no fetch), unit-tested in capacity.test.ts.
//
// Both inputs are exact: GET /api/data-tables returns every table of the workspace with its real row count
// (DataTableService.list - not paginated), and GET /api/data-tables/config carries the limits the server enforces
// (maxTables, maxRowsPerTable). So "3 of 50 tables" and "812 of 100,000 rows" are totals, not samples.
import { rate } from "../charts/chartMath";

/** "near" from this share of the limit upward (inclusive); "full" at the limit itself. */
export const NEAR_LIMIT_RATE = 0.8;

export type CapacityLevel = "ok" | "near" | "full" | "unknown";

export interface Capacity {
  used: number;
  max: number;
  /** 0..1, or null when there is no usable limit (0, negative, missing): then no meter is drawn. */
  rate: number | null;
  level: CapacityLevel;
}

export function capacity(used: number, max: number): Capacity {
  const safeUsed = Number.isFinite(used) && used > 0 ? used : 0;
  const share = rate(safeUsed, max);
  let level: CapacityLevel = "unknown";
  if (share !== null) {
    // Compared on the raw counts, not a rounded percent: 79.6% is not "near", and "full" needs the last row.
    if (safeUsed >= max) level = "full";
    else if (share >= NEAR_LIMIT_RATE) level = "near";
    else level = "ok";
  }
  return { used: safeUsed, max, rate: share, level };
}

export interface CapacityOverview {
  tables: Capacity;
  /** Tables whose row meter is "near" (full ones are counted separately). */
  nearCount: number;
  fullCount: number;
}

export function capacityOverview(
  rowCounts: readonly number[],
  limits: { maxTables: number; maxRowsPerTable: number }
): CapacityOverview {
  let nearCount = 0;
  let fullCount = 0;
  for (const rows of rowCounts) {
    const level = capacity(rows, limits.maxRowsPerTable).level;
    if (level === "near") nearCount += 1;
    else if (level === "full") fullCount += 1;
  }
  return { tables: capacity(rowCounts.length, limits.maxTables), nearCount, fullCount };
}
