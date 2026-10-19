import { AlertTriangle, ScrollText, XCircle } from "lucide-react";
import { formatClock, formatDuration, intFmt, isNum } from "./format";
import { STATUS_META } from "./status";
import type { InspectorVisit, LevelFilter, LogLevel, StatusKey } from "./types";

export function SummaryPanel({ visit, sKey, durationMs, isLive, visitLabel, logCounts, onJump }: {
  visit: InspectorVisit; sKey: StatusKey; durationMs: number | null; isLive: boolean; visitLabel: string;
  logCounts: Record<LogLevel, number>; onJump: (f: LevelFilter) => void;
}) {
  const meta = STATUS_META[sKey];
  const summary = visit.summary?.trim();
  return (
    <div className="ri-summary">
      <dl className="ri-readout">
        <div>
          <dt>Status</dt>
          <dd data-status={sKey}>
            <meta.Icon size={13} aria-hidden="true" className={sKey === "running" ? "ri-spin" : undefined} />
            {meta.label}
          </dd>
        </div>
        <div>
          <dt>Duration</dt>
          <dd className="ri-num">
            {durationMs === null ? "-" : formatDuration(durationMs)}
            {isLive ? <span className="ri-live">live</span> : null}
          </dd>
        </div>
        <div>
          <dt>Started</dt>
          <dd className="ri-num">{isNum(visit.startMs) ? formatClock(visit.startMs) : "-"}</dd>
        </div>
        <div>
          <dt>Visit</dt>
          <dd>{visitLabel}</dd>
        </div>
      </dl>

      {summary ? (
        <blockquote className="ri-quote" data-status={sKey}>
          {summary}
        </blockquote>
      ) : (
        <p className="ri-hint">{isLive ? "This step is still running; its summary appears when it finishes." : "This step reported no summary."}</p>
      )}

      {logCounts.error + logCounts.warn + logCounts.info > 0 ? (
        <div className="ri-levels" role="group" aria-label="Log lines by level">
          {logCounts.error > 0 ? (
            <button type="button" className="ri-level" data-level="error" onClick={() => onJump("error")}>
              <XCircle size={12} aria-hidden="true" /> {intFmt.format(logCounts.error)} error{logCounts.error === 1 ? "" : "s"}
            </button>
          ) : null}
          {logCounts.warn > 0 ? (
            <button type="button" className="ri-level" data-level="warn" onClick={() => onJump("warn")}>
              <AlertTriangle size={12} aria-hidden="true" /> {intFmt.format(logCounts.warn)} warning{logCounts.warn === 1 ? "" : "s"}
            </button>
          ) : null}
          <button type="button" className="ri-level" data-level="info" onClick={() => onJump("all")}>
            <ScrollText size={12} aria-hidden="true" /> All {intFmt.format(logCounts.error + logCounts.warn + logCounts.info)} lines
          </button>
        </div>
      ) : null}
    </div>
  );
}
