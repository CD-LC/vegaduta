// "What is already set up here" for TemplateSetupGuide: one shared, briefly cached snapshot of the
// workspace (plan, knowledge, channels, Google, E2B, the MCP catalog and registrations) plus the pure
// helpers that read MCP server state out of it. Split out of TemplateSetupGuide.

import { api } from "../../../lib/api";
import type { McpServerKey, McpSuggestion } from "../templatePrereqs";

// One shared snapshot of "what is already set up here", cached briefly so
// opening the guide on five templates in a row is one round of requests, not
// five. Deliberately short: the point of the ticks is that they are current,
// and a person who leaves to enable something and comes back should see it.
const SNAPSHOT_TTL_MS = 45_000;

export type Tri = "done" | "todo" | "unknown";

export interface McpRegistration {
  name?: string;
  enabled?: boolean;
  connectionStatus?: string;
}

export interface CatalogEntry {
  key?: string;
  name?: string;
  description?: string;
  docsUrl?: string;
  ready?: boolean;
  requiredEnvVars?: string[];
  requiresOAuth?: boolean;
}

export interface WorkspaceSnapshot {
  plan: string | null;
  collectionCount: number | null;
  connectedChannels: string[] | null;
  googleConnected: boolean | null;
  e2bActive: boolean | null;
  catalog: CatalogEntry[] | null;
  registrations: McpRegistration[] | null;
}

let snapshotCache: { at: number; value: Promise<WorkspaceSnapshot> } | null = null;

/** Every field independently nullable: one 403 (several of these are admin-only) must not blank out the rest of the checklist. */
async function loadSnapshot(): Promise<WorkspaceSnapshot> {
  const [settings, collections, channels, google, e2b, catalog, registrations] = await Promise.allSettled([
    api.getTenantSettings(),
    api.listCollections(),
    api.getChannelsStatus(),
    api.getGoogleConnection(),
    api.getE2bCredential(),
    api.listMcpCatalog(),
    api.listMcpServers(),
  ]);

  // A rejected promise (403 on an admin-only read, network failure) stays
  // null, which renders as "not checked" rather than as "not done" - the
  // difference matters, because "not done" would be a claim we cannot make.
  const settled = <T,>(r: PromiseSettledResult<unknown>, pick: (value: unknown) => T | null): T | null => {
    if (r.status !== "fulfilled") return null;
    try {
      return pick(r.value);
    } catch {
      return null;
    }
  };

  const field = (value: unknown, key: string): unknown =>
    value && typeof value === "object" ? (value as Record<string, unknown>)[key] : undefined;

  return {
    plan: settled(settings, (v) => {
      const plan = field(v, "plan");
      return typeof plan === "string" ? plan : null;
    }),
    collectionCount: settled(collections, (v) => (Array.isArray(v) ? v.length : null)),
    connectedChannels: settled(channels, (v) => {
      const list = field(v, "channels");
      if (!Array.isArray(list)) return null;
      return list
        .filter((c) => field(c, "state") === "CONNECTED")
        .map((c) => String(field(c, "label") ?? field(c, "key") ?? ""))
        .filter((name) => name.length > 0);
    }),
    // 204 -> null body -> not connected.
    googleConnected: settled(google, (v) => Boolean(v)),
    e2bActive: settled(e2b, (v) => Boolean(v)),
    catalog: settled(catalog, (v) => (Array.isArray(v) ? (v as CatalogEntry[]) : null)),
    registrations: settled(registrations, (v) => (Array.isArray(v) ? (v as McpRegistration[]) : null)),
  };
}

export function getSnapshot(): Promise<WorkspaceSnapshot> {
  const now = Date.now();
  if (snapshotCache && now - snapshotCache.at < SNAPSHOT_TTL_MS) return snapshotCache.value;
  const value = loadSnapshot();
  snapshotCache = { at: now, value };
  return value;
}

/** Registrations are matched back to catalog entries by NAME - key and name are identical for all 17, which is what McpPanel relies on too. */
function registrationFor(snapshot: WorkspaceSnapshot | null, server: McpServerKey): McpRegistration | null {
  if (!snapshot?.registrations) return null;
  return snapshot.registrations.find((r) => r?.name === server) ?? null;
}

export type ServerState = "on" | "error" | "off" | "missing" | "unknown";

export function serverState(snapshot: WorkspaceSnapshot | null, server: McpServerKey): ServerState {
  if (!snapshot?.registrations) return "unknown";
  const reg = registrationFor(snapshot, server);
  if (!reg) return "missing";
  if (reg.enabled === false) return "off";
  if (reg.connectionStatus === "ERROR") return "error";
  return "on";
}

/** Interchangeable suggestions (github OR gitlab) collapse into one block so the checklist never reads as "you need both". */
export function groupSuggestions(mcp: readonly McpSuggestion[]) {
  const blocks: Array<{ id: string; alternatives: boolean; items: McpSuggestion[] }> = [];
  const seen = new Map<string, number>();
  mcp.forEach((suggestion, i) => {
    if (suggestion.alternativeGroup) {
      const at = seen.get(suggestion.alternativeGroup);
      if (at !== undefined) {
        blocks[at].items.push(suggestion);
        return;
      }
      seen.set(suggestion.alternativeGroup, blocks.length);
      blocks.push({ id: suggestion.alternativeGroup, alternatives: true, items: [suggestion] });
      return;
    }
    blocks.push({ id: `${suggestion.server}-${i}`, alternatives: false, items: [suggestion] });
  });
  // Required first - the thing without which the agent simply does not work
  // should not be below three nice-to-haves.
  return [
    ...blocks.filter((b) => b.items.some((i) => i.necessity === "required")),
    ...blocks.filter((b) => !b.items.some((i) => i.necessity === "required")),
  ];
}
