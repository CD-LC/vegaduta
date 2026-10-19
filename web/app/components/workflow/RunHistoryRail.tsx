"use client";

/**
 * Run history rail - the "flight log" beside the canvas. Every run is a
 * flight: a duration sparkline shows how the workflow has been flying lately
 * (with the moments it was edited marked on the timeline), and the list below
 * is keyboard-driven so a run can be opened or pinned for comparison without
 * touching the mouse.
 */

import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import type { KeyboardEvent, ReactNode } from "react";
import { GitCompareArrows, History, Play, RefreshCw, X } from "lucide-react";
import Sparkline from "./RunHistorySparkline";
import {
  durationOf,
  formatDuration,
  median,
  relativeTime,
  startOfDay,
  statusMeta,
  toMs,
  type RunRow,
  type RunSummary,
} from "./runHistoryModel";
import "./run-history.css";

export type { RunSummary } from "./runHistoryModel";

export interface RunHistoryRailProps {
  runs: RunSummary[];
  selectedRunId: string | null;
  onSelectRun: (id: string) => void;
  loading?: boolean;
  onRefresh?: () => void;
  compareRunId?: string | null;
  /** Toggle a run as the comparison baseline; called with the same id to clear. */
  onCompare?: (id: string) => void;
  /** Empty-state action when no `children` slot is given. */
  onRunNow?: () => void;
  /** Empty-state slot (e.g. the canvas's own Run button). */
  children?: ReactNode;
  /** Pinned below the list (e.g. the selected run's story). */
  footer?: ReactNode;
  className?: string;
}

