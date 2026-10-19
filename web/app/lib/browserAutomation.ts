import type { BrowserProfile, BrowserRecipeSummary } from "./api/browserAutomation";

// Pure helpers behind the browser-automation cards (saved logins + recorded tasks). Kept out of the
// components so the rules - what counts as a domain, which parameter names are legal, when a run
// is worth offering "save as recipe" for - are unit-testable without a DOM. They mirror the
// server's validation (core BrowserStorageState / BrowserRecipeValidator) only to give instant
// feedback; the server stays the authority.

/** Parameter names: letters, digits, underscore, start with a letter, at most 32 chars. */
export const PARAM_NAME_RE = /^[A-Za-z][A-Za-z0-9_]{0,31}$/;
/** `{{secret.X}}` and `{{totp.X}}` are placeholder namespaces, so these cannot be parameter names. */
export const RESERVED_PARAM_NAMES = ["secret", "totp"];
/** Shorter values are too ambiguous to find safely in a typed text, so core ignores them. */
export const MIN_PARAM_VALUE_LENGTH = 3;

const DOMAIN_RE = /^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$/;
const IP_LIKE_RE = /^[0-9.]+$/;

/** True for a hostname with at least two labels (never an IP literal or a bare TLD like "com"). */
export function isPlausibleDomain(domain: string): boolean {
  const d = domain.trim().toLowerCase();
  return d.length > 0 && d.length <= 253 && !IP_LIKE_RE.test(d) && DOMAIN_RE.test(d);
}

/**
 * Turns what a person types into a domain box - commas, spaces or newlines between entries, a pasted URL, a
 * "*." prefix, a port - into unique lower-case hostnames, in the order given. Entries that are not a plausible
 * hostname are returned in `invalid` (as typed) rather than silently dropped, so the form can say which one.
 */
export function parseDomainInput(text: string): { domains: string[]; invalid: string[] } {
  const domains: string[] = [];
  const invalid: string[] = [];
  for (const raw of (text || "").split(/[\s,;]+/)) {
    const entry = raw.trim();
    if (!entry) continue;
    let host = entry.toLowerCase();
    host = host.replace(/^[a-z][a-z0-9+.-]*:\/\//, ""); // scheme
    host = host.split(/[/?#]/)[0]; // path, query, fragment
    host = host.replace(/:\d+$/, ""); // port
    host = host.replace(/^\*\./, "").replace(/^\.+/, "").replace(/\.+$/, "");
    if (isPlausibleDomain(host)) {
      if (!domains.includes(host)) domains.push(host);
    } else {
      invalid.push(entry);
    }
  }
  return { domains, invalid };
}

/** "1.2 KB" style size for a saved state; the state itself is never shown. */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return "0 B";
  if (bytes < 1024) return `${Math.round(bytes)} B`;
  const kb = bytes / 1024;
  if (kb < 1024) return `${kb < 10 ? kb.toFixed(1) : Math.round(kb)} KB`;
  return `${(kb / 1024).toFixed(1)} MB`;
}

export type ProfileStatus = "no-login" | "active" | "expired";

/** Where a profile is in its life: nothing captured yet, holding a live login, or the login has lapsed. */
export function profileStatus(profile: Pick<BrowserProfile, "hasState" | "expired">): ProfileStatus {
  if (!profile.hasState) return "no-login";
  return profile.expired ? "expired" : "active";
}

/** Whole days from `now` until `iso` (negative when past); null when there is no date. */
export function daysUntil(iso: string | null | undefined, now: Date): number | null {
  if (!iso) return null;
  const t = Date.parse(iso);
  if (!Number.isFinite(t)) return null;
  return Math.ceil((t - now.getTime()) / 86_400_000);
}

/** The profile an agent currently runs with (the server allows one per agent), if any. */
export function profileForAgent(profiles: BrowserProfile[], agentId: string): BrowserProfile | undefined {
  return profiles.find((p) => p.attachedAgentIds.includes(agentId));
}

/** Profiles a person can switch this agent to: everything except the one it already uses, shared ones first. */
export function attachableProfiles(profiles: BrowserProfile[], agentId: string): BrowserProfile[] {
  return profiles
    .filter((p) => !p.attachedAgentIds.includes(agentId))
    .sort((a, b) => (a.scope === b.scope ? a.name.localeCompare(b.name) : a.scope === "TENANT" ? -1 : 1));
}

export interface ParamRow {
  id: string;
  name: string;
  value: string;
}

export type ParamProblemKind = "badName" | "reservedName" | "duplicateName" | "shortValue";

export interface ParamProblem {
  rowId: string;
  kind: ParamProblemKind;
}

/**
 * Builds the {name: value} map a "save this run as a recipe" request sends, from the form's rows. A row with
 * neither a name nor a value is ignored (an untouched blank row is not an error); every other problem is
 * reported against its row so the form can mark it. `parameters` holds only the valid rows.
 */
export function buildParameterMap(rows: ParamRow[]): { parameters: Record<string, string>; problems: ParamProblem[] } {
  const parameters: Record<string, string> = {};
  const problems: ParamProblem[] = [];
  const seen = new Set<string>();
  for (const row of rows) {
    const name = row.name.trim();
    const value = row.value;
    if (!name && !value.trim()) continue;
    if (!PARAM_NAME_RE.test(name)) {
      problems.push({ rowId: row.id, kind: "badName" });
      continue;
    }
    if (RESERVED_PARAM_NAMES.includes(name.toLowerCase())) {
      problems.push({ rowId: row.id, kind: "reservedName" });
      continue;
    }
    if (seen.has(name.toLowerCase())) {
      problems.push({ rowId: row.id, kind: "duplicateName" });
      continue;
    }
    if (value.trim().length < MIN_PARAM_VALUE_LENGTH) {
      problems.push({ rowId: row.id, kind: "shortValue" });
      continue;
    }
    seen.add(name.toLowerCase());
    parameters[name] = value;
  }
  return { parameters, problems };
}

const BROWSER_ACTION_TOOLS = new Set(["browser_navigate", "browser_click", "browser_type"]);

interface TraceStepLike {
  type: string;
  tool?: string;
}

/** How many browser actions (navigate / click / type) a run's activity trace shows. */
export function browserActionCount(steps: TraceStepLike[] | null | undefined): number {
  if (!steps) return 0;
  return steps.filter((s) => s.type === "tool_call" && !!s.tool && BROWSER_ACTION_TOOLS.has(s.tool)).length;
}

/** True when a finished run drove the browser, so offering "save this run as a recipe" makes sense. */
export function isBrowserRun(steps: TraceStepLike[] | null | undefined): boolean {
  return browserActionCount(steps) >= 2;
}

export type RecipeHealth = "new" | "healthy" | "failing";

/** A recipe whose latest replay failed needs a look; one never replayed is just new. */
export function recipeHealth(recipe: Pick<BrowserRecipeSummary, "runCount" | "lastFailure">): RecipeHealth {
  if (recipe.lastFailure) return "failing";
  return recipe.runCount > 0 ? "healthy" : "new";
}

/** "Which recipes may this agent run": its own plus tenant-wide ones, own first. */
export function recipesForAgent(recipes: BrowserRecipeSummary[], agentId: string): BrowserRecipeSummary[] {
  return recipes
    .filter((r) => r.agentId === null || r.agentId === agentId)
    .sort((a, b) => {
      if ((a.agentId === null) !== (b.agentId === null)) return a.agentId === null ? 1 : -1;
      return a.name.localeCompare(b.name);
    });
}
