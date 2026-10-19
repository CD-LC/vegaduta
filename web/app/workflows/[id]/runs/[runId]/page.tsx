"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import {
  ScrollText,
  Bot,
  Wrench,
  Code2,
  GitBranch,
  ArrowRightCircle,
  Download,
  ArrowLeft,
  ChevronRight,
  Check,
  X as XIcon,
  Minus,
  Pause,
  Pin,
  Square,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { api } from "../../../../lib/api";
import AppShell from "../../../../components/AppShell";
import PageHeader from "../../../../components/ax/PageHeader";
import { Section, EmptyState } from "../../../../components/ax/page-kit";
import { Button } from "../../../../components/ui/button";
import { Badge } from "../../../../components/ui/badge";
import { Card } from "../../../../components/ui/card";
import { cn } from "../../../../lib/utils";
import { useTranslations } from "../../../../lib/i18n";
import PaginationControls from "../../../../components/PaginationControls";
import { usePagination } from "../../../../lib/usePagination";
import { errorMessage } from "../../../../lib/errors";

// Full run transparency page - direct feedback: "after running the workflow
// the logs should get printed in a separate page, on what agent is doing,
// code is doing - everything should be transparent to consumer." This is
// that page: every node the run touched, in order, fully expanded by
// default (not collapsed behind a click the way the canvas's inline "Last
// run" panel keeps them) - the complete, honest record of what happened,
// not a summary of it.
//
// Premium redesign - the bespoke .ax-int-card/.ax-wf-step-*/.ax-wf-raw-*
// log chrome is rebuilt on the shared PageHeader + page-kit Section +
// token-driven Cards. The Steps/Raw-trace tab strip is a clean segmented
// control; the log viewer keeps its standard level-tagged, line-numbered
// convention (Papertrail/PatternFly), just retinted onto theme tokens.
const NODE_TYPE_ICON: Record<string, LucideIcon> = {
  agent: Bot,
  tool: Wrench,
  code: Code2,
  condition: GitBranch,
};

/** One WorkflowExecutionEngine trace entry, as returned by
 * GET /api/workflows/{id}/runs/{runId} - each represents one node the run
 * touched, in order. `logLines` is free text (no structured level field -
 * see classifyLogLine's own comment above for why this page classifies it
 * client-side from real substrings instead). */
interface WorkflowTraceStep {
  nodeId?: string;
  nodeType?: string;
  status?: string;
  label?: string;
  summary?: string;
  logLines?: string[];
}

// Direct feedback: "logs should be displayed in logger way - search internet
// to understand how they do it." Standard log-viewer convention (Papertrail,
// PatternFly's log viewer, every console-log formatter) is a short color-
// coded level tag ahead of the message. The backend (WorkflowExecutionEngine)
// doesn't send a structured level - each logLine is just free text - so this
// classifies from the same real substrings the backend already guarantees
// show up in the line ("Tool call:", "Calling agent"/"Agent:", "ERROR:"/
// "failed:"). It's a display heuristic over real text, never a fabricated
// field - a line that matches none of these just reads as INFO. Duplicated
// (not imported) from WorkflowCanvas.js on purpose - see that file's own
// comment on its copy for why.
function classifyLogLine(line: string): "error" | "tool" | "agent" | "info" {
  if (/ERROR:|failed:/i.test(line)) return "error";
  if (/^Tool call:/i.test(line)) return "tool";
  if (/^(Agent:|Calling agent|Agent replied)/i.test(line)) return "agent";
  return "info";
}

const LOG_LEVEL_KEY = { error: "logLevelError", tool: "logLevelTool", agent: "logLevelAgent", info: "logLevelInfo" };

// Token-driven tint per log level. One violet accent for the "signal" levels
// (tool/agent), a semantic red for errors, muted for plain info - disciplined
// rather than a rainbow of hardcoded hex.
const LOG_LEVEL_CLASS = {
  error: "text-destructive",
  tool: "text-primary",
  agent: "text-primary",
  info: "text-muted-foreground",
};

