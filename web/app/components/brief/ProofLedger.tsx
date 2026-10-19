"use client";

import { FlaskConical, Loader2, Lock, ThumbsDown, ThumbsUp } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";
import { Alert, AlertDescription } from "../ui/alert";
import { Button } from "../ui/button";
import { ledgerView, pct, points, sparklinePoints, thumbsView, type RunView } from "./ledger";
import type { BriefLedger, LedgerExperiment } from "./types";

// The proof ledger: what each version scored, what changed, and whether the
// change is real. Every comparison carries its noise band; a difference inside
// the band reads "no measurable change" and shows no delta. A locked ledger
// shows only the reason it is locked. Nothing here estimates or fills a gap.

const STATUS_TOKEN: Record<string, string> = {
  DRAFT: "var(--state-idle)",
  RUNNING: "var(--state-running)",
  STOPPED: "var(--state-idle)",
};
const KNOWN_STATUS = new Set(["DRAFT", "RUNNING", "STOPPED"]);

/** Tiny inline sparkline: currentColor line, end point marked and labelled. No chart library. */
export function ScoreSparkline({ scores, label }: { scores: readonly (number | null)[]; label: string }) {
  const W = 120;
  const H = 32;
  const { points: pts, coords } = sparklinePoints(scores, W, H - 6);
  if (coords.length === 0) return null;
  const last = coords[coords.length - 1];
  return (
    <svg viewBox={`0 0 ${W} ${H}`} className="h-8 w-28 shrink-0 text-primary" role="img" aria-label={label} preserveAspectRatio="none">
      <g transform="translate(0 3)">
        {coords.length > 1 && (
          <polyline points={pts} fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" vectorEffect="non-scaling-stroke" />
        )}
        <circle cx={last.x} cy={last.y} r="2.4" fill="currentColor" vectorEffect="non-scaling-stroke" />
      </g>
    </svg>
  );
}

function RunRow({ run, onRunExperiment }: { run: RunView; onRunExperiment?: (version: number) => void }) {
  const t = useTranslations("brief");
  const band = run.band != null ? points(run.band).replace(/^[+-]/, "") : null;
  return (
    <li className="flex flex-wrap items-center gap-x-3 gap-y-1 border-b border-border py-2 text-sm last:border-b-0" data-verdict={run.verdict}>
      <span className="w-10 shrink-0 font-medium tabular-nums text-foreground">{t("ledger.version", { version: run.version })}</span>
      <span className="w-12 shrink-0 tabular-nums text-foreground">{run.score != null ? pct(run.score) : t("ledger.notScored")}</span>
      <span className="min-w-0 flex-1 text-muted-foreground">
        {run.verdict === "first" && t("ledger.first")}
        {run.verdict === "unmeasured" && t("ledger.unmeasured")}
        {run.verdict === "noChange" && (band != null ? t("ledger.noChangeBand", { band }) : t("ledger.noChange"))}
        {(run.verdict === "improved" || run.verdict === "regressed") && run.delta != null && (
          <span className="text-foreground">
            {t(run.verdict === "improved" ? "ledger.improved" : "ledger.regressed", { delta: points(run.delta) })}
            {band != null && <span className="text-muted-foreground">{` ${t("ledger.outsideBand", { band })}`}</span>}
          </span>
        )}
      </span>
      {onRunExperiment && run.verdict !== "first" && run.score != null && (
        <Button size="sm" variant="ghost" onClick={() => onRunExperiment(run.version)}>
          <FlaskConical className="size-3.5" aria-hidden="true" />
          {t("ledger.runExperiment")}
        </Button>
      )}
    </li>
  );
}

function Experiment({ e }: { e: LedgerExperiment }) {
  const t = useTranslations("brief");
  return (
    <li className="flex flex-wrap items-center gap-x-3 gap-y-1 py-1.5 text-sm">
      <span className="state-pill" style={{ ["--state" as string]: STATUS_TOKEN[e.status] ?? "var(--state-idle)" }}>
        <span className="state-dot" aria-hidden="true" />
        {KNOWN_STATUS.has(e.status) ? t(`ledger.experimentStatus.${e.status}`) : e.status}
      </span>
      <span className="text-foreground">{e.name || t("ledger.experimentUnnamed")}</span>
      {/* Core only sends a comparison when both arms have enough rated turns; its sentence is shown as written. */}
      {e.comparison?.significant && <span className="text-muted-foreground">{e.comparison.summary}</span>}
    </li>
  );
}

