"use client";

import type { ReactNode } from "react";
import { AlertTriangle, Shuffle } from "lucide-react";
import { Badge } from "../../components/ui/badge";
import { cn } from "../../lib/utils";
import {
  formatEstimate,
  formatSigned,
  formatValue,
  hasInterval,
  isUnreplicated,
  orEmptyEstimate,
  type IntervalEstimate,
} from "./evalStatisticsApi";
import { useTranslations } from "../../lib/i18n";

/**
 * The presentational atoms for honest scores — the pieces that make the rule
 * in evalStatisticsApi.ts's header visible on screen.
 *
 * There is exactly one reason this is a separate file from
 * EvalStatisticsSection.tsx: these are the components any OTHER quality
 * surface needs in order to stop rendering bare numbers. AgentQualityPanel
 * (web/app/components/agents/, owned by concurrent work in flight) renders
 * six per-dimension scores and a trend as plain percentages today; when its
 * owner wires the reliability endpoint, `<ScoreWithInterval>` is the drop-in
 * and nothing here needs to change. Keeping the atoms importable is the whole
 * point — a second hand-rolled interval renderer elsewhere in the console is
 * how the two drift apart.
 *
 * Design notes that are not arbitrary:
 *  - Numbers are bare 2dp decimals (`0.62`), not percentages. The interval
 *    notation `0.62 [0.48–0.75]` is the form the literature and the build
 *    agenda both use, and `62% [48%–75%]` is three extra glyphs per figure in
 *    a table that already has four numeric columns.
 *  - `tabular-nums` + `font-mono` on every figure, because these are meant to
 *    be compared down a column.
 *  - The unreplicated badge is a WARNING hue, not an error hue. An n=1 score
 *    is not wrong; it is unsupported. Colour is never the only cue (design
 *    contract §2) — the badge carries the word "unreplicated" and an icon.
 *
 * i18n NOTE (cross-workstream): strings are literal English on purpose, same
 * as EvalDatasetsSection.tsx in this folder. web/messages/en.json is actively
 * edited by concurrent sessions and a t() miss renders the raw dotted key
 * path, which is worse than English. Moving these under a namespace once
 * en.json is free is part of the recorded follow-up.
 */

// ---------------------------------------------------------------------------
// Badges
// ---------------------------------------------------------------------------

/**
 * The n=1 marker. Rendered wherever a score has one or zero independent
 * rollouts behind it, so a single-run number can never be mistaken for a
 * measurement on any screen that uses these atoms.
 */
export function UnreplicatedBadge({ className }: { className?: string }) {
  const tHC = useTranslations("hcAgentsQualityScoreWithInterval");
  return (
    <Badge variant="state" state="var(--state-warning)" className={cn("gap-1", className)} title={tHC("s5")}>
      <AlertTriangle size={11} aria-hidden="true" /> unreplicated
    </Badge>
  );
}

/**
 * Marks a case whose rollouts disagreed with each other. Distinct from
 * "failed": the agent both passed and failed the same case, so its verdict
 * depends on the sampling, not on the agent. This is usually the single most
 * actionable row on the screen.
 */
export function FlakyBadge({ passed, rollouts, className }: { passed: number; rollouts: number; className?: string }) {
  return (
    <Badge
      variant="state"
      state="var(--state-warning)"
      className={cn("gap-1", className)}
      title={`This case passed ${passed} of ${rollouts} rollouts — its outcome is not stable, so neither pass nor fail is the honest summary.`}
    >
      <Shuffle size={11} aria-hidden="true" /> flaky {passed}/{rollouts}
    </Badge>
  );
}

// ---------------------------------------------------------------------------
// The score renderer
// ---------------------------------------------------------------------------

export interface ScoreWithIntervalProps {
  estimate: IntervalEstimate | null | undefined;
  /** Optional label rendered above the figure (table cells usually omit it). */
  label?: ReactNode;
  /** `lg` for headline figures, `sm` for dense table cells. */
  size?: "sm" | "md" | "lg";
  /** Render the 0..1 interval bar under the figure. Off in tables by default:
   *  twelve bars in a column is decoration, not information. */
  showBar?: boolean;
  /** Suppress the unreplicated badge — only for places that already show one
   *  badge for the whole block (e.g. a run header that is entirely n=1). */
  hideUnreplicatedBadge?: boolean;
  className?: string;
}

const SIZE_CLASS: Record<NonNullable<ScoreWithIntervalProps["size"]>, string> = {
  sm: "text-sm",
  md: "text-base",
  lg: "text-2xl",
};

