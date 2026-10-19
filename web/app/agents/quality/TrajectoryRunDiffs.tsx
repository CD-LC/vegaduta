"use client";

import { useCallback, useEffect, useState } from "react";
import { ChevronDown, ChevronRight } from "lucide-react";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { api } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import {
  metricEntries,
  normalizeReport,
  pctText,
  statusTone,
  type MetricEntry,
  type TrajectoryReportView,
} from "./trajectoryEval";
import { useTrajectoryConfig } from "./useTrajectoryConfig";

interface RunCase {
  resultId: string;
  caseId: string | null;
  position: number;
  userMessage: string;
  passed: boolean | null;
  overallScore: number | null;
  report: TrajectoryReportView | null;
}

const STATUS_KEYS: Record<string, string> = {
  match: "statusMatch",
  out_of_order: "statusOutOfOrder",
  args_mismatch: "statusArgsMismatch",
  missing: "statusMissing",
  extra: "statusExtra",
};

const METRIC_KEYS: Record<string, string> = {
  exactMatch: "metricExactMatch",
  inOrderMatch: "metricInOrderMatch",
  anyOrderMatch: "metricAnyOrderMatch",
  toolPrecision: "metricToolPrecision",
  toolRecall: "metricToolRecall",
  toolF1: "metricToolF1",
  argMatchRate: "metricArgMatchRate",
  extraCalls: "metricExtraCalls",
  extraCallPenalty: "metricExtraCallPenalty",
  efficiency: "metricEfficiency",
  failedCalls: "metricFailedCalls",
};

const MODE_KEYS: Record<string, string> = {
  exact: "modeExact",
  in_order: "modeInOrder",
  any_order: "modeAnyOrder",
  f1: "modeF1",
};

/**
 * Expected-vs-actual trajectory of every trajectory-graded case of one finished eval run, mounted under the
 * run's case results. The data is the report stored with each case result (metrics + a row-by-row alignment),
 * read from /quality/trajectory-eval; nothing is recomputed here. Matched steps, steps found out of order or with
 * the wrong arguments, missing steps and extra calls are highlighted differently (colour from the state tokens,
 * and always paired with a text label).
 *
 * Renders nothing unless the feature is on and the run has trajectory-graded cases - an absent optional surface
 * must not look like an error.
 */
