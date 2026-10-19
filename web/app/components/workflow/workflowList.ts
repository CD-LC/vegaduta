// Pure helpers for the workflows list (status chips, sort, text filter): no React, no clock.

export type WorkflowSort = "updated" | "name" | "largest";

export const WORKFLOW_SORTS: { value: WorkflowSort; label: string }[] = [
  { value: "updated", label: "Recently updated" },
  { value: "name", label: "Name A to Z" },
  { value: "largest", label: "Most steps" },
];

type Item = {
  name: string;
  description?: string;
  status: string;
  updatedAt: string;
  graph?: { nodes?: unknown[] };
};

/** Distinct statuses with counts, ACTIVE first then alphabetical, so the chips read the same every visit. */
export function statusCounts(items: readonly Item[]): { status: string; count: number }[] {
  const map = new Map<string, number>();
  for (const w of items) map.set(w.status, (map.get(w.status) ?? 0) + 1);
  return [...map.entries()]
    .map(([status, count]) => ({ status, count }))
    .sort((a, b) => (a.status === "ACTIVE" ? -1 : b.status === "ACTIVE" ? 1 : a.status.localeCompare(b.status)));
}

/** `status` of "" means any status. */
export function filterWorkflows<T extends Item>(items: readonly T[], status: string, query: string): T[] {
  const q = query.trim().toLowerCase();
  return items.filter(
    (w) =>
      (!status || w.status === status) &&
      (!q || (w.name || "").toLowerCase().includes(q) || (w.description || "").toLowerCase().includes(q))
  );
}

function time(iso: string): number {
  const n = Date.parse(iso);
  return Number.isNaN(n) ? 0 : n;
}

/** A sorted copy; ties keep the server's order. */
export function sortWorkflows<T extends Item>(items: readonly T[], sort: WorkflowSort): T[] {
  const list = [...items];
  const size = (w: T) => w.graph?.nodes?.length ?? 0;
  if (sort === "name") return list.sort((a, b) => (a.name || "").localeCompare(b.name || "", undefined, { sensitivity: "base" }));
  if (sort === "largest") return list.sort((a, b) => size(b) - size(a));
  return list.sort((a, b) => time(b.updatedAt) - time(a.updatedAt));
}

export function workflowLink(origin: string, id: string): string {
  return `${origin.replace(/\/+$/, "")}/workflows/${encodeURIComponent(id)}`;
}