/**
 * A score rendered as a measurement: `0.62 [0.48–0.75], n=5`.
 *
 * Every degradation path is explicit, because the no-fiction rule bites
 * hardest here:
 *  - no value at all  -> em dash, plus "not measured"; never 0.00.
 *  - value, no interval -> the figure and "no interval" in words. A
 *    zero-width bracket would read as infinite precision.
 *  - value at n<=1 -> the figure plus the unreplicated badge.
 */
export function ScoreWithInterval({
  estimate,
  label,
  size = "md",
  showBar = false,
  hideUnreplicatedBadge = false,
  className,
}: ScoreWithIntervalProps) {
  const tHC = useTranslations("hcAgentsQualityScoreWithInterval");
  const e = orEmptyEstimate(estimate);
  const hasValue = e.value !== null && e.value !== undefined;
  const withInterval = hasInterval(e);
  const unreplicated = isUnreplicated(e);

  return (
    <div className={cn("space-y-1", className)}>
      {label && <div className="text-xs font-medium text-muted-foreground">{label}</div>}
      <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
        <span
          className={cn(
            "font-mono font-semibold tabular-nums",
            SIZE_CLASS[size],
            hasValue ? "text-foreground" : "text-muted-foreground"
          )}
        >
          {formatValue(e.value)}
        </span>
        {withInterval && (
          <span
            className="font-mono text-xs tabular-nums text-muted-foreground"
            title={
              e.confidenceLevel
                ? `${Math.round(e.confidenceLevel * 100)}% interval${e.method ? ` (${methodLabel(e.method)})` : ""}`
                : e.method
                  ? `Interval method: ${methodLabel(e.method)}`
                  : undefined
            }
          >
            [{formatValue(e.ciLow)}–{formatValue(e.ciHigh)}]
          </span>
        )}
        {(e.rollouts || 0) > 0 && (
          <span className="font-mono text-xs tabular-nums text-muted-foreground" title={tHC("s4")}>
            n={e.rollouts}
          </span>
        )}
        {!hasValue && <span className="text-xs text-muted-foreground">{tHC("s3")}</span>}
        {hasValue && !withInterval && !unreplicated && (
          <span className="text-xs text-muted-foreground" title={tHC("s2")}>
            {tHC("s1")}
          </span>
        )}
        {unreplicated && !hideUnreplicatedBadge && <UnreplicatedBadge />}
      </div>
      {showBar && hasValue && <IntervalBar estimate={e} />}
    </div>
  );
}

// IntervalMethod enum names, serialised verbatim by the backend — labelled
// so a reader can tell which estimator produced the interval, because a
// Wilson on 8/10 and a percentile bootstrap on 10 scores can print the same
// bounds while meaning different things.
function methodLabel(method: string): string {
  if (method === "WILSON_SCORE") return "Wilson score interval";
  if (method === "PERCENTILE_BOOTSTRAP") return "percentile bootstrap";
  if (method === "HIERARCHICAL_BOOTSTRAP") return "hierarchical bootstrap (cases, then rollouts within case)";
  if (method === "PAIRED_BOOTSTRAP") return "paired bootstrap over case pairs";
  return method;
}

// ---------------------------------------------------------------------------
// Interval bars
// ---------------------------------------------------------------------------

/**
 * A 0..1 track with the interval drawn as a band and the point estimate as a
 * tick. Replaces the single filled progress bar the older quality cards use:
 * a filled bar communicates "this much", which is precisely the false
 * precision this screen exists to remove.
 *
 * Deliberately CSS-only (no SVG, no charting dependency) — same approach as
 * AgentQualityPanel's inline trend and AgentLearningPanel's RewardTrend.
 */
