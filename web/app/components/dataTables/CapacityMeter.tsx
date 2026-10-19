"use client";

import { cn } from "../../lib/utils";
import { stateColor } from "../charts/chartMath";
import type { Capacity } from "./capacity";

/**
 * One capacity bar: a `role="meter"` with real min / max / now values, and - whenever the level is "near" or "full" -
 * a visible TEXT label beside it. The colour change is an extra, never the only signal. Renders nothing when there
 * is no usable limit (an empty track would claim "0% of something").
 *
 * This is a meter against a fixed limit, which the shared chart kit does not have (its bars scale to the largest
 * value in the series), so it lives here rather than being bent out of RankedBars.
 */
export default function CapacityMeter({
  value,
  label,
  nearLabel,
  fullLabel,
  className,
}: {
  value: Capacity;
  /** Accessible name, e.g. "Orders: 812 of 100,000 rows used". */
  label: string;
  nearLabel: string;
  fullLabel: string;
  className?: string;
}) {
  if (value.rate === null) return null;
  const tone = value.level === "full" ? "error" : value.level === "near" ? "warning" : "running";
  const flag = value.level === "full" ? fullLabel : value.level === "near" ? nearLabel : null;
  // A non-empty table always shows a sliver, so "1 of 100,000" does not look like zero.
  const width = value.used > 0 ? Math.max(2, value.rate * 100) : 0;

  return (
    <span className={cn("flex min-w-0 items-center gap-2", className)} data-capacity-level={value.level}>
      <span
        role="meter"
        aria-label={label}
        aria-valuemin={0}
        aria-valuemax={value.max}
        aria-valuenow={Math.min(value.used, value.max)}
        aria-valuetext={flag ? `${label} - ${flag}` : label}
        className="block h-1.5 min-w-0 flex-1 overflow-hidden rounded-full bg-muted"
      >
        <span className="block h-full rounded-full" style={{ width: `${width.toFixed(2)}%`, background: stateColor(tone) }} />
      </span>
      {flag && (
        <span
          data-capacity-flag=""
          className="shrink-0 rounded-sm border px-1 text-[0.6875rem] font-semibold leading-4 text-foreground"
          style={{ borderColor: stateColor(tone) }}
        >
          {flag}
        </span>
      )}
    </span>
  );
}