export default function RunHistoryRail({
  runs,
  selectedRunId,
  onSelectRun,
  loading = false,
  onRefresh,
  compareRunId = null,
  onCompare,
  onRunNow,
  children,
  footer,
  className,
}: RunHistoryRailProps) {
  const uid = useId();
  const listRef = useRef<HTMLDivElement>(null);
  const toggleRef = useRef<HTMLButtonElement>(null);
  const [mobileOpen, setMobileOpen] = useState(false);
  const [activeId, setActiveId] = useState<string | null>(selectedRunId);
  const [now, setNow] = useState(() => Date.now());

  const hasLive = runs.some((r) => statusMeta(r.status).tone === "running");

  // Tick fast only while something is in flight (live duration); otherwise a
  // slow tick keeps "3 minutes ago" honest without re-rendering every second.
  useEffect(() => {
    const t = window.setInterval(() => setNow(Date.now()), hasLive ? 1000 : 60_000);
    return () => window.clearInterval(t);
  }, [hasLive]);

  const rtf = useMemo(() => new Intl.RelativeTimeFormat(undefined, { numeric: "auto" }), []);

  const { rows, typical, latestVersion } = useMemo(() => {
    const sorted = [...runs].sort((a, b) => (toMs(b.startedAt) ?? 0) - (toMs(a.startedAt) ?? 0));
    const finishedDurations = sorted
      .filter((r) => statusMeta(r.status).tone === "ok")
      .map((r) => durationOf(r, now))
      .filter((d): d is number => d != null);
    const typ = median(finishedDurations);
    let latest: number | null = null;
    for (const r of sorted) {
      if (typeof r.workflowVersion === "number") latest = Math.max(latest ?? r.workflowVersion, r.workflowVersion);
    }
    const out: RunRow[] = sorted.map((run, i) => {
      const duration = durationOf(run, now);
      const meta = statusMeta(run.status);
      // Need a few samples before "slow" means anything; 1.8x keeps noise out.
      const slow =
        typ && duration != null && finishedDurations.length >= 4 && meta.tone !== "running" && duration > typ * 1.8
          ? duration / typ
          : null;
      return { run, flight: sorted.length - i, meta, startMs: toMs(run.startedAt), duration, slowFactor: slow };
    });
    return { rows: out, typical: typ, latestVersion: latest };
  }, [runs, now]);

  const groups = useMemo(() => {
    const today = startOfDay(now);
    const yesterday = today - 86_400_000;
    const buckets: Array<{ key: string; label: string; rows: RunRow[] }> = [
      { key: "today", label: "Today", rows: [] },
      { key: "yesterday", label: "Yesterday", rows: [] },
      { key: "earlier", label: "Earlier", rows: [] },
    ];
    for (const row of rows) {
      const s = row.startMs ?? 0;
      buckets[s >= today ? 0 : s >= yesterday ? 1 : 2].rows.push(row);
    }
    return buckets.filter((b) => b.rows.length);
  }, [rows, now]);

  // Flat order matches visual order so arrow keys walk exactly what is seen.
  const order = useMemo(() => groups.flatMap((g) => g.rows.map((r) => r.run.id)), [groups]);

  // Follow external selection (e.g. a fresh run auto-selected by the canvas).
  useEffect(() => {
    if (selectedRunId) setActiveId(selectedRunId);
  }, [selectedRunId]);

  const effectiveActive = activeId && order.includes(activeId) ? activeId : order[0] ?? null;
  const optionId = (id: string) => `${uid}-run-${id}`;

  useEffect(() => {
    if (!effectiveActive) return;
    const el = document.getElementById(optionId(effectiveActive));
    el?.scrollIntoView({ block: "nearest" });
    // optionId is derived from the stable uid
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [effectiveActive]);

  const select = useCallback(
    (id: string) => {
      setActiveId(id);
      onSelectRun(id);
    },
    [onSelectRun],
  );

  const onListKey = (e: KeyboardEvent<HTMLDivElement>) => {
    if (!order.length) return;
    const idx = effectiveActive ? order.indexOf(effectiveActive) : -1;
    const move = (to: number) => {
      e.preventDefault();
      setActiveId(order[Math.max(0, Math.min(order.length - 1, to))]);
    };
    switch (e.key) {
      case "ArrowDown":
        return move(idx + 1);
      case "ArrowUp":
        return move(idx - 1);
      case "Home":
        return move(0);
      case "End":
        return move(order.length - 1);
      case "PageDown":
        return move(idx + 8);
      case "PageUp":
        return move(idx - 8);
      case "Enter":
      case " ":
        if (effectiveActive) {
          e.preventDefault();
          select(effectiveActive);
        }
        return;
      case "c":
      case "C":
        if (onCompare && effectiveActive && effectiveActive !== selectedRunId) {
          e.preventDefault();
          onCompare(effectiveActive);
        }
        return;
      case "Escape":
        if (mobileOpen) {
          e.preventDefault();
          setMobileOpen(false);
          toggleRef.current?.focus();
        }
        return;
      default:
    }
  };

  const failedCount = rows.filter((r) => r.meta.tone === "error").length;
  const hintId = `${uid}-hint`;
  const panelId = `${uid}-panel`;

  return (
    <aside
      className={`rh-rail${className ? ` ${className}` : ""}`}
      data-open={mobileOpen ? "true" : "false"}
      aria-label="Run history"
    >
      <button
        ref={toggleRef}
        type="button"
        className="rh-toggle"
        aria-expanded={mobileOpen}
        aria-controls={panelId}
        onClick={() => setMobileOpen((o) => !o)}
      >
        <History size={15} aria-hidden="true" />
        <span>Runs</span>
        {runs.length > 0 && <span className="rh-count">{runs.length}</span>}
        {failedCount > 0 && (
          <span className="rh-toggle-fail" aria-label={`${failedCount} failed`}>
            <X size={10} aria-hidden="true" strokeWidth={3} />
            {failedCount}
          </span>
        )}
      </button>

      <div className="rh-panel" id={panelId} aria-busy={loading || undefined}>
        <header className="rh-head">
          <History size={15} aria-hidden="true" className="rh-head-icon" />
          <h2 className="rh-title">Flight log</h2>
          {runs.length > 0 && <span className="rh-sub">{runs.length} runs</span>}
          {onRefresh && (
            <button
              type="button"
              className="rh-icon-btn"
              onClick={onRefresh}
              disabled={loading}
              aria-label={loading ? "Refreshing runs" : "Refresh runs"}
              title="Refresh"
            >
              <RefreshCw size={14} aria-hidden="true" className={loading ? "rh-spin" : undefined} />
            </button>
          )}
          <button
            type="button"
            className="rh-icon-btn rh-close"
            onClick={() => {
              setMobileOpen(false);
              toggleRef.current?.focus();
            }}
            aria-label="Close run history"
          >
            <X size={14} aria-hidden="true" />
          </button>
        </header>

        {rows.length > 0 && (
          <Sparkline
            rows={rows}
            typical={typical}
            selectedRunId={selectedRunId}
            compareRunId={compareRunId}
            onPick={select}
          />
        )}

        {loading && rows.length === 0 ? (
          <div className="rh-skeleton" aria-label="Loading runs" role="status">
            {[0, 1, 2, 3].map((i) => (
              <div key={i} className="rh-skel-row" />
            ))}
          </div>
        ) : rows.length === 0 ? (
          <div className="rh-empty">
            <svg className="rh-empty-art" viewBox="0 0 120 40" aria-hidden="true">
              <path className="rh-empty-runway" d="M4 32 H116" />
              <path className="rh-empty-path" d="M10 32 C 40 32, 60 10, 110 8" />
              <circle className="rh-empty-dot" cx="110" cy="8" r="3" />
            </svg>
            <p className="rh-empty-line">No flights logged yet.</p>
            <p className="rh-empty-sub">Every run lands here with its full black-box trace, ready to replay.</p>
            {children ??
              (onRunNow && (
                <button type="button" className="rh-run-now" onClick={onRunNow}>
                  <Play size={14} aria-hidden="true" />
                  Run now
                </button>
              ))}
          </div>
        ) : (
          <>
            <div
              ref={listRef}
              className="rh-list"
              role="listbox"
              tabIndex={0}
              aria-label="Workflow runs"
              aria-describedby={hintId}
              aria-activedescendant={effectiveActive ? optionId(effectiveActive) : undefined}
              onKeyDown={onListKey}
            >
              {groups.map((g) => (
                <div key={g.key} role="group" aria-labelledby={`${uid}-g-${g.key}`} className="rh-group">
                  <div className="rh-group-label" id={`${uid}-g-${g.key}`}>
                    {g.label}
                  </div>
                  {g.rows.map((row) => (
                    <RunOption
                      key={row.run.id}
                      row={row}
                      domId={optionId(row.run.id)}
                      selected={row.run.id === selectedRunId}
                      active={row.run.id === effectiveActive}
                      comparing={row.run.id === compareRunId}
                      latestVersion={latestVersion}
                      when={relativeTime(rtf, row.startMs, now)}
                      onSelect={() => select(row.run.id)}
                      onCompare={
                        onCompare && row.run.id !== selectedRunId ? () => onCompare(row.run.id) : undefined
                      }
                    />
                  ))}
                </div>
              ))}
            </div>
            <p className="rh-hint" id={hintId}>
              <kbd>↑</kbd>
              <kbd>↓</kbd> move · <kbd>Enter</kbd> open
              {onCompare ? (
                <>
                  {" "}
                  · <kbd>C</kbd> compare
                </>
              ) : null}
            </p>
          </>
        )}
        {footer}
      </div>
    </aside>
  );
}

interface RunOptionProps {
  row: RunRow;
  domId: string;
  selected: boolean;
  active: boolean;
  comparing: boolean;
  latestVersion: number | null;
  when: string;
  onSelect: () => void;
  onCompare?: () => void;
}

function RunOption({ row, domId, selected, active, comparing, latestVersion, when, onSelect, onCompare }: RunOptionProps) {
  const { run, meta, flight, duration, slowFactor } = row;
  const Icon = meta.Icon;
  const v = typeof run.workflowVersion === "number" ? run.workflowVersion : null;
  const stale = v != null && latestVersion != null && v < latestVersion;
  const exactTime = row.startMs != null ? new Date(row.startMs).toLocaleString() : undefined;

  return (
    // eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/interactive-supports-focus -- listbox option driven by aria-activedescendant on the list; keys are handled there
    <div
      id={domId}
      role="option"
      aria-selected={selected}
      data-active={active || undefined}
      data-tone={meta.tone}
      data-comparing={comparing || undefined}
      className="rh-row"
      onClick={onSelect}
    >
      <span className="rh-status" data-tone={meta.tone}>
        <Icon size={15} aria-hidden="true" className={meta.tone === "running" ? "rh-spin" : undefined} />
      </span>
      <span className="rh-main">
        <span className="rh-line1">
          <span className="rh-flight">#{flight}</span>
          <span className="rh-status-text" data-tone={meta.tone}>
            {meta.label}
          </span>
          {v != null && (
            <span className="rh-ver" data-stale={stale || undefined}>
              v{v}
            </span>
          )}
        </span>
        <span className="rh-line2">
          <time dateTime={run.startedAt ?? undefined} title={exactTime}>
            {when}
          </time>
          <span aria-hidden="true">·</span>
          <span className="rh-dur" data-live={meta.tone === "running" || undefined}>
            <span className="rh-sr">Duration </span>
            {formatDuration(duration)}
          </span>
          {slowFactor != null && (
            <span className="rh-slow" title="Compared with the median of successful runs">
              {slowFactor.toFixed(1)}× typical
            </span>
          )}
        </span>
        {stale && (
          <span className="rh-changed">
            Workflow changed since - now v{latestVersion}
          </span>
        )}
        {comparing && <span className="rh-compare-tag">Comparing</span>}
      </span>
      {onCompare && (
        // Pointer affordance only: interactive children inside role=option are
        // not reachable by AT, so keyboard users get the "C" shortcut instead.
        <button
          type="button"
          tabIndex={-1}
          aria-hidden="true"
          className="rh-compare-btn"
          data-on={comparing || undefined}
          title={comparing ? "Stop comparing" : "Compare with selected run"}
          onClick={(e) => {
            e.stopPropagation();
            onCompare();
          }}
        >
          <GitCompareArrows size={14} />
        </button>
      )}
    </div>
  );
}