export default function TrajectoryRunDiffs({
  agentId,
  datasetId,
  runId,
}: {
  agentId: string;
  datasetId: string;
  runId: string;
}) {
  const t = useTranslations("trajectoryEval");
  const config = useTrajectoryConfig(agentId);
  const [cases, setCases] = useState<RunCase[] | null>(null);
  const [open, setOpen] = useState(false);
  const [confirming, setConfirming] = useState<string | null>(null);
  const [note, setNote] = useState<{ kind: "error" | "saved"; text: string } | null>(null);
  const [busy, setBusy] = useState(false);

  const enabled = !!config;

  useEffect(() => {
    if (!enabled) return undefined;
    let cancelled = false;
    setCases(null);
    Promise.resolve(api.getTrajectoryEvalRun(agentId, datasetId, runId))
      .then((raw: any) => {
        if (cancelled) return;
        const list: RunCase[] = [];
        for (const c of raw && Array.isArray(raw.cases) ? raw.cases : []) {
          list.push({
            resultId: String(c.resultId),
            caseId: c.caseId ? String(c.caseId) : null,
            position: typeof c.position === "number" ? c.position : list.length,
            userMessage: typeof c.userMessage === "string" ? c.userMessage : "",
            passed: typeof c.passed === "boolean" ? c.passed : null,
            overallScore: typeof c.overallScore === "number" ? c.overallScore : null,
            report: normalizeReport(c.report),
          });
        }
        setCases(list);
      })
      .catch(() => {
        if (!cancelled) setCases([]);
      });
    return () => {
      cancelled = true;
    };
  }, [enabled, agentId, datasetId, runId]);

  const adoptActual = useCallback(
    async (c: RunCase) => {
      if (!c.caseId || busy) return;
      setBusy(true);
      setNote(null);
      try {
        const actual: any = await api.getTrajectoryEvalActual(agentId, datasetId, runId, c.resultId);
        await api.setTrajectoryEvalCase(agentId, datasetId, c.caseId, actual ? actual.suggestedSpec : null);
        setNote({ kind: "saved", text: t("usedActual") });
        setConfirming(null);
      } catch (err: any) {
        setNote({ kind: "error", text: err?.message || t("saveFailed") });
      } finally {
        setBusy(false);
      }
    },
    [agentId, datasetId, runId, busy, t]
  );

  if (!enabled || !cases) return null;
  const graded = cases.filter((c) => c.report);
  if (graded.length === 0) return null;
  const passedCount = graded.filter((c) => c.report && c.report.passed).length;

  function formatMetric(entry: MetricEntry): string {
    if (entry.kind === "bool") return entry.value ? t("yes") : t("no");
    if (entry.kind === "pct") return pctText(entry.value as number | null);
    return String(entry.value ?? 0);
  }

  return (
    <div className="mt-2 rounded-md border border-border" data-testid={`trajectory-run-${runId}`}>
      <button
        type="button"
        className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm font-medium text-foreground"
        aria-expanded={open}
        onClick={() => setOpen(!open)}
      >
        {open ? <ChevronDown size={14} aria-hidden="true" /> : <ChevronRight size={14} aria-hidden="true" />}
        {t("diffTitle")}
        <span className="text-xs font-normal text-muted-foreground">
          {t("diffSummary", { passed: String(passedCount), total: String(graded.length) })}
        </span>
      </button>

      {open && (
        <div className="space-y-3 border-t border-border p-3">
          {note && (
            <p
              className={note.kind === "error" ? "text-xs text-destructive" : "text-xs text-muted-foreground"}
              role={note.kind === "error" ? "alert" : "status"}
            >
              {note.text}
            </p>
          )}
          {graded.map((c) => {
            const r = c.report as TrajectoryReportView;
            return (
              <section key={c.resultId} className="space-y-2 rounded-md border border-border/60 p-3" data-testid={`trajectory-case-${c.resultId}`}>
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-xs font-medium text-muted-foreground">{t("caseHeading", { n: String(c.position + 1) })}</span>
                  {r.passed ? (
                    <Badge variant="state" state="var(--state-success)">
                      {t("passed")}
                    </Badge>
                  ) : (
                    <Badge variant="destructive">{t("failed")}</Badge>
                  )}
                  <span className="text-xs text-muted-foreground">
                    {t("scoreLine", { score: pctText(r.score), min: pctText(r.minScore) })}
                  </span>
                  <span className="text-xs text-muted-foreground">{t(MODE_KEYS[r.mode] || "modeInOrder")}</span>
                </div>
                {c.userMessage && <p className="line-clamp-2 text-xs text-foreground">{c.userMessage}</p>}

                <div className="grid gap-3 sm:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
                  <div>
                    <h4 className="mb-1 text-xs font-semibold text-foreground">{t("expectedColumn")}</h4>
                    {r.expected.length === 0 ? (
                      <p className="text-xs text-muted-foreground">{t("noExpected")}</p>
                    ) : (
                      <ol className="space-y-1">
                        {r.expected.map((row) => {
                          const tone = statusTone(row.status);
                          return (
                            <li
                              key={row.index}
                              className="rounded-sm border-l-4 bg-muted/30 px-2 py-1 text-xs"
                              style={{ borderLeftColor: tone.cssVar }}
                              data-status={row.status}
                            >
                              <span className="text-muted-foreground">#{row.index + 1}</span>{" "}
                              <code className="font-mono text-foreground">{row.tool}</code>{" "}
                              <Badge variant="state" state={tone.cssVar}>
                                {t(STATUS_KEYS[row.status] || "statusMissing")}
                              </Badge>
                              {row.actualIndex != null && (
                                <span className="ml-1 text-muted-foreground">{t("pairedWith", { n: String(row.actualIndex + 1) })}</span>
                              )}
                              {row.detail && <div className="mt-0.5 text-muted-foreground">{row.detail}</div>}
                            </li>
                          );
                        })}
                      </ol>
                    )}
                  </div>
                  <div>
                    <h4 className="mb-1 text-xs font-semibold text-foreground">{t("actualColumn")}</h4>
                    {r.actual.length === 0 ? (
                      <p className="text-xs text-muted-foreground">{t("noActual")}</p>
                    ) : (
                      <ol className="space-y-1">
                        {r.actual.map((row) => {
                          const tone = statusTone(row.status);
                          return (
                            <li
                              key={row.index}
                              className="rounded-sm border-l-4 bg-muted/30 px-2 py-1 text-xs"
                              style={{ borderLeftColor: tone.cssVar }}
                              data-status={row.status}
                            >
                              <span className="text-muted-foreground">#{row.index + 1}</span>{" "}
                              <code className="font-mono text-foreground">{row.tool}</code>{" "}
                              <Badge variant="state" state={tone.cssVar}>
                                {t(STATUS_KEYS[row.status] || "statusExtra")}
                              </Badge>
                              {!row.ok && <span className="ml-1 text-destructive">{t("callFailed")}</span>}
                              {row.expectedIndex != null && (
                                <span className="ml-1 text-muted-foreground">{t("pairedWith", { n: String(row.expectedIndex + 1) })}</span>
                              )}
                              {row.args && (
                                <code className="mt-0.5 block truncate font-mono text-muted-foreground" title={row.args}>
                                  {row.args}
                                </code>
                              )}
                            </li>
                          );
                        })}
                      </ol>
                    )}
                    {r.actualTruncated && <p className="mt-1 text-xs text-muted-foreground">{t("truncated", { max: "200" })}</p>}
                  </div>
                </div>

                <div>
                  <h4 className="mb-1 text-xs font-semibold text-foreground">{t("metricsTitle")}</h4>
                  <dl className="grid grid-cols-2 gap-x-4 gap-y-0.5 text-xs sm:grid-cols-[repeat(3,minmax(0,1fr))]">
                    {metricEntries(r.metrics).map((entry) => (
                      <div key={entry.key} className="flex justify-between gap-2">
                        <dt className="text-muted-foreground">{t(METRIC_KEYS[entry.key])}</dt>
                        <dd className="font-medium text-foreground">{formatMetric(entry)}</dd>
                      </div>
                    ))}
                  </dl>
                </div>

                {c.caseId && (
                  <div className="flex flex-wrap items-center gap-2">
                    {confirming === c.resultId ? (
                      <>
                        <span className="text-xs text-muted-foreground">{t("useActualConfirm")}</span>
                        <Button type="button" size="sm" disabled={busy} onClick={() => void adoptActual(c)}>
                          {t("useActualYes")}
                        </Button>
                        <Button type="button" size="sm" variant="outline" disabled={busy} onClick={() => setConfirming(null)}>
                          {t("cancel")}
                        </Button>
                      </>
                    ) : (
                      <Button type="button" size="sm" variant="outline" onClick={() => setConfirming(c.resultId)}>
                        {t("useActual")}
                      </Button>
                    )}
                  </div>
                )}
              </section>
            );
          })}
        </div>
      )}
    </div>
  );
}
