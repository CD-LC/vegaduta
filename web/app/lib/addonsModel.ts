import { docsPageHref } from "./siteLinks";
import type { AddonApply, AddonCategory, AddonStatus, AddonView } from "./addonsApi";

// Pure helpers behind the /add-ons page: grouping, filtering, polling cadence, dependency previews and
// the copyable host commands. No React, no network.

/** Display order of the groups. Categories core sends that are not listed here follow, in first-seen order. */
export const CATEGORY_ORDER: readonly AddonCategory[] = ["assistant", "search", "documents", "models", "media", "tools"];

export type StatusFilter = "all" | "on" | "off" | "attention" | "planned";

export interface AddonFilters {
  query: string;
  category: AddonCategory | "all";
  status: StatusFilter;
}

export const NO_FILTERS: AddonFilters = { query: "", category: "all", status: "all" };

/** True when the add-on cannot be switched on at all yet. */
export function isPlanned(a: AddonView): boolean {
  return a.availability === "planned" || a.status === "unavailable";
}

/** A host-managed (compose) add-on whose wish the host has not finished applying. */
export function isWaitingForHost(a: AddonView): boolean {
  return a.status === "enable_requested" || a.status === "applying" || a.status === "disable_requested";
}

/** Which status-filter bucket an add-on falls in. "Attention" is everything in flight or failed. */
export function statusBucket(a: AddonView): Exclude<StatusFilter, "all"> {
  if (isPlanned(a)) return "planned";
  if (a.status === "on") return "on";
  if (a.status === "off") return "off";
  return "attention";
}

export function filterAddons(
  addons: readonly AddonView[],
  filters: AddonFilters,
  /** The text a person can see for this add-on (translated name and description), searched case-insensitively. */
  haystack: (a: AddonView) => string
): AddonView[] {
  const q = filters.query.trim().toLowerCase();
  return addons.filter((a) => {
    if (filters.category !== "all" && a.category !== filters.category) return false;
    if (filters.status !== "all" && statusBucket(a) !== filters.status) return false;
    if (q === "") return true;
    return `${a.id} ${haystack(a)}`.toLowerCase().includes(q);
  });
}

export interface AddonGroup {
  category: AddonCategory;
  items: AddonView[];
}

/** Groups in CATEGORY_ORDER (unknown categories after, first-seen order); the order inside a group is the catalog order. */
export function groupByCategory(addons: readonly AddonView[]): AddonGroup[] {
  const seen: AddonCategory[] = [];
  for (const a of addons) if (!seen.includes(a.category)) seen.push(a.category);
  const known = CATEGORY_ORDER.filter((c) => seen.includes(c));
  const extra = seen.filter((c) => !CATEGORY_ORDER.includes(c));
  return [...known, ...extra].map((category) => ({ category, items: addons.filter((a) => a.category === category) }));
}

/** Per-category counts for the filter chips, over the unfiltered list. */
export function categoryCounts(addons: readonly AddonView[]): Map<AddonCategory, number> {
  const out = new Map<AddonCategory, number>();
  for (const a of addons) out.set(a.category, (out.get(a.category) ?? 0) + 1);
  return out;
}

/**
 * How often to re-read the list: every 2 s while a model downloads, every 5 s while a host-managed add-on
 * waits for the installer, otherwise not at all. (Core calls Ollama on each read, so never faster than 2 s.)
 */
export function pollIntervalMs(addons: readonly AddonView[]): number | null {
  if (addons.some((a) => a.status === "pulling")) return 2000;
  if (addons.some(isWaitingForHost)) return 5000;
  return null;
}

/** Requirements that switching `id` on would also switch on, requirements first (what core's `changed` will list). */
export function requirementsToEnable(id: string, byId: ReadonlyMap<string, AddonView>): AddonView[] {
  const out: AddonView[] = [];
  const visiting = new Set<string>();
  const visit = (cur: string) => {
    if (visiting.has(cur)) return;
    visiting.add(cur);
    const a = byId.get(cur);
    if (!a) return;
    for (const req of a.requires) visit(req);
    if (cur !== id && !a.desired) out.push(a);
  };
  visit(id);
  return out;
}

