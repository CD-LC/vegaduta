// Pure helpers for the Knowledge panel's file list and search history: no React, no clock.

export type DocStatusGroup = "all" | "ready" | "processing" | "failed";
export type DocSort = "newest" | "name" | "chunks";

export const DOC_STATUS_GROUPS: { id: DocStatusGroup; label: string }[] = [
  { id: "all", label: "All" },
  { id: "ready", label: "Ready" },
  { id: "processing", label: "Processing" },
  { id: "failed", label: "Failed" },
];

export const DOC_SORTS: { value: DocSort; label: string }[] = [
  { value: "newest", label: "Newest first" },
  { value: "name", label: "Name A to Z" },
  { value: "chunks", label: "Most chunks" },
];

type Doc = { filename: string; status: string; chunkCount?: number | null; createdAt?: string | null };

export function statusGroup(status: string): Exclude<DocStatusGroup, "all"> {
  if (status === "READY") return "ready";
  if (status === "FAILED") return "failed";
  return "processing";
}

export function countStatusGroups(docs: readonly Doc[]): Record<DocStatusGroup, number> {
  const c: Record<DocStatusGroup, number> = { all: docs.length, ready: 0, processing: 0, failed: 0 };
  for (const d of docs) c[statusGroup(d.status)]++;
  return c;
}

export function filterDocs<T extends Doc>(docs: readonly T[], group: DocStatusGroup, query: string): T[] {
  const q = query.trim().toLowerCase();
  return docs.filter(
    (d) => (group === "all" || statusGroup(d.status) === group) && (!q || (d.filename || "").toLowerCase().includes(q))
  );
}

function time(iso: string | null | undefined): number {
  const n = iso ? Date.parse(iso) : NaN;
  return Number.isNaN(n) ? 0 : n;
}

/** A sorted copy. "newest" keeps the server's order when dates are missing or equal (stable sort). */
export function sortDocs<T extends Doc>(docs: readonly T[], sort: DocSort): T[] {
  const list = [...docs];
  if (sort === "name") return list.sort((a, b) => (a.filename || "").localeCompare(b.filename || "", undefined, { sensitivity: "base" }));
  if (sort === "chunks") return list.sort((a, b) => (b.chunkCount ?? 0) - (a.chunkCount ?? 0));
  return list.sort((a, b) => time(b.createdAt) - time(a.createdAt));
}

export const MAX_RECENT_SEARCHES = 5;

/** Most-recent-first, de-duplicated case-insensitively, capped. Blank queries are ignored. */
export function pushRecentSearch(list: readonly string[], query: string): string[] {
  const q = query.trim().replace(/\s+/g, " ");
  if (!q) return [...list];
  const rest = list.filter((x) => x.toLowerCase() !== q.toLowerCase());
  return [q, ...rest].slice(0, MAX_RECENT_SEARCHES);
}

/** Tolerant parse of the stored value: anything unexpected becomes an empty history. */
export function parseRecentSearches(raw: string | null): string[] {
  if (!raw) return [];
  try {
    const v = JSON.parse(raw);
    return Array.isArray(v) ? v.filter((x) => typeof x === "string" && x.trim()).slice(0, MAX_RECENT_SEARCHES) : [];
  } catch {
    return [];
  }
}

/** Matched chunks as one paste-ready block, each tagged with its source. */
export function chunksAsContext(chunks: readonly { content: string; source: string }[]): string {
  return chunks.map((c, i) => `[${i + 1}] ${c.source}\n${c.content.trim()}`).join("\n\n");
}
