"use client";

import { useMemo } from "react";
import { X } from "lucide-react";
import { formatDuration, type RunRow } from "./runHistoryModel";
import { useTranslations } from "../../lib/i18n";

const SPARK_MAX = 40;
const SPARK_W = 260;
const SPARK_H = 56;
const SPARK_PAD_X = 8;
const SPARK_PAD_Y = 9;

interface SparklineProps {
  rows: RunRow[];
  typical: number | null;
  selectedRunId: string | null;
  compareRunId: string | null;
  onPick: (id: string) => void;
}

/**
 * Duration trend, oldest on the left. Log-scaled so one 10-minute outlier
 * does not flatten every other run into the baseline. Version changes are
 * drawn as ticks on the timeline: "did my edit make it slower?" is readable
 * at a glance.
 */
export default function Sparkline({ rows, typical, selectedRunId, compareRunId, onPick }: SparklineProps) {
  const tHC = useTranslations("hcComponentsWorkflowRunHistorySparkline");
  const pts = useMemo(() => {
    const recent = rows.slice(0, SPARK_MAX).reverse();
    const ds = recent.map((r) => r.duration).filter((d): d is number => d != null && d > 0);
    const lo = Math.log(Math.max(1, Math.min(...(ds.length ? ds : [1]))));
    const hi = Math.log(Math.max(1, Math.max(...(ds.length ? ds : [1]))));
    const span = hi - lo || 1;
    const innerW = SPARK_W - SPARK_PAD_X * 2;
    const innerH = SPARK_H - SPARK_PAD_Y * 2;
    const yOf = (d: number) => SPARK_PAD_Y + innerH - ((Math.log(Math.max(1, d)) - lo) / span) * innerH;
    const step = recent.length > 1 ? innerW / (recent.length - 1) : 0;
    return {
      recent,
      yOf,
      points: recent.map((r, i) => ({
        row: r,
        x: recent.length > 1 ? SPARK_PAD_X + i * step : SPARK_W / 2,
        // Unknown duration (e.g. crashed before start) sits on the floor line.
        y: r.duration != null ? yOf(r.duration) : SPARK_H - SPARK_PAD_Y,
      })),
      step,
    };
  }, [rows]);

  const { points, yOf, step } = pts;
  const linePath = points
    .filter((p) => p.row.duration != null)
    .map((p, i) => `${i ? "L" : "M"}${p.x.toFixed(1)} ${p.y.toFixed(1)}`)
    .join(" ");
  const typicalY = typical != null ? yOf(typical) : null;
  const sel = points.find((p) => p.row.run.id === selectedRunId);
  const cmp = points.find((p) => p.row.run.id === compareRunId);

  const versionTicks: Array<{ x: number; v: number }> = [];
  for (let i = 1; i < points.length; i++) {
    const a = points[i - 1].row.run.workflowVersion;
    const b = points[i].row.run.workflowVersion;
    if (typeof a === "number" && typeof b === "number" && a !== b) {
      versionTicks.push({ x: points[i].x - step / 2, v: b });
    }
  }

  const failed = points.filter((p) => p.row.meta.tone === "error").length;
  const summary =
    `Duration of the last ${points.length} runs, oldest first.` +
    (typical != null ? ` Typical ${formatDuration(typical)}.` : "") +
    (failed ? ` ${failed} failed.` : "") +
    (versionTicks.length ? ` Workflow changed ${versionTicks.length} time${versionTicks.length > 1 ? "s" : ""}.` : "");

  return (
    <figure className="rh-spark">
      <svg viewBox={`0 0 ${SPARK_W} ${SPARK_H}`} role="img" aria-label={summary}>
        {versionTicks.map((t) => (
          <g key={`v-${t.x}`} className="rh-spark-ver">
            <line x1={t.x} x2={t.x} y1={2} y2={SPARK_H - 2} />
            <text x={t.x + 2} y={8}>
              v{t.v}
            </text>
          </g>
        ))}
        {typicalY != null && (
          <line className="rh-spark-typical" x1={SPARK_PAD_X} x2={SPARK_W - SPARK_PAD_X} y1={typicalY} y2={typicalY} />
        )}
        {sel && <line className="rh-spark-cursor" x1={sel.x} x2={sel.x} y1={2} y2={SPARK_H - 2} />}
        {cmp && <line className="rh-spark-cursor rh-spark-cursor-cmp" x1={cmp.x} x2={cmp.x} y1={2} y2={SPARK_H - 2} />}
        {linePath && <path className="rh-spark-line" d={linePath} />}
        {points.map((p) => {
          const tone = p.row.meta.tone;
          const isSel = p.row.run.id === selectedRunId;
          return (
            <g
              key={p.row.run.id}
              className="rh-spark-pt"
              data-tone={tone}
              data-selected={isSel || undefined}
              onClick={() => onPick(p.row.run.id)}
            >
              <title>{`#${p.row.flight} ${p.row.meta.label} · ${formatDuration(p.row.duration)}`}</title>
              {/* generous invisible hit target so tiny dots stay clickable */}
              <circle className="rh-spark-hit" cx={p.x} cy={p.y} r={7} />
              {tone === "error" ? (
                <path
                  className="rh-spark-x"
                  d={`M${p.x - 3} ${p.y - 3} L${p.x + 3} ${p.y + 3} M${p.x + 3} ${p.y - 3} L${p.x - 3} ${p.y + 3}`}
                />
              ) : tone === "running" ? (
                <circle className="rh-spark-live" cx={p.x} cy={p.y} r={3.2} />
              ) : (
                <circle className="rh-spark-dot" cx={p.x} cy={p.y} r={isSel ? 3.6 : 2.6} />
              )}
              {isSel && <circle className="rh-spark-ring" cx={p.x} cy={p.y} r={6} />}
            </g>
          );
        })}
      </svg>
      <figcaption className="rh-spark-legend">
        <span>
          <i className="rh-lg-line" aria-hidden="true" />
          typical {typical != null ? formatDuration(typical) : "-"}
        </span>
        {failed > 0 && (
          <span>
            <X size={10} strokeWidth={3} aria-hidden="true" className="rh-lg-x" />
            {failed} failed
          </span>
        )}
        {cmp && (
          <span>
            <i className="rh-lg-cmp" aria-hidden="true" />
            {tHC("s1")}
            {cmp.row.flight}
          </span>
        )}
      </figcaption>
    </figure>
  );
}
