import type { SettingCatalogEntry } from "../../lib/api/settings";

/** Where a setting can be changed from. Workspace: the Runtime settings page; agent: the agent's Behavior tab. */
export function settingLinks(entry: Pick<SettingCatalogEntry, "key" | "scopes">): { workspace?: string; agent?: string } {
  const out: { workspace?: string; agent?: string } = {};
  if (entry.scopes.includes("tenant")) out.workspace = `/admin/runtime-settings#${encodeURIComponent(entry.key)}`;
  if (entry.scopes.includes("agent")) out.agent = `/agents?section=behavior&focus=${encodeURIComponent(entry.key)}`;
  return out;
}

export const OTHER_ANCHOR = "other";

export interface AnchorSection<T> {
  anchor: string;
  items: T[];
}

/** Entries grouped under their docsAnchor, sections in first-seen order; entries without one go last under "other". */
export function sectionsByAnchor<T extends { docsAnchor?: string | null }>(entries: T[]): AnchorSection<T>[] {
  const order: string[] = [];
  const map = new Map<string, T[]>();
  for (const e of entries) {
    const a = e.docsAnchor?.trim() || OTHER_ANCHOR;
    if (!map.has(a)) {
      map.set(a, []);
      order.push(a);
    }
    map.get(a)!.push(e);
  }
  order.sort((x, y) => Number(x === OTHER_ANCHOR) - Number(y === OTHER_ANCHOR));
  return order.map((anchor) => ({ anchor, items: map.get(anchor)! }));
}

/** Case-insensitive match of every word of the query against the given haystacks. */
export function matchesQuery(query: string, ...haystacks: Array<string | undefined>): boolean {
  const words = query.toLowerCase().split(/\s+/).filter(Boolean);
  if (words.length === 0) return true;
  const hay = haystacks.filter(Boolean).join(" \n ").toLowerCase();
  return words.every((w) => hay.includes(w));
}
