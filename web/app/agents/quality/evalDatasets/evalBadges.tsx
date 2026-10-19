import { ExternalLink } from "lucide-react";
import { Badge } from "../../../components/ui/badge";
import { cn } from "../../../lib/utils";
import type { EvalRunSummary } from "../evalDatasetsApi";

export const LIVE_STATUSES = ["QUEUED", "RUNNING"];

export function pct(score: number | null | undefined): string {
  return score == null ? "—" : `${Math.round(score * 100)}%`;
}

export function verdictBadge(verdict: string | null) {
  if (!verdict) return null;
  if (verdict === "PASSED") {
    return (
      <Badge variant="state" state="var(--state-success)">
        Passed
      </Badge>
    );
  }
  if (verdict === "FAILED") {
    return <Badge variant="destructive">Failed</Badge>;
  }
  return <Badge variant="secondary">Inconclusive</Badge>;
}

export function statusBadge(status: string) {
  if (status === "RUNNING") return <Badge variant="accent">Running</Badge>;
  if (status === "QUEUED") return <Badge variant="secondary">Queued</Badge>;
  if (status === "SUCCEEDED") return <Badge variant="outline">Completed</Badge>;
  if (status === "CANCELLED") return <Badge variant="secondary">Cancelled</Badge>;
  return <Badge variant="destructive">Failed</Badge>;
}

/** Deep link to the replay's Langfuse trace - only rendered when core sent
 *  one (see langfuseTraceUrl's doc in evalDatasetsApi.ts). Guarded to http(s)
 *  so a malformed value from a misconfigured base URL can never become a
 *  javascript: href. */
export function traceLink(url: string | null | undefined) {
  if (!url || !/^https?:\/\//i.test(url)) return null;
  return (
    <a
      href={url}
      target="_blank"
      rel="noreferrer noopener"
      className="inline-flex items-center gap-1 text-xs text-primary underline-offset-2 hover:underline"
      title="Open this replay's trace in Langfuse"
    >
      Trace <ExternalLink size={11} aria-hidden="true" />
    </a>
  );
}

export function deltaLabel(run: EvalRunSummary) {
  if (run.scoreDelta == null) {
    // No baseline = nothing to compare against (first measured run) — an
    // explicit dash, never a fabricated 0 (the backend's TokenBaseline rule).
    return <span className="text-muted-foreground">—</span>;
  }
  const up = run.scoreDelta >= 0;
  return (
    <span className={cn("font-medium", up ? "text-[var(--state-success)]" : "text-destructive")}>
      {up ? "▲" : "▼"} {Math.abs(Math.round(run.scoreDelta * 100))}pt
    </span>
  );
}
