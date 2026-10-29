"use client";

// Vertical bars over time (runs per day, tokens per hour...). Deliberately
// axis-free: the readout line under the bars names the hovered / focused bar,
// and the enclosing ChartFrame carries the summary sentence and table view.
//
// `partialLast` marks the last bar as an incomplete period: it is drawn
// hatched with a dashed outline instead of solid, and the caller's
// `partialLabel` ("so far") is appended to its title and readout, so a
// half-finished day is never read as a drop.
import { useId, useState, type ReactElement } from "react";
import { cn } from "../../lib/utils";
import { useChartLabel } from "./chartContext";
import { formatCompact, safeMax, toMagnitude } from "./chartMath";

export interface MiniBarDatum {
  /** Period label, e.g. "Mon" or "3 Oct". Shown in the title, readout and end labels. */
  label: string;
  value: number;
  /** Pre-formatted value ("1.2k runs"). Defaults to a compact number. */
  valueText?: string;
}

export interface MiniBarsProps {
  data: MiniBarDatum[];
  /** The last bar is an incomplete period: hatched + dashed outline. */
  partialLast?: boolean;
  /** Caller-translated marker for the partial bar, e.g. "so far". */
  partialLabel?: string;
  /** Show the hovered / focused bar's "label: value" under the chart. Default true. */
  showValueLabels?: boolean;
  /** Show the first and last period labels under the bars. Default true. */
  showEndLabels?: boolean;
  /** Bar colour (any CSS colour / token). Default: the theme primary. */
  color?: string;
  /** Bar area height in px. Default 96. */
  height?: number;
  /** Overrides the ChartFrame summary as the SVG's accessible name. */
  ariaLabel?: string;
  className?: string;
}

const SLOT = 10; // viewBox units per bar slot
const BAR = 7; // bar width inside the slot
const VIEW_H = 100;
const MIN_BAR = 2; // a zero period still shows a stub, so the slot is visibly there

export default function MiniBars({
  data,
  partialLast = false,
  partialLabel,
  showValueLabels = true,
  showEndLabels = true,
  color = "var(--primary)",
  height = 96,
  ariaLabel,
  className,
}: MiniBarsProps): ReactElement | null {
  const label = useChartLabel(ariaLabel);
  const [active, setActive] = useState<number | null>(null);
  // useId can contain characters that are not safe inside url(#...).
  const hatchId = `mini-bars-hatch-${useId().replace(/[^a-zA-Z0-9_-]/g, "")}`;

  const bars = Array.isArray(data) ? data : [];
  if (bars.length === 0) return null;

  const max = safeMax(bars.map((bar) => toMagnitude(bar.value)));
  const lastIndex = bars.length - 1;
  const textOf = (index: number) => {
    const bar = bars[index];
    const value = bar.valueText ?? formatCompact(toMagnitude(bar.value)) ?? "0";
    const partial = partialLast && index === lastIndex && partialLabel ? ` (${partialLabel})` : "";
    return `${bar.label}: ${value}${partial}`;
  };

  return (
    <div className={cn("min-w-0 text-foreground", className)}>
      <svg
        role="img"
        aria-label={label}
        viewBox={`0 0 ${bars.length * SLOT} ${VIEW_H}`}
        preserveAspectRatio="none"
        className="block w-full overflow-visible"
        style={{ height }}
        onMouseLeave={() => setActive(null)}
      >
        <defs>
          <pattern id={hatchId} width="3" height="3" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
            <line x1="0" y1="0" x2="0" y2="3" stroke={color} strokeWidth="1.4" />
          </pattern>
        </defs>
        <line
          x1="0"
          y1={VIEW_H}
          x2={bars.length * SLOT}
          y2={VIEW_H}
          stroke="currentColor"
          strokeOpacity="0.2"
          strokeWidth="1"
          vectorEffect="non-scaling-stroke"
        />
        {bars.map((bar, index) => {
          const scaled = max > 0 ? (toMagnitude(bar.value) / max) * (VIEW_H - 4) : 0;
          const barHeight = Math.max(MIN_BAR, scaled);
          const partial = partialLast && index === lastIndex;
          const isActive = active === index;
          return (
            <rect
              key={index}
              data-bar=""
              data-partial={partial ? "true" : undefined}
              tabIndex={0}
              x={index * SLOT + (SLOT - BAR) / 2}
              y={VIEW_H - barHeight}
              width={BAR}
              height={barHeight}
              rx="1"
              fill={partial ? `url(#${hatchId})` : color}
              fillOpacity={partial || isActive || active === null ? 1 : 0.55}
              stroke={partial ? color : isActive ? "currentColor" : "none"}
              strokeWidth={partial || isActive ? 1.5 : 0}
              strokeDasharray={partial ? "3 2" : undefined}
              vectorEffect="non-scaling-stroke"
              className="cursor-default outline-none focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-ring"
              onMouseEnter={() => setActive(index)}
              onFocus={() => setActive(index)}
              onBlur={() => setActive((current) => (current === index ? null : current))}
            >
              <title>{textOf(index)}</title>
            </rect>
          );
        })}
      </svg>
      {showEndLabels && (
        <div className="mt-1 flex justify-between gap-2 text-xs text-muted-foreground">
          <span className="min-w-0 truncate">{bars[0].label}</span>
          {bars.length > 1 && (
            <span className="min-w-0 truncate text-end">
              {bars[lastIndex].label}
              {partialLast && partialLabel ? ` (${partialLabel})` : ""}
            </span>
          )}
        </div>
      )}
      {showValueLabels && (
        // Fixed-height line so the layout does not jump when a bar is hovered.
        <p
          data-bar-readout=""
          aria-hidden="true"
          className="mt-1 min-h-4 truncate text-xs font-medium tabular-nums text-foreground"
        >
          {active !== null && active <= lastIndex ? textOf(active) : ""}
        </p>
      )}
    </div>
  );
}
