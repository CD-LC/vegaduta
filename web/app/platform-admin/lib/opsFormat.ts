// Pure helpers for the Operations group (/ops, /observability, /maintenance,
// /jobs, /migrations, /email-suppressions, /skill-security). No React, no DOM,
// no fetch - every decision about what a figure reads as lives here so it is
// tested. Same contract as platformFormat.ts: null means "core could not tell",
// rendered as "—", never as 0.

import type {
  AnnouncementLevel,
  AnnouncementState,
  MigrationsResponse,
  ScheduledJob,
  ServiceVersion,
  TenantMigration,
} from "./adminApi/operations";
import { UNAVAILABLE } from "./platformFormat";

// ---------------------------------------------------------------- numbers

function finite(v: unknown): number | null {
  if (v === null || v === undefined || v === "") return null;
  const n = typeof v === "number" ? v : Number(v);
  return Number.isFinite(n) ? n : null;
}

/** 850 -> "850 ms", 1250 -> "1.25 s", 125000 -> "2.1 min"; "—" when unknown. */
export function formatDurationMs(v: unknown): string {
  const ms = finite(v);
  if (ms == null || ms < 0) return UNAVAILABLE;
  if (ms < 1000) return `${Math.round(ms)} ms`;
  const s = ms / 1000;
  if (s < 60) return `${s < 10 ? s.toFixed(2) : s.toFixed(1)} s`;
  return `${(s / 60).toFixed(1)} min`;
}