export default function ProofLedger({
  ledger,
  loading,
  error,
  onRetry,
  onRunExperiment,
  className,
}: {
  ledger: BriefLedger | null;
  loading?: boolean;
  error?: string | null;
  onRetry?: () => void;
  /** Hand-off to where a version is put into an experiment; the button is hidden when absent. */
  onRunExperiment?: (version: number) => void;
  className?: string;
}) {
  const t = useTranslations("brief");
  const view = ledger ? ledgerView(ledger) : null;

  return (
    <section className={cn("space-y-4 rounded-xl border border-border bg-card p-5", className)} aria-label={t("ledger.title")}>
      <div>
        <h3 className="text-base font-semibold text-foreground">{t("ledger.title")}</h3>
        <p className="text-sm text-muted-foreground">{t("ledger.subtitle")}</p>
      </div>

      {loading && !ledger && (
        <p className="flex items-center gap-2 text-sm text-muted-foreground">
          <Loader2 className="size-4 animate-spin" aria-hidden="true" />
          {t("ledger.loading")}
        </p>
      )}
      {error && (
        <Alert variant="destructive">
          <AlertDescription className="flex flex-wrap items-center gap-3">
            {t("ledger.loadFailed")}
            {onRetry && (
              <Button size="sm" variant="outline" onClick={onRetry}>
                {t("apply.retry")}
              </Button>
            )}
          </AlertDescription>
        </Alert>
      )}

      {view?.kind === "locked" && (
        <div className="flex items-start gap-3 rounded-lg border border-dashed border-border p-4">
          <Lock className="mt-0.5 size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
          <div className="space-y-1">
            {view.state === "LOCKED" && <p className="text-sm font-medium text-foreground">{t("ledger.locked")}</p>}
            <p className="text-sm text-muted-foreground">{view.reason ?? t("ledger.lockedNoReason")}</p>
          </div>
        </div>
      )}

      {view?.kind === "empty" && ledger && (
        <>
          <p className="text-sm text-muted-foreground">{view.reason ?? t("ledger.empty")}</p>
          <ThumbsLine ledger={ledger} />
        </>
      )}

      {view?.kind === "ready" && ledger && (
        <>
          <div className="flex flex-wrap items-center gap-x-6 gap-y-2">
            <div>
              <div className="text-xs text-muted-foreground">{t("ledger.baseline")}</div>
              <div className="text-2xl font-semibold tabular-nums text-foreground">{view.baseline != null ? pct(view.baseline) : t("ledger.notScored")}</div>
            </div>
            <ScoreSparkline scores={ledger.runs.map((r) => r.score)} label={t("ledger.sparkAria", { count: ledger.runs.length })} />
          </div>

          <ul aria-label={t("ledger.timeline")}>
            {[...view.runs].reverse().map((r, i) => (
              <RunRow key={`${r.version}-${i}`} run={r} onRunExperiment={onRunExperiment} />
            ))}
          </ul>

          <ThumbsLine ledger={ledger} />

          <div className="space-y-1">
            <h4 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">{t("ledger.experiments")}</h4>
            {ledger.experiments.length === 0 ? (
              <p className="text-sm text-muted-foreground">{t("ledger.noExperiments")}</p>
            ) : (
              <ul>
                {ledger.experiments.map((e) => (
                  <Experiment key={e.id} e={e} />
                ))}
              </ul>
            )}
          </div>
        </>
      )}
    </section>
  );
}

function ThumbsLine({ ledger }: { ledger: BriefLedger }) {
  const t = useTranslations("brief");
  const v = thumbsView(ledger.thumbs);
  return (
    <div className="flex items-center gap-2 text-sm text-muted-foreground">
      <ThumbsUp className="size-4 shrink-0" aria-hidden="true" />
      <ThumbsDown className="size-4 shrink-0" aria-hidden="true" />
      {v.kind === "none" && <span>{t("ledger.thumbsNone")}</span>}
      {v.kind === "few" && <span>{t("ledger.thumbsFew", { total: v.total })}</span>}
      {v.kind === "ratio" && <span className="text-foreground">{t("ledger.thumbsRatio", { ratio: pct(v.ratio), total: v.total })}</span>}
    </div>
  );
}
