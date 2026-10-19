"use client";

import { CheckCircle2, HelpCircle, XCircle } from "lucide-react";
import { formatCostUsd, formatDuration } from "./runModel";
import "./eval-badge.css";

/** One node's slice of GET /workflows/{id}/eval-summary (`nodes[nodeId]`). */
export type EvalNodeStats = {
  runs: number;
  successRate: number | null;
  p50DurationMs: number | null;
  avgCostUsd: number | null;
  lastStatus?: string | null;
  evalScore?: number | null;
  evalVerdict?: "PASSED" | "FAILED" | "INCONCLUSIVE" | null;
};

const pctFmt = new Intl.NumberFormat(undefined, { style: "percent", maximumFractionDigits: 0 });
const intFmt = new Intl.NumberFormat();
const scoreFmt = new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 });

// Core reports rates as 0..1; tolerate 0..100 so a unit change never renders "9200%".
const ratio = (v: number) => Math.max(0, Math.min(1, v > 1 ? v / 100 : v));

function band(rate: number): "ok" | "warn" | "bad" {
  if (rate >= 0.95) return "ok";
  if (rate >= 0.8) return "warn";
  return "bad";
}

const VERDICT = {
  PASSED: { word: "Pass", long: "passed", Icon: CheckCircle2, tone: "ok" },
  FAILED: { word: "Fail", long: "failed", Icon: XCircle, tone: "bad" },
  INCONCLUSIVE: { word: "Unsure", long: "inconclusive", Icon: HelpCircle, tone: "warn" },
} as const;

function formatScore(score: number): string {
  return score >= 0 && score <= 1 ? pctFmt.format(score) : scoreFmt.format(score);
}

const R = 5;
const C = 2 * Math.PI * R;

// Tiny node-footer chip: success ring + %, and an eval verdict pill when the
// node has been scored. Full numbers live in the tooltip / aria-label.
export default function EvalBadge({ stats, compact = false }: { stats: EvalNodeStats; compact?: boolean }) {
  const hasScore = typeof stats.evalScore === "number" && Number.isFinite(stats.evalScore);
  if ((!stats.runs || stats.runs <= 0) && !hasScore) return null;

  const rate = typeof stats.successRate === "number" && Number.isFinite(stats.successRate) ? ratio(stats.successRate) : null;
  const verdict = stats.evalVerdict ? VERDICT[stats.evalVerdict] : null;

  const parts: string[] = [];
  if (stats.runs > 0) {
    parts.push(`${intFmt.format(stats.runs)} ${stats.runs === 1 ? "run" : "runs"}`);
    parts.push(rate == null ? "success rate unknown" : `${pctFmt.format(rate)} success`);
    if (stats.p50DurationMs != null) parts.push(`median ${formatDuration(stats.p50DurationMs)}`);
    if (stats.avgCostUsd != null) parts.push(`avg cost ${formatCostUsd(stats.avgCostUsd)}`);
    if (stats.lastStatus) parts.push(`last ${stats.lastStatus.toLowerCase()}`);
  }
  if (hasScore) {
    parts.push(`eval ${verdict ? verdict.long : "score"} ${formatScore(stats.evalScore as number)}`);
  }
  const label = parts.join(" · ");

  return (
    <span className="wfx-evalb" data-compact={compact ? "true" : undefined} role="img" aria-label={label} title={label}>
      {stats.runs > 0 && (
        <span className="wfx-evalb-rate" data-band={rate == null ? "none" : band(rate)} aria-hidden="true">
          <svg className="wfx-evalb-ring" viewBox="0 0 14 14" width="12" height="12" focusable="false">
            <circle className="wfx-evalb-track" cx="7" cy="7" r={R} />
            {rate != null && (
              <circle
                className="wfx-evalb-fill"
                cx="7"
                cy="7"
                r={R}
                strokeDasharray={`${(rate * C).toFixed(2)} ${C.toFixed(2)}`}
                transform="rotate(-90 7 7)"
              />
            )}
          </svg>
          <span className="wfx-evalb-num">{rate == null ? "—" : pctFmt.format(rate)}</span>
          {!compact && stats.p50DurationMs != null && (
            <span className="wfx-evalb-dim">{formatDuration(stats.p50DurationMs)}</span>
          )}
        </span>
      )}
      {hasScore && (
        <span className="wfx-evalb-pill" data-tone={verdict ? verdict.tone : "none"} aria-hidden="true">
          {verdict && <verdict.Icon size={10} />}
          {compact ? (verdict ? verdict.word : formatScore(stats.evalScore as number)) : `${verdict ? `${verdict.word} ` : "Eval "}${formatScore(stats.evalScore as number)}`}
        </span>
      )}
    </span>
  );
}
