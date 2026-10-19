
// One row per Inspector-selectable card in the McpPanel grid - built-in tool,
// Google Workspace sub-tool, catalog/registered MCP server, or custom server.
// `data` deliberately stays `any`: it holds whichever of those four very
// different shapes `kind` says it does (BUILT_IN_TOOLS entry, GOOGLE_TOOLS
// entry, registered server, or catalog entry - none of which have a shared
// interface in this codebase yet), same loose-typing precedent this file
// already uses for `server: any` throughout.
export interface McpCard {
  selKey: string;
  category: string;
  kind: string;
  data: any;
  catalogEntry?: any;
}

/**
 * Totals for the /tools header, derived with the same rules McpPanel uses to
 * build its card list so the header and the "1-10 of N" pager cannot disagree:
 * built-ins, then the four Google tools when connected (one "connect" card
 * otherwise), one card per catalog entry, and one per registered server that
 * matches no catalog entry.
 */
export function toolCardTotals(opts: {
  builtInCount: number;
  googleToolCount: number;
  googleConnected: boolean;
  catalog: { name: string }[];
  servers: { name: string; enabled?: boolean }[];
}): { total: number; enabled: number } {
  const { builtInCount, googleToolCount, googleConnected, catalog, servers } = opts;
  const custom = servers.filter((s) => !catalog.some((c) => c.name === s.name)).length;
  return {
    total: builtInCount + (googleConnected ? googleToolCount : 1) + catalog.length + custom,
    enabled: builtInCount + (googleConnected ? googleToolCount : 0) + servers.filter((s) => s.enabled).length,
  };
}

export function initialsOf(name: any) {
  if (!name) return "?";
  const parts = name.trim().split(/\s+/);
  return ((parts[0]?.[0] || "") + (parts[1]?.[0] || "")).toUpperCase() || name.slice(0, 2).toUpperCase();
}

// Same derivation KnowledgePanel.js uses for exposedAgents - empty/missing
// allowedToolNames means unrestricted (counts as available), otherwise the
// exact literal tool name must be present.
export function agentsWithTool(agents: any, toolName: any) {
  return agents.filter(
    (a: { allowedToolNames?: string[] }) =>
      !a.allowedToolNames || a.allowedToolNames.length === 0 || a.allowedToolNames.includes(toolName)
  );
}

// #424 - which requiredEnvVars entries a catalog entry's dynamicArgs will
// splice in as a positional CLI arg (postgres/sqlite) rather than send as an
// env var - see McpCatalogEntry.dynamicArgs' javadoc for why this is
// data-driven instead of a per-server special case here.
export function placeholderVars(entry: any) {
  const vars = new Set();
  (entry.dynamicArgs || []).forEach((a: string) => {
    const m = /^\{\{(.+)\}\}$/.exec(a);
    if (m) vars.add(m[1]);
  });
  return vars;
}

// Human labels for the test endpoint's phase field - which stage of the
// connection lifecycle the test reached (or died in).
export const PHASE_LABELS: Record<string, string> = {
  SPAWN: "Spawning the server process",
  HTTP: "HTTP connection",
  HANDSHAKE: "MCP handshake",
  INIT_TIMEOUT: "Initialization timed out",
  OK: "Connected",
};

// Shared success/warning tone classes. Sourced from the same --state-success/
// --state-warning tokens ui/alert.tsx's success/warning variants use (design-
// system contract, docs/DESIGN-SYSTEM-CONTRACT-2026-07-29.md §3) rather than
// a hand-picked emerald/amber approximation, so a status pill and an alert
// about the same state are guaranteed to read as the exact same color instead
// of two independently-invented greens/ambers.
export const TONE_SUCCESS =
  "border-[color-mix(in_srgb,var(--state-success)_32%,transparent)] " +
  "bg-[color-mix(in_srgb,var(--state-success)_12%,transparent)] " +
  "text-[var(--state-success)]";
export const TONE_WARNING =
  "border-[color-mix(in_srgb,var(--state-warning)_32%,transparent)] " +
  "bg-[color-mix(in_srgb,var(--state-warning)_12%,transparent)] " +
  "text-[var(--state-warning)]";

// The new core writes an enriched last-error ("detail" + an actionable
// hint); older cores wrote one raw blob. Accept every shape: a dedicated
// field if the read DTO grows one, a "Hint:" marker embedded in the text,
// or a plain legacy string (detail only, no hint).
export function splitConnectionError(server: any): { detail: string | null; hint: string | null } {
  const raw: string | null = server?.connectionError || null;
  const explicitHint: string | null = server?.connectionHint || server?.hint || null;
  if (!raw) return { detail: null, hint: explicitHint };
  if (explicitHint) return { detail: raw, hint: explicitHint };
  const m = /\bhint:\s*/i.exec(raw);
  if (m) {
    return {
      detail: raw.slice(0, m.index).trim() || null,
      hint: raw.slice(m.index + m[0].length).trim() || null,
    };
  }
  return { detail: raw, hint: null };
}

// usageStatus (UNTESTED | READY | FAILED | ACKNOWLEDGED) - the readiness
// gate the core agent is adding: agents only use READY/ACKNOWLEDGED servers.
// null = the running core predates the field, in which case NO badge is
// shown (an old core does NOT gate agents on tests, so claiming "agents
// won't use this until it passes a test" would be false there).
export function usageStatusOf(server: any): string | null {
  return typeof server?.usageStatus === "string" && server.usageStatus ? server.usageStatus : null;
}

// The read DTO stores args as a JSON-encoded string (McpRegistryService
// writeJson) but the write DTO wants a real list - parse defensively so
// re-registering (the only way to attach env today, PATCH only carries
// {enabled}) round-trips the stored command line intact.
export function parseStoredArgs(args: any): string[] {
  if (Array.isArray(args)) return args.map(String);
  if (typeof args === "string" && args.trim()) {
    try {
      const parsed = JSON.parse(args);
      if (Array.isArray(parsed)) return parsed.map(String);
    } catch {
      // Not JSON - treat as a space-separated legacy string.
    }
    return args.trim().split(/\s+/);
  }
  return [];
}

// MCP-STDIO-SANDBOXING (2026-08-05) - McpReviewStatus (NOT_REQUIRED | PENDING
// | APPROVED | REJECTED), the platform-staff safety-review gate a self-
// service custom/registry-browsed STDIO registration must clear before it's
// ever allowed to spawn (see McpReviewStatus.java's javadoc). null = the
// running core predates the field (no gate to show).
export function reviewStatusOf(server: any): string | null {
  return typeof server?.reviewStatus === "string" && server.reviewStatus ? server.reviewStatus : null;
}
