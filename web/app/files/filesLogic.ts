// Pure helpers for the Files page: no React, no clock, unit-testable on their own.

export type RowKind = "knowledge-document" | "image" | "elevenlabs-audio" | "sarvam-audio" | "video" | "document";

export type FileGroup = "all" | "image" | "audio" | "video" | "document" | "knowledge";
export type FileSort = "newest" | "oldest" | "name" | "type";

export const GROUPS: { id: FileGroup; label: string }[] = [
  { id: "all", label: "All" },
  { id: "image", label: "Images" },
  { id: "audio", label: "Audio" },
  { id: "video", label: "Video" },
  { id: "document", label: "Documents" },
  { id: "knowledge", label: "Knowledge" },
];

export const SORTS: { value: FileSort; label: string }[] = [
  { value: "newest", label: "Newest first" },
  { value: "oldest", label: "Oldest first" },
  { value: "name", label: "Name A to Z" },
  { value: "type", label: "Type" },
];

type Sortable = {
  kind: RowKind;
  name: string;
  detail?: string;
  collectionName?: string;
  createdAt: string;
};

export function groupOf(kind: RowKind): Exclude<FileGroup, "all"> {
  switch (kind) {
    case "image":
      return "image";
    case "elevenlabs-audio":
    case "sarvam-audio":
      return "audio";
    case "video":
      return "video";
    case "document":
      return "document";
    default:
      return "knowledge";
  }
}

export function countByGroup(rows: readonly Sortable[]): Record<FileGroup, number> {
  const counts: Record<FileGroup, number> = { all: rows.length, image: 0, audio: 0, video: 0, document: 0, knowledge: 0 };
  for (const r of rows) counts[groupOf(r.kind)]++;
  return counts;
}

export function filterRows<T extends Sortable>(rows: readonly T[], group: FileGroup, query: string): T[] {
  const q = query.trim().toLowerCase();
  return rows.filter((r) => {
    if (group !== "all" && groupOf(r.kind) !== group) return false;
    if (!q) return true;
    return (
      (r.name || "").toLowerCase().includes(q) ||
      (r.detail || "").toLowerCase().includes(q) ||
      (r.collectionName || "").toLowerCase().includes(q)
    );
  });
}

function time(iso: string): number {
  const n = Date.parse(iso);
  return Number.isNaN(n) ? 0 : n;
}

/** A sorted copy; undated rows sort as oldest. */
export function sortRows<T extends Sortable>(rows: readonly T[], sort: FileSort): T[] {
  const list = [...rows];
  switch (sort) {
    case "newest":
      return list.sort((a, b) => time(b.createdAt) - time(a.createdAt));
    case "oldest":
      return list.sort((a, b) => time(a.createdAt) - time(b.createdAt));
    case "name":
      return list.sort((a, b) => (a.name || "").localeCompare(b.name || "", undefined, { sensitivity: "base" }));
    case "type":
      return list.sort(
        (a, b) => groupOf(a.kind).localeCompare(groupOf(b.kind)) || time(b.createdAt) - time(a.createdAt)
      );
  }
}

export function rowKey(row: { kind: RowKind; id: unknown }): string {
  return `${row.kind}-${row.id}`;
}

/** Previewable in the browser without a new endpoint: the same blob the download uses. */
export function previewKind(kind: RowKind): "image" | "audio" | "video" | null {
  const g = groupOf(kind);
  return g === "image" || g === "audio" || g === "video" ? g : null;
}
