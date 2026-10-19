import type { Lead } from "../../lib/leads";

/** Rows fetched per "Show more" click on a board column (server max page size is 200). */
export const COLUMN_PAGE_SIZE = 100;

/** Server page index that holds the next unseen rows of a column that already shows `loaded` leads. */
export function nextColumnPage(loaded: number, pageSize: number = COLUMN_PAGE_SIZE): number {
  return Math.floor(Math.max(0, loaded) / pageSize);
}

/** Appends rows to a lead list, skipping ids already present (a column page can overlap what is loaded). */
export function mergeLeadsById(existing: Lead[], incoming: Lead[]): Lead[] {
  const seen = new Set(existing.map((l) => l.id));
  const added = incoming.filter((l) => !seen.has(l.id));
  return added.length === 0 ? existing : [...existing, ...added];
}

/** Leads the stage total says exist but the board has not loaded (never negative). */
export function remainingInColumn(stageCount: number, shown: number): number {
  return Math.max(0, stageCount - shown);
}

/** True when the board shows fewer leads than the pipeline holds: header counts then disagree with the cards. */
export function boardIsTruncated(allCount: number, loadedCount: number): boolean {
  return allCount > loadedCount;
}
