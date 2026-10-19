import type { DependencyEntry, DependencyStatus, SloVerdict } from "../lib/adminApi/reliability";

// Pure helpers for the Reliability screen. No React, no DOM: every decision the
// page makes about WHAT to say (how a status reads, when a number is shown at
// all, how a burn rate is worded, when the poll should run) lives here so it is
// tested. Status is never conveyed by colour alone: each tone pairs with an icon
// name and a text label, chosen here.

export const REFRESH_MS = 30_000;

/** Window choices offered in the UI; the server accepts 1..retention (48 h by default). */
export const WINDOWS_HOURS = [1, 6, 24, 48];

export type Tone = "ok" | "warn" | "bad" | "muted";
export type IconName = "check" | "alert" | "x" | "help";

export interface StatusMeta {
  label: string;
  tone: Tone;
  icon: IconName;
}

const STATUS_META: Record<DependencyStatus, StatusMeta> = {
  UP: { label: "Up", tone: "ok", icon: "check" },
  DEGRADED: { label: "Degraded", tone: "warn", icon: "alert" },
  DOWN: { label: "Down", tone: "bad", icon: "x" },
  UNKNOWN: { label: "Unknown", tone: "muted", icon: "help" },
};

/** Any status string the server sends maps to a meta; an unrecognised one reads as Unknown, never as Up. */
export function statusMeta(status: string | null | undefined): StatusMeta {
  return (status && STATUS_META[status as DependencyStatus]) || STATUS_META.UNKNOWN;
}

/** Tailwind classes for a tile or banner, token-driven with the same dark pairings the Alert primitive uses. */
export function toneClasses(tone: Tone): string {
  switch (tone) {
    case "ok":
      return "border-[var(--success-border)] bg-[var(--success-bg)] text-[var(--success-text)]";
    case "warn":
      return "border-[var(--warning-border)] bg-[var(--warning-bg)] text-[var(--warning-text)]";
    case "bad":
      return "border-destructive/30 bg-destructive/10 text-destructive";
    default:
      return "border-border bg-muted text-muted-foreground";
  }
}

export interface VerdictMeta {
  label: string;
  tone: Tone;
  icon: IconName;
}

const VERDICT_META: Record<SloVerdict, VerdictMeta> = {
  OK: { label: "Within budget", tone: "ok", icon: "check" },
  WARN: { label: "Burning too fast", tone: "warn", icon: "alert" },
  BREACH: { label: "Budget breach", tone: "bad", icon: "x" },
  INSUFFICIENT_DATA: { label: "Not enough data", tone: "muted", icon: "help" },
};

/** Undefined for a missing verdict (a class with no target has none to show). */
export function verdictMeta(verdict: string | null | undefined): VerdictMeta | undefined {
  if (!verdict) return undefined;
  return VERDICT_META[verdict as SloVerdict] ?? VERDICT_META.INSUFFICIENT_DATA;
}

// ---------------------------------------------------------------- number formatting

const NA = "n/a";

/** 12 -> "12 ms", 1250 -> "1.25 s". Null (nothing was measured) is "n/a", never "0 ms". */
export function fmtLatency(ms: number | null | undefined): string {
  if (ms == null || !Number.isFinite(ms)) return NA;
  if (ms < 1000) return `${Math.round(ms)} ms`;
  const s = ms / 1000;
  return `${s < 10 ? s.toFixed(2) : s.toFixed(1)} s`;
}

/** 0.99512 -> "99.51%". Availability needs two decimals to be readable near 100. */
export function fmtPct(v: number | null | undefined, digits = 2): string {
  if (v == null || !Number.isFinite(v)) return NA;
  return `${(v * 100).toFixed(digits)}%`;
}

/** 0.8 -> "0.80x". */
export function fmtBurn(v: number | null | undefined): string {
  if (v == null || !Number.isFinite(v)) return NA;
  return `${v.toFixed(2)}x`;
}

export function fmtCount(v: number | null | undefined): string {
  return v == null || !Number.isFinite(v) ? NA : Math.round(v).toLocaleString("en-US");
}

