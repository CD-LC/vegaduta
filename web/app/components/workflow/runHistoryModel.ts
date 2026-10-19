// Pure helpers behind the run history rail: status vocabulary, durations,
// relative times and the row model the list and sparkline both read.
import { Ban, CheckCircle2, CircleDashed, Hand, Loader2, PauseCircle, XCircle } from "lucide-react";

/*
 * LOCAL until web/app/components/workflow/runModel.ts (owned by the 'model'
 * agent) exports RunSummary - it did not exist when this was written. The
 * integrator should replace this with `import type { RunSummary } from "./runModel"`;
 * the shape is a structural subset so any richer RunSummary still fits.
 */
export interface RunSummary {
  id: string;
  status: string;
  startedAt?: string | null;
  completedAt?: string | null;
  requestedBy?: string | null;
  workflowVersion?: number | null;
  /** Optional future field; preferred over completedAt - startedAt when present. */
  durationMs?: number | null;
}

export type Tone = "ok" | "error" | "running" | "paused" | "cancelled" | "unknown";

export interface StatusMeta {
  tone: Tone;
  label: string;
  Icon: typeof CheckCircle2;
}

// Run-level statuses come from core's WorkflowRunStatus; the trace-level
// spellings (OK/ERROR) are accepted too so a caller can pass either.
export function statusMeta(raw: string): StatusMeta {
  switch ((raw || "").toUpperCase()) {
    case "COMPLETED":
    case "SUCCEEDED":
    case "OK":
      return { tone: "ok", label: "Completed", Icon: CheckCircle2 };
    case "FAILED":
    case "ERROR":
      return { tone: "error", label: "Failed", Icon: XCircle };
    case "RUNNING":
    case "PENDING":
    case "QUEUED":
      return { tone: "running", label: "Running", Icon: Loader2 };
    case "AWAITING_APPROVAL":
      return { tone: "paused", label: "Awaiting approval", Icon: Hand };
    case "AWAITING_INPUT":
      return { tone: "paused", label: "Needs input", Icon: Hand };
    case "PAUSED":
      return { tone: "paused", label: "Paused", Icon: PauseCircle };
    case "CANCELLED":
    case "CANCELED":
      return { tone: "cancelled", label: "Cancelled", Icon: Ban };
    default:
      return { tone: "unknown", label: raw ? humanize(raw) : "Unknown", Icon: CircleDashed };
  }
}

function humanize(s: string): string {
  const t = s.replace(/_/g, " ").toLowerCase();
  return t.charAt(0).toUpperCase() + t.slice(1);
}

export function toMs(iso?: string | null): number | null {
  if (!iso) return null;
  const n = Date.parse(iso);
  return Number.isFinite(n) ? n : null;
}

/** Duration in ms; live-ticking for in-flight runs, null when unknowable. */
export function durationOf(run: RunSummary, now: number): number | null {
  if (typeof run.durationMs === "number" && run.durationMs >= 0) return run.durationMs;
  const s = toMs(run.startedAt);
  if (s == null) return null;
  const e = toMs(run.completedAt);
  if (e != null) return Math.max(0, e - s);
  return statusMeta(run.status).tone === "running" ? Math.max(0, now - s) : null;
}

export function formatDuration(ms: number | null): string {
  if (ms == null) return "-";
  if (ms < 1000) return `${Math.round(ms)}ms`;
  const s = ms / 1000;
  if (s < 60) return `${s < 10 ? s.toFixed(1) : Math.round(s)}s`;
  const m = Math.floor(s / 60);
  const rs = Math.round(s % 60);
  if (m < 60) return `${m}m ${String(rs).padStart(2, "0")}s`;
  const h = Math.floor(m / 60);
  return `${h}h ${String(m % 60).padStart(2, "0")}m`;
}

const RTF_UNITS: Array<[Intl.RelativeTimeFormatUnit, number]> = [
  ["year", 31_536_000_000],
  ["month", 2_592_000_000],
  ["week", 604_800_000],
  ["day", 86_400_000],
  ["hour", 3_600_000],
  ["minute", 60_000],
];

export function relativeTime(rtf: Intl.RelativeTimeFormat, ms: number | null, now: number): string {
  if (ms == null) return "time unknown";
  const diff = ms - now;
  const abs = Math.abs(diff);
  if (abs < 45_000) return "just now";
  for (const [unit, size] of RTF_UNITS) {
    if (abs >= size || unit === "minute") return rtf.format(Math.round(diff / size), unit);
  }
  return "just now";
}

export function startOfDay(ms: number): number {
  const d = new Date(ms);
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}

export function median(xs: number[]): number | null {
  if (!xs.length) return null;
  const s = [...xs].sort((a, b) => a - b);
  const mid = Math.floor(s.length / 2);
  return s.length % 2 ? s[mid] : (s[mid - 1] + s[mid]) / 2;
}

export interface RunRow {
  run: RunSummary;
  /** 1-based flight number, oldest = 1, so it is stable as new runs land on top. */
  flight: number;
  meta: StatusMeta;
  startMs: number | null;
  duration: number | null;
  /** Multiple of the typical duration when noticeably slower, else null. */
  slowFactor: number | null;
}
