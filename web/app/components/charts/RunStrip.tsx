"use client";

// The last N runs as a row of status marks, oldest to newest in the order
// given. It is a real list: screen readers get "<label>, list, N items" and
// each mark's own text. Each status has its own SHAPE as well as its colour
// (filled dot, square, diamond, ring, dashed ring, small dot), so the strip
// still reads in greyscale and for colour-blind users.
import type { CSSProperties, ReactElement } from "react";
import { cn } from "../../lib/utils";
import { stateColor, type ChartState } from "./chartMath";

export interface RunStripItem {
  /** Stable React key; defaults to the index. */
  id?: string;
  status: ChartState;
  /** Full text for this run, e.g. "Run 14: failed, 3 Oct 10:42". Tooltip + accessible name. */
  title: string;
}

export interface RunStripProps {
  runs: RunStripItem[];
  /** Caller-translated caption and list name, e.g. "last 20 runs". */
  label: string;
  /** Keep only the newest N (the tail of `runs`). */
  max?: number;
  /** Hide the visible caption (the list keeps its accessible name). Default false. */
  hideLabel?: boolean;
  className?: string;
}

function markStyle(status: ChartState): { className: string; style: CSSProperties } {
  const color = stateColor(status);
  switch (status) {
    case "success":
      return { className: "size-2.5 rounded-full", style: { background: color } };
    case "error":
      return { className: "size-2.5 rounded-[2px]", style: { background: color } };
    case "warning":
      return { className: "size-2 rotate-45 rounded-[1px]", style: { background: color } };
    case "running":
      return { className: "size-2.5 rounded-full border-2 bg-transparent", style: { borderColor: color } };
    case "queued":
      return { className: "size-2.5 rounded-full border border-dashed bg-transparent", style: { borderColor: color } };
    default:
      return { className: "size-1.5 rounded-full", style: { background: color } };
  }
}

/** Renders nothing for an empty list - use ChartFrame's empty state. */
export default function RunStrip({ runs, label, max, hideLabel = false, className }: RunStripProps): ReactElement | null {
  const all = Array.isArray(runs) ? runs : [];
  const cap = typeof max === "number" && Number.isFinite(max) && max > 0 ? Math.floor(max) : all.length;
  const shown = all.slice(Math.max(0, all.length - cap));
  if (shown.length === 0) return null;

  return (
    <div className={cn("min-w-0", className)}>
      <ul aria-label={label} className="flex list-none flex-wrap items-center gap-1 p-0">
        {shown.map((run, index) => {
          const mark = markStyle(run.status);
          return (
            <li key={run.id ?? index} className="flex size-3.5 items-center justify-center" data-run-status={run.status}>
              <span role="img" aria-label={run.title} title={run.title} className={cn("block shrink-0", mark.className)} style={mark.style} />
            </li>
          );
        })}
      </ul>
      {!hideLabel && (
        <p aria-hidden="true" className="mt-1 text-xs text-muted-foreground">
          {label}
        </p>
      )}
    </div>
  );
}