export function IntervalBar({
  estimate,
  className,
}: {
  estimate: IntervalEstimate | null | undefined;
  className?: string;
}) {
  const e = orEmptyEstimate(estimate);
  if (e.value === null || e.value === undefined) return null;

  const clamp = (v: number) => Math.max(0, Math.min(1, v));
  const point = clamp(Number(e.value));
  const lo = hasInterval(e) ? clamp(Number(e.ciLow)) : point;
  const hi = hasInterval(e) ? clamp(Number(e.ciHigh)) : point;
  // A degenerate band (no interval, or lo==hi) still gets a visible sliver so
  // the bar does not silently look like a band of zero width that the reader
  // might mistake for precision; the accompanying text says "no interval".
  const width = Math.max(hi - lo, 0.008);

  return (
    <div
      className={cn("relative h-2 w-full overflow-hidden rounded-full bg-secondary/40", className)}
      role="img"
      aria-label={`Estimate ${formatEstimate(e)} on a 0 to 1 scale`}
      title={formatEstimate(e)}
    >
      {hasInterval(e) && (
        <div
          className="absolute inset-y-0 rounded-full bg-primary/30"
          style={{ left: `${lo * 100}%`, width: `${width * 100}%` }}
        />
      )}
      <div
        className="absolute inset-y-0 w-[2px] rounded-full bg-primary"
        style={{ left: `calc(${point * 100}% - 1px)` }}
      />
    </div>
  );
}

/**
 * The signed version, for a paired A/B gap: zero is the centre line, the
 * tenant's noise floor is drawn as a shaded band around it, and the gap's
 * interval is the bar. When the bar overlaps the shaded band or the centre
 * line, the reader can SEE why the verdict is "cannot tell" — the visual and
 * the verdict text are driven off the same two numbers.
 *
 * `noiseFloor` null means the floor has not been measured; no band is drawn
 * and the caller says so in words. Drawing a zero-width band would imply a
 * measured floor of zero, which is the assumption this whole screen exists to
 * refuse.
 */
export function GapIntervalBar({
  estimate,
  noiseFloor,
  className,
}: {
  estimate: IntervalEstimate | null | undefined;
  noiseFloor: number | null;
  className?: string;
}) {
  const e = orEmptyEstimate(estimate);
  if (e.value === null || e.value === undefined) return null;

  const floor =
    noiseFloor !== null && noiseFloor !== undefined && Number.isFinite(Number(noiseFloor))
      ? Math.abs(Number(noiseFloor))
      : null;
  // Symmetric domain around zero, sized by the largest thing we must show, so
  // the centre line is always the true zero and two bars on one screen are
  // not drawn to different scales by accident.
  const candidates = [Math.abs(Number(e.value))];
  if (hasInterval(e)) candidates.push(Math.abs(Number(e.ciLow)), Math.abs(Number(e.ciHigh)));
  if (floor !== null) candidates.push(floor);
  const domain = Math.max(...candidates) * 1.25 || 0.05;

  // Map a signed value to a 0..100% position with zero at the centre.
  const posOf = (v: number) => ((v + domain) / (2 * domain)) * 100;

  const lo = hasInterval(e) ? posOf(Number(e.ciLow)) : posOf(Number(e.value));
  const hi = hasInterval(e) ? posOf(Number(e.ciHigh)) : posOf(Number(e.value));
  const barLeft = Math.min(lo, hi);
  const barWidth = Math.max(Math.abs(hi - lo), 0.8);
  const point = posOf(Number(e.value));

  return (
    <div className={cn("space-y-1", className)}>
      <div
        className="relative h-3 w-full rounded-full bg-secondary/40"
        role="img"
        aria-label={
          `Paired gap ${formatSigned(e.value)}` +
          (hasInterval(e) ? `, interval ${formatSigned(e.ciLow)} to ${formatSigned(e.ciHigh)}` : "") +
          (floor !== null ? `, noise floor plus or minus ${formatValue(floor)}` : ", noise floor not measured")
        }
      >
        {floor !== null && (
          <div
            className="absolute inset-y-0 bg-[color-mix(in_srgb,var(--state-warning)_26%,transparent)]"
            style={{ left: `${posOf(-floor)}%`, width: `${posOf(floor) - posOf(-floor)}%` }}
            title={`Noise floor: gaps within ±${formatValue(floor)} are indistinguishable from run-to-run variance for this tenant.`}
          />
        )}
        {/* True zero — "no difference". */}
        <div className="absolute inset-y-[-2px] left-1/2 w-[1px] -translate-x-1/2 bg-border" />
        <div
          className="absolute inset-y-[3px] rounded-full bg-primary/40"
          style={{ left: `${barLeft}%`, width: `${barWidth}%` }}
        />
        <div className="absolute inset-y-0 w-[2px] rounded-full bg-primary" style={{ left: `calc(${point}% - 1px)` }} />
      </div>
      <div className="flex justify-between font-mono text-[0.65rem] tabular-nums text-muted-foreground">
        <span>{formatSigned(-domain)}</span>
        <span>0</span>
        <span>{formatSigned(domain)}</span>
      </div>
    </div>
  );
}
