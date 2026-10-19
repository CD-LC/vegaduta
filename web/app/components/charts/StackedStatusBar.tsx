"use client";

// One horizontal bar split into status segments (succeeded / failed /
// waiting...), with a text legend underneath: every segment is listed as
// "label count", so the split can be read without seeing colour at all. The
// segments are also separated by a gap, not just by hue.
import { useId, type ReactElement } from "react";
import { cn } from "../../lib/utils";
import { useChartLabel } from "./chartContext";
import { formatPercent, stateColor, toMagnitude, type ChartState } from "./chartMath";

export interface StatusSegment {
  /** Stable React key; defaults to the label. */
  key?: string;
  label: string;
  count: number;
  /** One of the `--state-*` tokens. */
  state?: ChartState;
  /** Explicit CSS colour / token; wins over `state`. */
  color?: string;
  /** Pre-formatted count for the legend. Defaults to the number. */
  countText?: string;
}

export interface StackedStatusBarProps {
  segments: StatusSegment[];
  /** Append each segment's share ("82%") in the legend. Default false. */
  showPercent?: boolean;
  /** Hide the text legend (only when the caller renders its own). Default true = shown. */
  showLegend?: boolean;
  /** Overrides the ChartFrame summary as the SVG's accessible name. */
  ariaLabel?: string;
  className?: string;
}

const WIDTH = 400;
const HEIGHT = 12;
const GAP = 2;

/** Renders nothing when every count is zero / invalid - use ChartFrame's empty state. */
export default function StackedStatusBar({
  segments,
  showPercent = false,
  showLegend = true,
  ariaLabel,
  className,
}: StackedStatusBarProps): ReactElement | null {
  const label = useChartLabel(ariaLabel);
  const clipId = `status-bar-${useId().replace(/[^a-zA-Z0-9_-]/g, "")}`;

  const all = (Array.isArray(segments) ? segments : []).map((segment) => ({
    ...segment,
    count: toMagnitude(segment.count),
    fill: segment.color ?? stateColor(segment.state),
  }));
  const total = all.reduce((acc, segment) => acc + segment.count, 0);
  if (total <= 0) return null;

  const drawn = all.filter((segment) => segment.count > 0);
  const drawable = WIDTH - GAP * (drawn.length - 1);
  let x = 0;
  const rects = drawn.map((segment) => {
    const width = (segment.count / total) * drawable;
    const rect = { segment, x, width };
    x += width + GAP;
    return rect;
  });

  return (
    <div className={cn("min-w-0", className)}>
      <svg role="img" aria-label={label} viewBox={`0 0 ${WIDTH} ${HEIGHT}`} preserveAspectRatio="none" className="block h-3 w-full">
        <defs>
          {/* Rounds the two outer ends only; the joins between segments stay square. */}
          <clipPath id={clipId}>
            <rect x="0" y="0" width={WIDTH} height={HEIGHT} rx={HEIGHT / 2} />
          </clipPath>
        </defs>
        <g clipPath={`url(#${clipId})`}>
          {rects.map(({ segment, x: rectX, width }) => (
            <rect key={segment.key ?? segment.label} data-segment="" x={rectX} y="0" width={width} height={HEIGHT} fill={segment.fill}>
              <title>{`${segment.label}: ${segment.countText ?? segment.count}`}</title>
            </rect>
          ))}
        </g>
      </svg>
      {showLegend && (
        <ul className="mt-2 flex list-none flex-wrap gap-x-4 gap-y-1 p-0 text-xs text-muted-foreground">
          {all.map((segment) => (
            <li key={segment.key ?? segment.label} className="flex min-w-0 items-center gap-1.5">
              <span aria-hidden="true" className="inline-block size-2.5 shrink-0 rounded-sm" style={{ background: segment.fill }} />
              <span className="min-w-0 truncate">{segment.label}</span>
              <span className="font-medium tabular-nums text-foreground">{segment.countText ?? segment.count}</span>
              {showPercent && <span className="tabular-nums">{formatPercent(segment.count / total)}</span>}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
