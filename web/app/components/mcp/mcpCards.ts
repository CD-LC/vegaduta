import { BUILT_IN_TOOLS, GOOGLE_TOOLS } from "../McpPanel/constants";
import type { McpCard } from "../McpPanel/helpers";

/* eslint-disable @typescript-eslint/no-explicit-any */
// Real card inventory: built-ins (always "available"), the four Google
// Workspace tools (only if actually connected - see GoogleIntegrationPanel's
// javadoc), and one card per curated catalog entry - EVERY major MCP
// server the platform knows about, listed directly, not hidden behind a
// separate "Add" panel someone has to discover first. If this tenant has
// already registered a catalog entry, its card shows the real,
// live registration (kind "mcp" - enabled toggle, connection status,
// everything the inspector already supported); otherwise it shows as
// "not added yet" (kind "catalog") with a one-click Activate or a
// Connect-now form right there in the inspector. A catalog entry's
// selKey (`mcp:<catalogKey>`) is deliberately the SAME before and after
// it's registered, so selecting a card, activating it, and staying
// selected on the now-live version is one continuous action instead of
// losing your place. Any registered server that doesn't match a catalog
// entry (added via the "Register a custom server" form) gets its own
// "custom" card keyed by its real id.
export function buildMcpCards(servers: any[], catalog: any[], googleConnection: any): McpCard[] {
  const list: McpCard[] = [];
  BUILT_IN_TOOLS.forEach((bt) =>
    list.push({ selKey: `builtin:${bt.key}`, category: "builtin", kind: "builtin", data: bt })
  );
  if (googleConnection) {
    GOOGLE_TOOLS.forEach((g) =>
      list.push({ selKey: `google:${g.toolName}`, category: "google", kind: "google", data: g })
    );
  } else {
    list.push({ selKey: "google:connect", category: "google", kind: "google-connect", data: null });
  }
  const registeredByName = new Map(servers.map((s: any) => [s.name, s]));
  catalog.forEach((entry: any) => {
    const server = registeredByName.get(entry.name);
    list.push({
      selKey: `mcp:${entry.key}`,
      category: "mcp",
      kind: server ? "mcp" : "catalog",
      data: server || entry,
      catalogEntry: entry,
    });
  });
  servers
    .filter((s: any) => !catalog.some((c: any) => c.name === s.name))
    .forEach((s: any) =>
      list.push({ selKey: `custom:${s.id}`, category: "custom", kind: "mcp", data: s, catalogEntry: null })
    );
  return list;
}

// QA-07 (2026-09-24): one heading PER ORIGIN instead of a single
// "MCP servers · N available" label sitting over built-in and Google
// Workspace cards too. `cards` is assembled category by category, so the
// runs on a page are contiguous and a heading per run is a heading per
// origin. Counts are the filtered totals (not just this page's slice).
export const GROUP_LABEL_KEYS: Record<string, string> = {
  builtin: "tabBuiltin",
  google: "googleWorkspace",
  mcp: "tabMcpServers",
  custom: "tabCustom",
};

/** How many of the filtered cards fall in each category. */
export function countByCategory(cards: McpCard[]): Record<string, number> {
  const totals: Record<string, number> = {};
  cards.forEach((c) => {
    totals[c.category] = (totals[c.category] || 0) + 1;
  });
  return totals;
}

/** Splits one page of cards into contiguous same-category runs. */
export function groupByCategory(pagedCards: McpCard[]): { category: string; items: McpCard[] }[] {
  const groups: { category: string; items: McpCard[] }[] = [];
  pagedCards.forEach((card: McpCard) => {
    const last = groups[groups.length - 1];
    if (last && last.category === card.category) last.items.push(card);
    else groups.push({ category: card.category, items: [card] });
  });
  return groups;
}
