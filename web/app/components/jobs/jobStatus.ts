// Presentation vocabulary for AgentJobStatus, in ONE place.
//
// The single rule this file exists to enforce: AWAITING_APPROVAL is NOT
// finished. AgentJobStatus.java's javadoc opens by calling that "the single
// most important sentence in this file", because the workflow engine shipped
// the same state as effectively terminal and had to be corrected afterwards -
// "completed" counts, finished filters and the run list all quietly treated a
// run merely waiting for a human as a run that was over. A job parked on an
// approval gate holds no worker and can wait days; it is still in flight.
//
// So: never write `status === "SUCCEEDED" || status === "FAILED" || ...`
// anywhere in the UI. For a single job, trust the server's own `terminal`
// boolean on AgentJobResponse (projected explicitly so no client has to
// derive it). For list rows - AgentJobSummaryResponse carries no `terminal`
// field - use isLiveStatus below, and for server-side filtering always send
// the "live" sentinel rather than an enumerated list.

export type JobStatus =
  | "QUEUED"
  | "RUNNING"
  | "AWAITING_APPROVAL"
  | "SUCCEEDED"
  | "FAILED"
  | "CANCELLED";

/** Mirrors AgentJobStatus.live() - the three non-terminal states. */
const LIVE: readonly string[] = ["QUEUED", "RUNNING", "AWAITING_APPROVAL"];

export function isLiveStatus(status: string | null | undefined): boolean {
  return !!status && LIVE.includes(status);
}

export type JobStatusTone = {
  label: string;
  /** i18n key (namespace "jobsPage") for the label; the English `label` is the fallback for non-React callers. */
  labelKey: string;
  /** Tailwind classes for the status pill. */
  className: string;
  /** True while the row should keep refreshing. */
  live: boolean;
};

export function jobStatusTone(status: string | null | undefined): JobStatusTone {
  switch (status) {
    case "QUEUED":
      return { label: "Queued", labelKey: "status_QUEUED", className: "border-muted-foreground/30 text-muted-foreground", live: true };
    case "RUNNING":
      return { label: "Running", labelKey: "status_RUNNING", className: "border-primary/40 text-primary", live: true };
    case "AWAITING_APPROVAL":
      // --state-warning token (globals.css), not raw amber-500: matches the
      // dashboard's pending-approvals tile in meaning, and keeps AA contrast
      // in light theme where the raw Tailwind hue fell short (audit).
      return { label: "Awaiting approval", labelKey: "status_AWAITING_APPROVAL", className: "border-[color-mix(in_srgb,var(--state-warning)_40%,transparent)] text-[var(--state-warning)]", live: true };
    case "SUCCEEDED":
      return { label: "Succeeded", labelKey: "status_SUCCEEDED", className: "border-[color-mix(in_srgb,var(--state-success)_40%,transparent)] text-[var(--state-success)]", live: false };
    case "FAILED":
      return { label: "Failed", labelKey: "status_FAILED", className: "border-destructive/40 text-destructive", live: false };
    case "CANCELLED":
      return { label: "Cancelled", labelKey: "status_CANCELLED", className: "border-muted-foreground/30 text-muted-foreground", live: false };
    default:
      return { label: status || "Unknown", labelKey: "status_UNKNOWN", className: "border-muted-foreground/30 text-muted-foreground", live: false };
  }
}

/** Compact "3m 12s" / "1h 04m" duration between two instants. */
export function formatDuration(fromIso?: string | null, toIso?: string | null): string | null {
  if (!fromIso) return null;
  const from = new Date(fromIso).getTime();
  const to = toIso ? new Date(toIso).getTime() : Date.now();
  if (Number.isNaN(from) || Number.isNaN(to) || to < from) return null;
  const secs = Math.floor((to - from) / 1000);
  if (secs < 60) return `${secs}s`;
  const mins = Math.floor(secs / 60);
  if (mins < 60) return `${mins}m ${String(secs % 60).padStart(2, "0")}s`;
  return `${Math.floor(mins / 60)}h ${String(mins % 60).padStart(2, "0")}m`;
}

/** Locale-formatted absolute time; "—" for a null instant. */
export function formatWhen(iso?: string | null): string {
  if (!iso) return "—";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "—" : d.toLocaleString();
}

/** Localized pill text for a tone: the known statuses go through i18n, an unrecognised server status shows raw. */
export function jobStatusText(tone: JobStatusTone, t: (key: string) => string): string {
  return tone.labelKey === "status_UNKNOWN" ? tone.label : t(tone.labelKey);
}
