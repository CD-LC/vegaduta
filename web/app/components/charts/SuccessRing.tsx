"use client";

// A donut with one value in the middle (success rate, budget used...).
//
// The one rule that matters: a null rate is "no data", and it is drawn as a
// dashed empty ring with the caller's placeholder in the centre - never as
// 0% (which reads as "everything failed") and never as 100%.
//
// Colour is never the only cue: the ring always sits next to a text `label`,
// and an optional `statusLabel` (+ `icon`) spells out what the colour means.
import type { ReactElement, ReactNode } from "react";
import { cn } from "../../lib/utils";
import { useChartLabel } from "./chartContext";
import { clamp, formatPercent, isFiniteNumber, stateColor, type ChartState } from "./chartMath";

export interface SuccessRingProps {
  /** 0..1, or null when there is nothing to measure yet. */
  rate: number | null | undefined;
  /** What the ring measures, e.g. "Success rate". Always rendered as text. */
  label: ReactNode;
  /** Centre text override. Defaults to the rate as a whole percent. */
  valueText?: string;
  /** Centre text when rate is null. Default "-". */
  nullText?: string;
  /** Colour of the filled arc. Default "success". Ignored (idle) when rate is null. */
  state?: ChartState;
  /** Words for what the colour means, e.g. "Healthy" / "Needs attention". */
  statusLabel?: ReactNode;
  /** Small icon shown before statusLabel (decorative; pass aria-hidden). */
  icon?: ReactNode;
  /** Ring diameter in px. Default 88. It shrinks to fit a narrower parent. */
  size?: number;
  /** Stroke thickness in viewBox units (ring is 100 wide). Default 10. */
  thickness?: number;
  /** Overrides the ChartFrame summary as the SVG's accessible name. */
  ariaLabel?: string;
  className?: string;
}

export default function SuccessRing({
  rate,
  label,
  valueText,
  nullText = "-",
  state = "success",
  statusLabel,
  icon,
  size = 88,
  thickness = 10,
  ariaLabel,
  className,
}: SuccessRingProps): ReactElement {
  const accessibleName = useChartLabel(ariaLabel);
  const hasRate = isFiniteNumber(rate);
  const fraction = hasRate ? clamp(rate as number, 0, 1) : 0;
  const stroke = clamp(thickness, 2, 40);
  const radius = 50 - stroke / 2;
  const circumference = 2 * Math.PI * radius;
  const centre = hasRate ? (valueText ?? formatPercent(fraction) ?? nullText) : nullText;
  const color = hasRate ? stateColor(state) : stateColor("idle");

  return (
    <div className={cn("flex min-w-0 items-center gap-3", className)} data-ring-state={hasRate ? "value" : "none"}>
      <div className="relative aspect-square max-w-full shrink-0" style={{ width: size }}>
        <svg role="img" aria-label={accessibleName} viewBox="0 0 100 100" className="block size-full -rotate-90 text-foreground">
          <circle
            cx="50"
            cy="50"
            r={radius}
            fill="none"
            stroke="currentColor"
            strokeOpacity={hasRate ? 0.12 : 0.3}
            strokeWidth={stroke}
            strokeDasharray={hasRate ? undefined : "4 5"}
          />
          {hasRate && fraction > 0 && (
            <circle
              data-ring-arc=""
              cx="50"
              cy="50"
              r={radius}
              fill="none"
              stroke={color}
              strokeWidth={stroke}
              strokeLinecap={fraction >= 1 ? "butt" : "round"}
              strokeDasharray={`${(fraction * circumference).toFixed(2)} ${circumference.toFixed(2)}`}
            />
          )}
        </svg>
        <span
          data-ring-value=""
          aria-hidden="true"
          className={cn(
            "absolute inset-0 flex items-center justify-center text-base font-semibold tabular-nums",
            hasRate ? "text-foreground" : "text-muted-foreground"
          )}
        >
          {centre}
        </span>
      </div>
      <div className="min-w-0 space-y-0.5">
        <div className="text-sm font-medium text-foreground">{label}</div>
        {statusLabel && (
          <div className="flex items-center gap-1 text-xs text-muted-foreground">
            {icon && (
              <span className="inline-flex shrink-0" style={{ color }}>
                {icon}
              </span>
            )}
            <span>{statusLabel}</span>
          </div>
        )}
      </div>
    </div>
  );
}