/**
 * The add-ons a disable would take down with it: those listed in `requiredBy` and, transitively, whatever
 * needs them. This is a preview for the confirmation text; core decides what actually changes.
 */
export function dependentsOf(id: string, byId: ReadonlyMap<string, AddonView>): AddonView[] {
  const out: AddonView[] = [];
  const seen = new Set<string>([id]);
  const queue = [...(byId.get(id)?.requiredBy ?? [])];
  while (queue.length) {
    const cur = queue.shift() as string;
    if (seen.has(cur)) continue;
    seen.add(cur);
    const a = byId.get(cur);
    if (!a) continue;
    out.push(a);
    queue.push(...a.requiredBy);
  }
  return out;
}

export type StatusTone = "success" | "warning" | "danger" | "progress" | "neutral";

export function statusTone(status: AddonStatus): StatusTone {
  switch (status) {
    case "on":
      return "success";
    case "failed":
      return "danger";
    case "enable_requested":
    case "disable_requested":
      return "warning";
    case "applying":
    case "pulling":
      return "progress";
    default:
      return "neutral";
  }
}

/** "377 MB", "4.1 GB", "14 GB". Sizes are approximate by design, so no more than one decimal. */
export function formatMb(mb: number): string {
  if (!Number.isFinite(mb) || mb <= 0) return "0 MB";
  if (mb < 1024) return `${Math.round(mb)} MB`;
  const gb = mb / 1024;
  const text = gb >= 10 ? String(Math.round(gb)) : gb.toFixed(1).replace(/\.0$/, "");
  return `${text} GB`;
}

/** The documentation link for a catalog `learnMore` value ("docs/local-models.md#free-web-search"), or null. */
export function learnMoreHref(learnMore: string): string | null {
  const raw = learnMore.trim();
  if (raw === "") return null;
  const hash = raw.indexOf("#");
  const path = hash === -1 ? raw : raw.slice(0, hash);
  const anchor = hash === -1 ? "" : raw.slice(hash);
  const m = /^docs\/(.+)\.md$/.exec(path);
  if (!m) return null;
  const href = docsPageHref(m[1], path);
  return `${href}${anchor}`;
}

/** `KEY=value` lines for deploy/compose/.env, sorted so the text is stable between polls. */
export function envLines(envSet: Record<string, string>): string[] {
  return Object.keys(envSet)
    .sort()
    .map((k) => `${k}=${envSet[k]}`);
}

export interface WindowsApply {
  /** `.\scripts\vegaduta.ps1 personal -Profile a,b`, or null when nothing is wanted beyond the base stack. */
  command: string;
  /** Wanted flags the PowerShell launcher cannot express (today: --search). */
  unsupportedFlags: string[];
}

/** The PowerShell form of `apply.command`; `--search` has no PowerShell equivalent today (contract, section 6). */
export function windowsApply(apply: AddonApply): WindowsApply {
  const profile = apply.profiles.length > 0 ? ` -Profile ${apply.profiles.join(",")}` : "";
  return { command: `.\\scripts\\vegaduta.ps1 personal${profile}`, unsupportedFlags: [...apply.flags] };
}

/** True when the browser reports a Windows computer, so the host panel leads with the PowerShell command. */
export function isWindowsClient(userAgent: string | undefined): boolean {
  return typeof userAgent === "string" && /windows/i.test(userAgent);
}

/** Maps a failed call to the key (in the `addons` namespace) of the sentence that explains it. */
export function errorKeyFor(status: number | undefined, code: string | null | undefined): string {
  switch (code) {
    case "unknown_addon":
    case "not_available":
    case "required_by":
    case "conflict":
    case "rate_limited":
    case "too_many_downloads":
    case "ollama_unreachable":
      return `error.${code}`;
    default:
      break;
  }
  if (status === 401) return "error.signedOut";
  if (status === 403) return "error.forbidden";
  if (status === 429) return "error.rate_limited";
  if (status === 503) return "error.unavailable";
  return "error.generic";
}
