"use client";

import { Braces, ChevronDown, ChevronRight, Download, StopCircle } from "lucide-react";
import { Badge } from "../../../components/ui/badge";
import { Button } from "../../../components/ui/button";
import PaginationControls from "../../../components/PaginationControls";
import type { EvalCaseResult, EvalRunSummary } from "../evalDatasetsApi";
import JudgeTrustBadge from "../JudgeTrustBadge";
import TrajectoryRunDiffs from "../TrajectoryRunDiffs";
import { LIVE_STATUSES, deltaLabel, pct, statusBadge, traceLink, verdictBadge } from "./evalBadges";
import type { PageState } from "./pageState";
import { useTranslations } from "../../../lib/i18n";

/** Run history for one expanded dataset, with per-run case results. */
export default function RunHistory({
  agentId,
  datasetId,
  loaded,
  runs,
  runsPagination,
  runDetailId,
  caseResults,
  caseResultsPagination,
  onOpenRunDetail,
  onDownloadJunit,
  onDownloadRunJsonl,
  onCancelRun,
  simulatedTools = [],
}: {
  agentId: string | null;
  datasetId: string;
  /** True once the dataset detail has loaded (gates the "No runs yet" hint). */
  loaded: boolean;
  runs: EvalRunSummary[];
  runsPagination: PageState<EvalRunSummary>;
  runDetailId: string | null;
  caseResults: EvalCaseResult[] | null;
  caseResultsPagination: PageState<EvalCaseResult>;
  onOpenRunDetail: (runId: string) => void;
  onDownloadJunit: (runId: string) => void;
  onDownloadRunJsonl: (runId: string) => void;
  onCancelRun: (runId: string) => void;
  /** The tools the sandbox simulated in the open run (from its detail); empty for a quick run or when none was. */
  simulatedTools?: string[];
}) {
  const t = useTranslations("evalControls");
  // The turn records OK, FAILED, NOT_RUN, AWAITING_APPROVAL, REPLAYED or SIMULATED; an unknown value is shown as it came.
  const toolStatusLabel = (status: string): string => {
    const known: Record<string, string> = {
      OK: "toolStatusOk",
      FAILED: "toolStatusFailed",
      NOT_RUN: "toolStatusNotRun",
      AWAITING_APPROVAL: "toolStatusAwaitingApproval",
      REPLAYED: "toolStatusReplayed",
      SIMULATED: "toolStatusSimulated",
    };
    return known[status] ? t(known[status]) : status;
  };
  return (
    <div className="space-y-2">
      <h3 className="text-sm font-semibold text-foreground">Run history</h3>
      {loaded && runs.length === 0 && (
        <p className="text-sm text-muted-foreground">
          No runs yet — hit Run to replay every case and score it against the pass bar.
        </p>
      )}
      <div className="space-y-2">
        {runsPagination.pageItems.map((run) => (
          <div key={run.id} className="rounded-md border border-border">
            <div className="flex flex-wrap items-center gap-3 p-3">
              <button
                type="button"
                className="flex items-center gap-2 bg-transparent p-0 text-start text-sm text-foreground"
                onClick={() => onOpenRunDetail(run.id)}
              >
                {runDetailId === run.id ? (
                  <ChevronDown size={14} aria-hidden="true" />
                ) : (
                  <ChevronRight size={14} aria-hidden="true" />
                )}
                {new Date(run.createdAt).toLocaleString()}
              </button>
              {statusBadge(run.status)}
              {verdictBadge(run.verdict)}
              <span className="text-sm text-foreground">score {pct(run.overallScore)}</span>
              {deltaLabel(run)}
              <span className="text-xs text-muted-foreground">
                {run.scoredCaseCount}/{run.caseCount} scored
                {run.erroredCaseCount > 0 && ` · ${run.erroredCaseCount} errored`}
                {run.failedAssertionCount > 0 && ` · ${run.failedAssertionCount} tool checks failed`}
              </span>
              {run.judgeModel && <span className="text-xs text-muted-foreground">judge: {run.judgeModel}</span>}
              <JudgeTrustBadge info={run.judgeCalibration} />
              {(run.repeats ?? 1) > 1 && <span className="text-xs text-muted-foreground">×{run.repeats} reps</span>}
              {run.strictJudge && <span className="text-xs text-muted-foreground">strict judge</span>}
              {run.mode && (
                <Badge
                  variant={run.mode === "FULL" ? "accent" : "outline"}
                  title={t(run.mode === "FULL" ? "runModeFullTitle" : "runModeQuickTitle")}
                >
                  {t(run.mode === "FULL" ? "badgeFull" : "badgeQuick")}
                </Badge>
              )}
              {run.autoApproved && (
                <Badge variant="outline" title={t("runAutoApprovedTitle")}>
                  {t("runAutoApproved")}
                </Badge>
              )}
              {run.costUsd != null && <span className="text-xs text-muted-foreground">${run.costUsd}</span>}
              {traceLink(run.langfuseTraceUrl)}
              {!LIVE_STATUSES.includes(run.status) && (run.scoredCaseCount > 0 || run.erroredCaseCount > 0) && (
                <>
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => onDownloadJunit(run.id)}
                    title="Download JUnit XML (CI report)"
                  >
                    <Download size={13} aria-hidden="true" />
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => onDownloadRunJsonl(run.id)}
                    title="Download run as JSONL (prompt/optimizer input)"
                  >
                    <Braces size={13} aria-hidden="true" />
                  </Button>
                </>
              )}
              {LIVE_STATUSES.includes(run.status) && (
                <Button
                  variant="outline"
                  size="sm"
                  className="ms-auto"
                  onClick={() => onCancelRun(run.id)}
                  disabled={run.cancelRequested}
                >
                  <StopCircle size={13} aria-hidden="true" /> {run.cancelRequested ? "Cancelling…" : "Cancel"}
                </Button>
              )}
            </div>
            {run.errorMessage && (
              <p className="border-t border-border px-3 py-2 text-xs text-destructive">{run.errorMessage}</p>
            )}

            {runDetailId === run.id && (
              <div className="space-y-2 border-t border-border p-3">
                {caseResults === null && <p className="text-sm text-muted-foreground">Loading case results…</p>}
                {simulatedTools.length > 0 && (
                  <p className="text-xs text-muted-foreground" data-testid="run-simulated-tools">
                    {t("runSimulatedTools", { tools: simulatedTools.join(", ") })}
                  </p>
                )}
                {caseResultsPagination.pageItems.map((r) => (
                  <div key={r.id} className="rounded-md border border-border/60 p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-xs font-medium text-muted-foreground">#{r.position + 1}</span>
                      {r.passed === true && (
                        <Badge variant="state" state="var(--state-success)">
                          passed
                        </Badge>
                      )}
                      {r.passed === false && <Badge variant="destructive">failed</Badge>}
                      {r.status === "ERROR" ? (
                        <Badge variant="destructive">Error</Badge>
                      ) : r.status === "UNAVAILABLE" ? (
                        <Badge variant="secondary">Not scored</Badge>
                      ) : (
                        <Badge variant="outline">{pct(r.overallScore)}</Badge>
                      )}
                      {r.expectedToolsSatisfied === false && <Badge variant="destructive">tools missing</Badge>}
                      {r.expectedToolsSatisfied === true && (
                        <Badge variant="state" state="var(--state-success)">
                          tools ok
                        </Badge>
                      )}
                      {traceLink(r.langfuseTraceUrl)}
                    </div>
                    <p className="mt-1 text-sm text-foreground">{r.userMessage}</p>
                    {r.toolCalls?.length > 0 && (
                      <ul className="mt-1 flex flex-wrap gap-2" aria-label={t("toolCallsLabel")}>
                        {r.toolCalls.map((call, i) => (
                          <li key={i} className="text-xs text-muted-foreground">
                            {call.toolName ?? "?"}
                            {call.status ? ` (${toolStatusLabel(call.status)})` : ""}
                          </li>
                        ))}
                      </ul>
                    )}
                    {r.agentResponse && (
                      <p className="mt-1 line-clamp-3 text-xs text-muted-foreground">{r.agentResponse}</p>
                    )}
                    {r.errorMessage && <p className="mt-1 text-xs text-destructive">{r.errorMessage}</p>}
                    {Object.keys(r.scores).length > 0 && (
                      <div className="mt-2 flex flex-wrap gap-2">
                        {Object.entries(r.scores).map(([dim, s]) => (
                          <span key={dim} className="text-xs text-muted-foreground" title={s.feedback || undefined}>
                            {dim.toLowerCase()}: {s.score == null ? "n/a" : pct(s.score)}
                          </span>
                        ))}
                      </div>
                    )}
                  </div>
                ))}
                <PaginationControls
                  page={caseResultsPagination.page}
                  totalPages={caseResultsPagination.totalPages}
                  totalItems={caseResultsPagination.totalItems}
                  pageSize={caseResultsPagination.pageSize}
                  onPageChange={caseResultsPagination.setPage}
                  onPageSizeChange={caseResultsPagination.setPageSize}
                  itemLabel="results"
                />
                {agentId && <TrajectoryRunDiffs agentId={agentId} datasetId={datasetId} runId={run.id} />}
              </div>
            )}
          </div>
        ))}
        <PaginationControls
          page={runsPagination.page}
          totalPages={runsPagination.totalPages}
          totalItems={runsPagination.totalItems}
          pageSize={runsPagination.pageSize}
          onPageChange={runsPagination.setPage}
          onPageSizeChange={runsPagination.setPageSize}
          itemLabel="runs"
        />
      </div>
    </div>
  );
}