function NodeTypeIcon({ nodeType }: { nodeType?: string }) {
  const Icon = (nodeType && NODE_TYPE_ICON[nodeType]) || ArrowRightCircle;
  return <Icon size={13} aria-hidden="true" />;
}

// Step status → a small colored disc with a glyph, mirroring the canvas run
// inspector's dot language but on theme-following semantic tints.
function StepStatusDot({ status }: { status?: string }) {
  const s = (status || "").toUpperCase();
  const map: Record<string, { cls: string; glyph: any }> = {
    OK: {
      cls: "border-[color-mix(in_srgb,var(--state-success)_30%,transparent)] bg-[color-mix(in_srgb,var(--state-success)_15%,transparent)] text-[var(--state-success)]",
      glyph: <Check size={12} />,
    },
    ERROR: { cls: "border-destructive/30 bg-destructive/15 text-destructive", glyph: <XIcon size={12} /> },
    SKIPPED: { cls: "border-border bg-secondary text-muted-foreground", glyph: <Minus size={12} /> },
    // Draft run only: a pin answered for this node, it did not execute. Success-like tint, pin glyph.
    PINNED: {
      cls: "border-[color-mix(in_srgb,var(--state-success)_30%,transparent)] bg-[color-mix(in_srgb,var(--state-success)_15%,transparent)] text-[var(--state-success)]",
      glyph: <Pin size={12} />,
    },
    AWAITING_APPROVAL: {
      cls: "border-[color-mix(in_srgb,var(--state-warning)_30%,transparent)] bg-[color-mix(in_srgb,var(--state-warning)_15%,transparent)] text-[var(--state-warning)]",
      glyph: <Pause size={11} />,
    },
    // Same parked-on-human shape for the other two halt statuses - a halted
    // node's trace entry carries haltRun's name as its status.
    AWAITING_INPUT: {
      cls: "border-[color-mix(in_srgb,var(--state-warning)_30%,transparent)] bg-[color-mix(in_srgb,var(--state-warning)_15%,transparent)] text-[var(--state-warning)]",
      glyph: <Pause size={11} />,
    },
    AWAITING_VOICE_INPUT: {
      cls: "border-[color-mix(in_srgb,var(--state-warning)_30%,transparent)] bg-[color-mix(in_srgb,var(--state-warning)_15%,transparent)] text-[var(--state-warning)]",
      glyph: <Pause size={11} />,
    },
    // Stop Run - neutral "stopped", not a failure (that's ERROR's red above).
    CANCELLED: { cls: "border-border bg-secondary text-muted-foreground", glyph: <Square size={10} /> },
  };
  const entry = map[s] || { cls: "border-border bg-secondary text-muted-foreground", glyph: null };
  return (
    <span
      role="img"
      aria-label={status || "unknown"}
      title={status || "unknown"}
      className={cn("flex size-6 shrink-0 items-center justify-center rounded-full border", entry.cls)}
    >
      {entry.glyph}
    </span>
  );
}

function statusVariant(status: string | undefined): "default" | "secondary" | "outline" | "destructive" {
  const s = (status || "").toUpperCase();
  if (s === "COMPLETED" || s === "OK") return "default";
  if (s === "FAILED" || s === "ERROR") return "destructive";
  if (
    s === "RUNNING" ||
    s === "AWAITING_APPROVAL" ||
    s === "AWAITING_INPUT" ||
    s === "AWAITING_VOICE_INPUT" ||
    s === "PAUSED"
  )
    return "secondary";
  // Stop Run - explicit, not just falling through to the same "outline"
  // default every unrecognized status would also land on - documents that
  // CANCELLED was actually considered, not merely uncaught.
  if (s === "CANCELLED") return "outline";
  return "outline";
}

