"use client";

// Horizontal ranked bars ("top tools", "busiest agents"): label, value text
// and a bar scaled to the largest value. Built from HTML, not SVG, so labels
// wrap / truncate naturally down to 320px and the value text stays real text.
// Rendered as an ordered list - rank is the point. With `cap`, the rest is
// folded into an "and N more" line instead of silently dropped.
import type { ReactElement } from "react";
import { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";
import { useChartLabel } from "./chartContext";
import { formatCompact, safeMax, toMagnitude } from "./chartMath";

export interface RankedBarItem {
  /** Stable React key; defaults to the label. */
  key?: string;
  label: string;
  value: number;
  /** Pre-formatted value ("1,204 calls"). Defaults to a compact number. */
  valueText?: string;
  /** Per-row bar colour (CSS colour / token). */
  color?: string;
}

export interface RankedBarsProps {
  items: RankedBarItem[];
  /** Show at most N rows, then an "and N more" line. */
  cap?: number;
  /** Sort largest first. Default true; pass false if the caller already ranked them. */
  sort?: boolean;
  /**
   * The value a full-width bar stands for. Default: the largest item, which is
   * right for counts and durations. Pass 1 for 0..1 rates, so a 33% failure
   * rate is drawn a third wide instead of full width.
   */
  scaleMax?: number;
  /** Default bar colour. Default: the theme primary. */
  color?: string;
  /** Overrides the built-in "and {count} more" line (already translated). */
  moreLabel?: (count: number) => string;
  /** Overrides the ChartFrame summary as the list's accessible name. */
  ariaLabel?: string;
  className?: string;
}

/** Renders nothing for an empty list - use ChartFrame's empty state. */
export default function RankedBars({
  items,
  cap,
  sort = true,
  scaleMax,
  color = "var(--primary)",
  moreLabel,
  ariaLabel,
  className,
}: RankedBarsProps): ReactElement | null {
  const t = useTranslations("charts");
  const label = useChartLabel(ariaLabel);

  const all = (Array.isArray(items) ? items : []).map((item) => ({ ...item, value: toMagnitude(item.value) }));
  if (all.length === 0) return null;
  const ranked = sort ? [...all].sort((a, b) => b.value - a.value) : all;
  const limit = typeof cap === "number" && Number.isFinite(cap) && cap > 0 ? Math.floor(cap) : ranked.length;
  const shown = ranked.slice(0, limit);
  const hidden = ranked.length - shown.length;
  const largest = safeMax(ranked.map((item) => item.value));
  const max =
    typeof scaleMax === "number" && Number.isFinite(scaleMax) && scaleMax > 0 ? Math.max(scaleMax, largest) : largest;

  return (
    <div className={cn("min-w-0", className)}>
      <ol aria-label={label} className="m-0 list-none space-y-2 p-0">
        {shown.map((item) => {
          const percent = max > 0 ? (item.value / max) * 100 : 0;
          return (
            <li key={item.key ?? item.label} className="min-w-0">
              {/* flex-wrap: on a narrow card a long label keeps its full width
                  and the value drops to its own line instead of squeezing the
                  label down to a few characters. */}
              <div className="flex flex-wrap items-baseline justify-between gap-x-3 text-sm">
                <span className="min-w-0 max-w-full truncate text-foreground" title={item.label}>
                  {item.label}
                </span>
                <span className="ms-auto shrink-0 text-xs font-medium tabular-nums text-muted-foreground">
                  {item.valueText ?? formatCompact(item.value) ?? "0"}
                </span>
              </div>
              <div aria-hidden="true" className="mt-1 h-1.5 w-full overflow-hidden rounded-full bg-muted">
                <div
                  data-ranked-bar=""
                  className="h-full rounded-full"
                  style={{ width: `${percent.toFixed(2)}%`, background: item.color ?? color }}
                />
              </div>
            </li>
          );
        })}
      </ol>
      {hidden > 0 && (
        <p data-ranked-more="" className="mt-2 text-xs text-muted-foreground">
          {moreLabel ? moreLabel(hidden) : t("andMore", { count: hidden })}
        </p>
      )}
    </div>
  );
}