/** Seconds as the unit a person reads: 45 s, 12 min, 3.5 h. */
export function fmtDuration(seconds: number | null | undefined): string {
  if (seconds == null || !Number.isFinite(seconds) || seconds < 0) return NA;
  if (seconds < 120) return `${Math.round(seconds)} s`;
  if (seconds < 7200) return `${Math.round(seconds / 60)} min`;
  return `${(seconds / 3600).toFixed(1)} h`;
}

/** What is left of the window's error budget, in words. Negative means overspent. */
export function budgetText(remaining: number | null | undefined): string {
  if (remaining == null || !Number.isFinite(remaining)) return "Error budget not computed";
  if (remaining < 0) return `Error budget overspent (${Math.round(-remaining * 100)}% over)`;
  return `${Math.round(remaining * 100)}% of the error budget left`;
}

// ---------------------------------------------------------------- dependency view

export const CATEGORY_ORDER = [
  "database",
  "cache",
  "auth",
  "ai",
  "messaging",
  "workflow",
  "storage",
  "service",
  "observability",
  "credential",
];

const CATEGORY_LABELS: Record<string, string> = {
  database: "Database",
  cache: "Cache",
  auth: "Authentication",
  ai: "AI services",
  messaging: "Messaging",
  credential: "Credentials (presence only)",
  // Categories core added with the wider probe set (Temporal, code-intel,
  // MinIO, Langfuse, Academy). Any other category still renders, under its
  // raw name, after these - see groupByCategory.
  workflow: "Workflow engine",
  storage: "Object storage",
  observability: "Observability",
  service: "Internal services",
};

export function categoryLabel(category: string): string {
  return CATEGORY_LABELS[category] ?? category;
}

export interface CategoryGroup {
  category: string;
  label: string;
  items: DependencyEntry[];
}

/** Groups entries by category in a fixed order, keeping the server's order inside a group. Unknown categories go last. */
export function groupByCategory(entries: DependencyEntry[]): CategoryGroup[] {
  const buckets = new Map<string, DependencyEntry[]>();
  for (const e of entries) {
    const list = buckets.get(e.category) ?? [];
    list.push(e);
    buckets.set(e.category, list);
  }
  const known = CATEGORY_ORDER.filter((c) => buckets.has(c));
  const extra = [...buckets.keys()].filter((c) => !CATEGORY_ORDER.includes(c)).sort();
  return [...known, ...extra].map((category) => ({
    category,
    label: categoryLabel(category),
    items: buckets.get(category) ?? [],
  }));
}

export function countStatuses(entries: DependencyEntry[]): Record<DependencyStatus, number> {
  const counts: Record<DependencyStatus, number> = { UP: 0, DEGRADED: 0, DOWN: 0, UNKNOWN: 0 };
  for (const e of entries) {
    counts[statusMeta(e.status) === STATUS_META.UNKNOWN ? "UNKNOWN" : e.status] += 1;
  }
  return counts;
}

/** One sentence under the overall banner. */
export function overallSummary(entries: DependencyEntry[]): string {
  if (entries.length === 0) return "No dependency readings are available.";
  const c = countStatuses(entries);
  const parts: string[] = [];
  if (c.DOWN) parts.push(`${c.DOWN} down`);
  if (c.DEGRADED) parts.push(`${c.DEGRADED} degraded`);
  parts.push(`${c.UP} up`);
  if (c.UNKNOWN) parts.push(`${c.UNKNOWN} unknown (not configured or not checkable)`);
  return parts.join(" · ");
}

// ---------------------------------------------------------------- refresh policy

/** The 30-second poll only runs while the tab is visible. */
export function shouldPoll(visibility: string | null | undefined): boolean {
  return visibility !== "hidden";
}

/** On returning to a hidden tab, refresh at once only if the data is older than one poll interval. */
export function needsCatchUpRefresh(lastFetchedAt: number | null, now: number, intervalMs = REFRESH_MS): boolean {
  return lastFetchedAt == null || now - lastFetchedAt >= intervalMs;
}

/** Window labels: 1 -> "1 h", 48 -> "48 h". */
export function windowLabel(hours: number): string {
  return `${hours} h`;
}