// A run is still "live" (worth polling for) exactly while it hasn't reached
// one of WorkflowRunStatus's terminal values. Terminal is COMPLETED/FAILED/
// CANCELLED. Everything else can still move: RUNNING is mid-traversal, and
// AWAITING_APPROVAL / AWAITING_INPUT / AWAITING_VOICE_INPUT / PAUSED are all
// parked-on-human states that flip back to RUNNING the moment the approval,
// form submission, DTMF digit or Resume lands (same compareAndSetStatus CAS
// server-side) - treating any of them as terminal left this page frozen on
// the halt snapshot even after the run resumed and finished.
function isLiveStatus(status: string | undefined): boolean {
  const s = (status || "").toUpperCase();
  return (
    s === "RUNNING" ||
    s === "AWAITING_APPROVAL" ||
    s === "AWAITING_INPUT" ||
    s === "AWAITING_VOICE_INPUT" ||
    s === "PAUSED"
  );
}

export default function WorkflowRunDetailPage() {
  const t = useTranslations("workflowRunDetailPage");
  const params = useParams<{ id: string; runId: string }>();
  const workflowId = params.id;
  const runId = params.runId;
  const [workflow, setWorkflow] = useState<any>(null);
  const [run, setRun] = useState<any>(null);
  const [error, setError] = useState<string | null>(null);
  const [downloading, setDownloading] = useState(false);
  const [tab, setTab] = useState("steps");
  // Every row starts expanded (true transparency, not click-to-reveal) -
  // collapsedRows tracks the exceptions a person has explicitly folded
  // away, rather than the other way around.
  const [collapsedRows, setCollapsedRows] = useState<Record<number, boolean>>({});

  useEffect(() => {
    let cancelled = false;
    api
      .getWorkflow(workflowId)
      .then((wf) => !cancelled && setWorkflow(wf))
      .catch(() => {});
    api
      .getWorkflowRun(workflowId, runId)
      .then((r) => !cancelled && setRun(r))
      .catch((err) => !cancelled && setError(err.message || t("couldNotLoadRun")));
    return () => {
      cancelled = true;
    };
  }, [workflowId, runId, t]);

  // Fail-proof/monitoring hardening: this page previously fetched the run
  // exactly once on mount. WorkflowExecutionEngine writes this run's trace
  // incrementally after every node specifically so a concurrent GET
  // .../runs/{runId} poll shows real, growing progress (see that class's own
  // "Real-time log viewer (#393)" comment) - the canvas's own inline "Run"
  // panel already polls on exactly this contract (WorkflowCanvas.tsx's
  // runWithRuntimeInputs), but this history/detail page (the one you land on
  // from the Runs list, a notification, or a bookmark - not the canvas
  // session that triggered the run) never did, so a run still in flight when
  // this page loads - or one that transitions to RUNNING/AWAITING_APPROVAL
  // while you're already looking at it - was frozen at whatever snapshot the
  // initial fetch happened to catch, with no way to see it progress or land
  // without a manual page reload. Same 1200ms interval as the canvas poll;
  // stops itself the moment the run reaches a terminal status.
  useEffect(() => {
    if (!isLiveStatus(run?.status)) return undefined;
    let cancelled = false;
    const poll = async () => {
      if (cancelled) return;
      try {
        const updated = await api.getWorkflowRun(workflowId, runId);
        if (!cancelled) setRun(updated);
      } catch {
        // Transient poll failure - the next tick retries; not worth
        // surfacing as an error banner over what's otherwise a healthy page.
      }
    };
    const timer = setInterval(() => {
      void poll();
    }, 1200);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [workflowId, runId, run?.status]);

  const downloadOutput = useCallback(async () => {
    if (!run) return;
    setDownloading(true);
    try {
      const { blob, filename } = await api.downloadWorkflowRunOutput(workflowId, run.id);
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = filename;
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
    } catch (err) {
      setError(errorMessage(err, t("couldNotDownload")));
    } finally {
      setDownloading(false);
    }
  }, [run, workflowId, t]);

  const trace: WorkflowTraceStep[] = run?.trace || [];
  // Long-running workflows touch far more than ten nodes - the Steps tab
  // pages through them while the Raw tab stays a single scrollable
  // transcript. collapsedRows is keyed by each step's index in the FULL
  // trace (computed as a global index below), not its position on the
  // current page, so folding a row survives page changes.
  const stepsPagination = usePagination(trace);

  return (
    <AppShell eyebrow={t("eyebrow")}>
      <PageHeader
        title={workflow?.name ? `${workflow.name} — Run ${runId.slice(0, 8)}` : `Run ${runId.slice(0, 8)}`}
        actions={
          <Button asChild variant="outline" size="sm">
            <Link href={`/workflows/${workflowId}/runs`}>
              <ArrowLeft size={14} /> {t("allRuns")}
            </Link>
          </Button>
        }
      />

      {error && (
        <div className="mb-6 rounded-xl border border-destructive/30 bg-destructive/10 px-4 py-3 text-sm text-destructive">
          {error}
        </div>
      )}

      {!error && !run && <p className="text-sm text-muted-foreground">{t("loading")}</p>}

      {run && (
        <div className="space-y-8">
          {/* Run summary */}
          <Section title={t("runSummaryLabel")}>
            <Card className="gap-0 p-5 sm:p-6">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <Badge variant={statusVariant(run.status)} className="px-3 py-1 text-sm">
                  <span
                    className={cn(
                      "mr-1.5 inline-block size-1.5 rounded-full bg-current",
                      isLiveStatus(run.status) && "animate-pulse"
                    )}
                  />
                  {run.status}
                </Badge>
                {run.outputFileName && (
                  <Button type="button" size="sm" onClick={downloadOutput} disabled={downloading}>
                    <Download size={14} aria-hidden="true" /> {downloading ? t("preparing") : run.outputFileName}
                  </Button>
                )}
              </div>
              <div className="mt-4 flex flex-wrap items-center gap-x-2 gap-y-1 text-sm text-muted-foreground tabular-nums">
                {run.startedAt && (
                  <span>
                    {t("startedPrefix")} {new Date(run.startedAt).toLocaleString()}
                  </span>
                )}
                {run.startedAt && run.finishedAt && (
                  <span aria-hidden="true" className="text-muted-foreground">
                    ·
                  </span>
                )}
                {run.finishedAt && (
                  <span>
                    {t("finishedPrefix")} {new Date(run.finishedAt).toLocaleString()}
                  </span>
                )}
              </div>
              {run.errorMessage && (
                <div className="mt-4 rounded-lg border border-destructive/30 bg-destructive/10 px-3.5 py-2.5 text-sm text-destructive">
                  {run.errorMessage}
                </div>
              )}
            </Card>
          </Section>

          {/* Step-by-step trace */}
          <Section title={t("stepByStepLabel")} description={t("stepByStepDescription")}>
            {/* Segmented tab control */}
            <div className="inline-flex items-center gap-0.5 rounded-lg border border-border bg-secondary/40 p-0.5">
              {[
                { id: "steps", label: t("stepsTab") },
                { id: "raw", label: t("rawTraceTab") },
              ].map((tb) => (
                <button
                  key={tb.id}
                  type="button"
                  onClick={() => setTab(tb.id)}
                  aria-pressed={tab === tb.id}
                  className={cn(
                    "rounded-md px-3.5 py-1.5 text-sm font-medium transition-colors",
                    tab === tb.id
                      ? "bg-background text-foreground shadow-sm"
                      : "text-muted-foreground hover:text-foreground"
                  )}
                >
                  {tb.label}
                </button>
              ))}
            </div>

            {tab === "steps" &&
              (trace.length === 0 ? (
                <EmptyState icon={ScrollText} title={t("noSteps")} />
              ) : (
                <div className="space-y-2.5">
                  {stepsPagination.pageItems.map((step, pageIndex) => {
                    const i = (stepsPagination.page - 1) * stepsPagination.pageSize + pageIndex;
                    const hasLogLines = (step.logLines || []).length > 0;
                    const collapsed = !!collapsedRows[i];
                    return (
                      <Card key={i} className="gap-0 overflow-hidden p-0">
                        <button
                          type="button"
                          disabled={!hasLogLines}
                          aria-expanded={hasLogLines ? !collapsed : undefined}
                          className={cn(
                            "flex w-full items-start gap-3 px-4 py-3.5 text-left disabled:cursor-default",
                            hasLogLines && "cursor-pointer transition-colors hover:bg-accent/50"
                          )}
                          onClick={() => hasLogLines && setCollapsedRows((prev) => ({ ...prev, [i]: !prev[i] }))}
                        >
                          <StepStatusDot status={step.status} />
                          <div className="min-w-0 flex-1">
                            <div className="flex items-center gap-1.5 text-sm font-medium text-foreground">
                              <NodeTypeIcon nodeType={step.nodeType} />
                              <span className="truncate">{step.label || step.nodeId}</span>
                              <span className="font-normal text-muted-foreground">· {step.nodeType}</span>
                            </div>
                            {step.summary && <div className="mt-0.5 text-sm text-muted-foreground">{step.summary}</div>}
                          </div>
                          {hasLogLines && (
                            <ChevronRight
                              size={16}
                              className={cn(
                                "mt-0.5 shrink-0 text-muted-foreground transition-transform",
                                !collapsed && "rotate-90"
                              )}
                            />
                          )}
                        </button>
                        {hasLogLines && !collapsed && (
                          <div className="border-t border-border bg-secondary/30 px-4 py-3 font-mono text-xs">
                            {step.logLines.map((line, j) => {
                              const level = classifyLogLine(line);
                              return (
                                <div key={j} className="flex gap-3 py-0.5 leading-relaxed">
                                  <span className="w-6 shrink-0 select-none text-right text-muted-foreground tabular-nums">
                                    {j + 1}
                                  </span>
                                  <span className={cn("w-12 shrink-0 font-semibold uppercase", LOG_LEVEL_CLASS[level])}>
                                    {t(LOG_LEVEL_KEY[level])}
                                  </span>
                                  <span className="min-w-0 break-words text-foreground/90">{line}</span>
                                </div>
                              );
                            })}
                          </div>
                        )}
                      </Card>
                    );
                  })}
                  <PaginationControls
                    page={stepsPagination.page}
                    totalPages={stepsPagination.totalPages}
                    totalItems={stepsPagination.totalItems}
                    pageSize={stepsPagination.pageSize}
                    onPageChange={stepsPagination.setPage}
                    onPageSizeChange={stepsPagination.setPageSize}
                    itemLabel={t("itemLabelSteps")}
                  />
                </div>
              ))}

            {/* Raw trace tab - same flattened, chronological, never-capped
             * transcript as the canvas panel's Raw trace tab: every real
             * logLine from every trace entry, in order, falling back to
             * the entry's own summary for any step with no logLines. */}
            {tab === "raw" && (
              <Card className="gap-0 overflow-hidden p-0">
                <div className="max-h-[80vh] overflow-auto px-4 py-3 font-mono text-xs">
                  {trace.length === 0 && <p className="text-muted-foreground">{t("noEvents")}</p>}
                  {trace.flatMap((step, i) => {
                    const lines =
                      step.logLines && step.logLines.length > 0 ? step.logLines : [step.summary || step.status];
                    return lines.map((line, j) => {
                      const level = classifyLogLine(line);
                      return (
                        <div className="flex gap-3 py-0.5 leading-relaxed" key={`${step.nodeId || "node"}-${i}-${j}`}>
                          <span className="w-28 shrink-0 truncate text-muted-foreground">
                            {step.label || step.nodeId}
                          </span>
                          <span className={cn("w-12 shrink-0 font-semibold uppercase", LOG_LEVEL_CLASS[level])}>
                            {t(LOG_LEVEL_KEY[level])}
                          </span>
                          <span className="min-w-0 break-words text-foreground/90">{line}</span>
                        </div>
                      );
                    });
                  })}
                </div>
              </Card>
            )}
          </Section>
        </div>
      )}
    </AppShell>
  );
}