/** 1234 -> "1.2k", 2500000 -> "2.5M"; "—" when unknown. */
export function formatCompact(v: unknown): string {
  const n = finite(v);
  if (n == null) return UNAVAILABLE;
  const abs = Math.abs(n);
  if (abs >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (abs >= 10_000) return `${Math.round(n / 1000)}k`;
  if (abs >= 1000) return `${(n / 1000).toFixed(1)}k`;
  return String(Math.round(n));
}

/** First 7 chars of a git SHA; "—" when unknown. */
export function shortSha(sha: unknown): string {
  return typeof sha === "string" && sha.trim() ? sha.trim().slice(0, 7) : UNAVAILABLE;
}

// ---------------------------------------------------------------- tones

export type Tone = "ok" | "warn" | "bad" | "muted";

/** Same token pairings as reliability/format.ts toneClasses. */
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

// ---------------------------------------------------------------- versions

export function serviceStatusMeta(status: string | null | undefined): { label: string; tone: Tone } {
  if (status === "ok") return { label: "OK", tone: "ok" };
  if (status === "unreachable") return { label: "Unreachable", tone: "bad" };
  return { label: "Unknown", tone: "muted" };
}

/** "1.4.2", or the short SHA when there is no version string, else "—". */
export function versionLabel(s: Pick<ServiceVersion, "version" | "gitSha">): string {
  if (s.version && s.version.trim()) return s.version.trim();
  return shortSha(s.gitSha);
}

/** Services that did not answer their version probe. */
export function unreachableServices(services: ServiceVersion[] | null | undefined): ServiceVersion[] {
  return (services || []).filter((s) => s.status === "unreachable");
}

/**
 * web-admin's own build SHA, inlined at build time from NEXT_PUBLIC_BUILD_SHA.
 * Empty / unset reads as null ("not stamped"), never as a fake value.
 */
export function ownBuildSha(raw: string | undefined | null): string | null {
  const v = (raw || "").trim();
  return v ? v : null;
}

// ---------------------------------------------------------------- jobs

const JOB_RANK: Record<string, number> = { FAILED: 0, RUNNING: 1, SUCCESS: 3 };

export function jobStatusMeta(status: string | null | undefined): { label: string; tone: Tone } {
  switch (status) {
    case "FAILED":
      return { label: "Failed", tone: "bad" };
    case "RUNNING":
      return { label: "Running", tone: "warn" };
    case "SUCCESS":
      return { label: "Succeeded", tone: "ok" };
    default:
      return { label: "Never run", tone: "muted" };
  }
}

/** Failed first, then running, then never-run, then succeeded; name order inside a bucket. */
export function sortJobs(jobs: ScheduledJob[]): ScheduledJob[] {
  return [...jobs].sort((a, b) => {
    const ra = a.lastStatus ? (JOB_RANK[a.lastStatus] ?? 2) : 2;
    const rb = b.lastStatus ? (JOB_RANK[b.lastStatus] ?? 2) : 2;
    if (ra !== rb) return ra - rb;
    return `${a.bean}.${a.method}`.localeCompare(`${b.bean}.${b.method}`);
  });
}

/** Case-insensitive match on id, bean, method, schedule and last error. */
export function filterJobs(jobs: ScheduledJob[], q: string): ScheduledJob[] {
  const needle = q.trim().toLowerCase();
  if (!needle) return jobs;
  return jobs.filter((j) =>
    [j.id, j.bean, j.method, j.schedule, j.lastError].some(
      (f) => typeof f === "string" && f.toLowerCase().includes(needle)
    )
  );
}

export function failedJobCount(jobs: ScheduledJob[] | null | undefined): number | null {
  if (!Array.isArray(jobs)) return null;
  return jobs.filter((j) => j.lastStatus === "FAILED").length;
}

// ---------------------------------------------------------------- migrations

/**
 * Compare Flyway versions segment-wise ("312" vs "45", "1.10" vs "1.9").
 * Returns <0, 0, >0; null when either side is missing.
 */
export function compareVersions(a: string | null | undefined, b: string | null | undefined): number | null {
  if (!a || !b) return null;
  const pa = a.split(/[._]/).map((x) => Number(x));
  const pb = b.split(/[._]/).map((x) => Number(x));
  if (pa.some((n) => !Number.isFinite(n)) || pb.some((n) => !Number.isFinite(n))) {
    return a === b ? 0 : a < b ? -1 : 1;
  }
  const len = Math.max(pa.length, pb.length);
  for (let i = 0; i < len; i++) {
    const d = (pa[i] ?? 0) - (pb[i] ?? 0);
    if (d !== 0) return d;
  }
  return 0;
}

export type MigrationState = "failed" | "behind" | "ok" | "unknown";

export function tenantMigrationState(t: TenantMigration, expected: string | null | undefined): MigrationState {
  if ((t.failed ?? 0) > 0 || /fail/i.test(t.status || "")) return "failed";
  if ((t.pending ?? 0) > 0) return "behind";
  const cmp = compareVersions(t.version, expected);
  if (cmp == null) return "unknown";
  return cmp < 0 ? "behind" : "ok";
}

export function migrationStateMeta(state: MigrationState): { label: string; tone: Tone } {
  switch (state) {
    case "failed":
      return { label: "Failed", tone: "bad" };
    case "behind":
      return { label: "Behind", tone: "warn" };
    case "ok":
      return { label: "Current", tone: "ok" };
    default:
      return { label: "Unknown", tone: "muted" };
  }
}

export interface MigrationDrift {
  failed: number;
  behind: number;
  unknown: number;
  current: number;
  controlPlaneProblem: boolean;
}

export function migrationDrift(m: MigrationsResponse | null | undefined): MigrationDrift | null {
  if (!m || !Array.isArray(m.tenants)) return null;
  const out: MigrationDrift = { failed: 0, behind: 0, unknown: 0, current: 0, controlPlaneProblem: false };
  for (const t of m.tenants) {
    const s = tenantMigrationState(t, m.expectedTenantVersion);
    if (s === "failed") out.failed++;
    else if (s === "behind") out.behind++;
    else if (s === "unknown") out.unknown++;
    else out.current++;
  }
  out.controlPlaneProblem = (m.controlPlane?.failed ?? 0) > 0 || (m.controlPlane?.pending ?? 0) > 0;
  return out;
}

/** Failed, then behind, then unknown, then current; name order inside. */
export function sortTenantMigrations(m: MigrationsResponse): TenantMigration[] {
  const rank: Record<MigrationState, number> = { failed: 0, behind: 1, unknown: 2, ok: 3 };
  return [...m.tenants].sort((a, b) => {
    const d =
      rank[tenantMigrationState(a, m.expectedTenantVersion)] - rank[tenantMigrationState(b, m.expectedTenantVersion)];
    return d !== 0 ? d : (a.name || "").localeCompare(b.name || "");
  });
}

// ---------------------------------------------------------------- maintenance & announcement

export const MAINTENANCE_CONFIRM_WORD = "MAINTENANCE";

/** Enabling needs the typed word; disabling or editing while already on does not. */
export function maintenanceConfirmSatisfied(wasEnabled: boolean, willEnable: boolean, typed: string): boolean {
  if (!willEnable || wasEnabled) return true;
  return typed.trim() === MAINTENANCE_CONFIRM_WORD;
}

/** ISO -> "YYYY-MM-DDTHH:mm" in local time for <input type="datetime-local">; "" when missing. */
export function isoToLocalInput(iso: string | null | undefined): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** datetime-local value (local time) -> ISO; null when blank or invalid. */
export function localInputToIso(value: string | null | undefined): string | null {
  if (!value) return null;
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? null : d.toISOString();
}

export function isHttpUrl(v: string | null | undefined): boolean {
  if (!v) return false;
  try {
    const u = new URL(v);
    return u.protocol === "https:" || u.protocol === "http:";
  } catch {
    return false;
  }
}

/** Problems that block saving an announcement; empty when it can be saved. */
export function announcementProblems(a: AnnouncementState, now: number = Date.now()): string[] {
  const out: string[] = [];
  if (a.enabled && !a.message.trim()) out.push("An enabled announcement needs a message.");
  if (a.message.length > 500) out.push("Keep the message under 500 characters.");
  if (a.linkUrl && !isHttpUrl(a.linkUrl)) out.push("The link must be a full http(s) URL.");
  if (a.enabled && a.expiresAt) {
    const t = new Date(a.expiresAt).getTime();
    if (Number.isNaN(t)) out.push("The expiry date is not valid.");
    else if (t <= now) out.push("The expiry is in the past - the banner would never show.");
  }
  if (!["info", "warning", "critical"].includes(a.level)) out.push("Pick a level.");
  return out;
}

/** True while the announcement is switched on and not past its expiry. */
export function announcementLive(a: AnnouncementState | null | undefined, now: number = Date.now()): boolean {
  if (!a || !a.enabled || !a.message?.trim()) return false;
  if (!a.expiresAt) return true;
  const t = new Date(a.expiresAt).getTime();
  return Number.isNaN(t) ? true : t > now;
}

export function announcementLevelClasses(level: AnnouncementLevel | string): string {
  if (level === "critical") return toneClasses("bad");
  if (level === "warning") return toneClasses("warn");
  return "border-[var(--info-border)] bg-[var(--info-bg)] text-[var(--info-text)]";
}

// ---------------------------------------------------------------- external tools

export interface ExternalToolDef {
  key: string;
  label: string;
  envName: string;
  url: string | undefined;
  use: string;
}

/** A tool is configured only when its URL is set and is a real http(s) URL. No defaults. */
export function resolveExternalTool(def: ExternalToolDef): { configured: boolean; url: string | null } {
  const v = (def.url || "").trim();
  return isHttpUrl(v) ? { configured: true, url: v } : { configured: false, url: null };
}

// ---------------------------------------------------------------- charts

export interface BarPoint {
  index: number;
  value: number | null;
  x: number;
  y: number;
  h: number;
}

/**
 * Bar geometry for a fixed viewBox. Null values get no bar (h = 0) so a gap
 * reads as "no data", never as zero. max is at least 1 so an all-zero series
 * does not divide by zero.
 */
export function barGeometry(
  values: (number | null)[],
  opts: { width: number; height: number; padTop?: number; padBottom?: number }
): { max: number; slot: number; points: BarPoint[] } {
  const padTop = opts.padTop ?? 8;
  const padBottom = opts.padBottom ?? 4;
  const nums = values.filter((v): v is number => typeof v === "number" && Number.isFinite(v));
  const max = nums.length ? Math.max(0, ...nums) : 0;
  const effectiveMax = max > 0 ? max : 1;
  const slot = values.length ? opts.width / values.length : opts.width;
  const plotH = opts.height - padTop - padBottom;
  const baseline = opts.height - padBottom;
  const points = values.map((v, index) => {
    const val = typeof v === "number" && Number.isFinite(v) ? v : null;
    const h = val == null ? 0 : (Math.max(0, val) / effectiveMax) * plotH;
    return { index, value: val, x: slot * index + slot / 2, y: baseline - h, h };
  });
  return { max, slot, points };
}

/** Totals over the metrics window; null fields when no day reported that figure. */
export function sumMetrics(byDay: { traces: number | null; costUsd: number | null }[]): {
  traces: number | null;
  costUsd: number | null;
} {
  let traces: number | null = null;
  let cost: number | null = null;
  for (const d of byDay || []) {
    if (typeof d.traces === "number" && Number.isFinite(d.traces)) traces = (traces ?? 0) + d.traces;
    if (typeof d.costUsd === "number" && Number.isFinite(d.costUsd)) cost = (cost ?? 0) + d.costUsd;
  }
  return { traces, costUsd: cost };
}

/** Default trace window: the last `days` days ending now, as ISO strings. */
export function lastDaysRange(days: number, now: number = Date.now()): { from: string; to: string } {
  return { from: new Date(now - days * 86_400_000).toISOString(), to: new Date(now).toISOString() };
}

// ---------------------------------------------------------------- skill security

export function riskMeta(level: string | null | undefined): { label: string; tone: Tone } {
  switch (level) {
    case "high":
      return { label: "High risk", tone: "bad" };
    case "medium":
      return { label: "Medium risk", tone: "warn" };
    case "low":
      return { label: "Low risk", tone: "muted" };
    case "none":
      return { label: "No findings", tone: "ok" };
    default:
      return { label: "Not scanned", tone: "muted" };
  }
}

export function reviewReasonLabel(reason: string | null | undefined): string {
  if (reason === "CHANGED") return "Changed since approval";
  if (reason === "HIGH_RISK") return "High risk, never approved";
  return reason || UNAVAILABLE;
}
