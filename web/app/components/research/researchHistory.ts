// Client-side helpers for the research history list: search, status filter,
// pinning (kept in this browser only). Pure except the two storage functions,
// which swallow every storage failure (private windows, blocked site data).

import { isLiveStatus } from "../jobs/jobStatus";
import { questionFromGoal } from "./researchRun";

export type HistoryFilter = "all" | "running" | "done" | "issues";

export interface HistoryRow {
  id: string;
  status?: string | null;
  goalPreview?: string | null;
}

export function matchesFilter(status: string | null | undefined, filter: HistoryFilter): boolean {
  switch (filter) {
    case "running":
      return isLiveStatus(status);
    case "done":
      return status === "SUCCEEDED";
    case "issues":
      return status === "FAILED" || status === "CANCELLED";
    default:
      return true;
  }
}

/** Search (case-insensitive, every word must appear) + status filter, then pinned runs first with order otherwise kept. */
export function filterHistory<T extends HistoryRow>(
  rows: ReadonlyArray<T>,
  opts: { query?: string; filter?: HistoryFilter; pinned?: ReadonlySet<string> }
): T[] {
  const words = (opts.query ?? "").toLowerCase().split(/\s+/).filter(Boolean);
  const kept = rows.filter((r) => {
    if (!matchesFilter(r.status, opts.filter ?? "all")) return false;
    if (words.length === 0) return true;
    const hay = questionFromGoal(r.goalPreview).toLowerCase();
    return words.every((w) => hay.includes(w));
  });
  const pinned = opts.pinned;
  if (!pinned || pinned.size === 0) return kept;
  return [...kept.filter((r) => pinned.has(r.id)), ...kept.filter((r) => !pinned.has(r.id))];
}

const PIN_KEY = "vd.research.pins.v1";
const MAX_PINS = 50;

export function parsePins(raw: string | null | undefined): string[] {
  if (!raw) return [];
  try {
    const v = JSON.parse(raw);
    return Array.isArray(v) ? v.filter((x): x is string => typeof x === "string").slice(0, MAX_PINS) : [];
  } catch {
    return [];
  }
}

export function togglePin(pins: ReadonlyArray<string>, id: string): string[] {
  return pins.includes(id) ? pins.filter((p) => p !== id) : [id, ...pins].slice(0, MAX_PINS);
}

export function loadPins(): string[] {
  try {
    return parsePins(window.localStorage.getItem(PIN_KEY));
  } catch {
    return [];
  }
}

export function savePins(pins: ReadonlyArray<string>): void {
  try {
    window.localStorage.setItem(PIN_KEY, JSON.stringify(pins));
  } catch {
    // Pins are a convenience; losing them is fine.
  }
}
