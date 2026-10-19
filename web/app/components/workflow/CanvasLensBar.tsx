"use client";

import { useCallback, useRef, type CSSProperties, type KeyboardEvent } from "react";
import { EyeOff, Flame, TriangleAlert, Route, Ghost, type LucideIcon } from "lucide-react";
import { CANVAS_LENSES, RUNS_WINDOWS, type CanvasLens, type LensLegend, type RunsWindow } from "./lensStyles";
import "./canvas-lens.css";

const LENS_META: Record<CanvasLens, { label: string; hint: string; Icon: LucideIcon }> = {
  none: { label: "Design", hint: "No lens: the canvas as designed", Icon: EyeOff },
  heat: { label: "Heat", hint: "Heat: average duration per node", Icon: Flame },
  failures: { label: "Failures", hint: "Failures: how often each node errors", Icon: TriangleAlert },
  critical: { label: "Critical path", hint: "Critical path: the chain that set this run's duration", Icon: Route },
  coverage: { label: "Coverage", hint: "Coverage: nodes that never ran (dead branches)", Icon: Ghost },
};

// Eight samples are enough for the eye to read a continuous ramp.
const RAMP_STEPS = Array.from({ length: 8 }, (_, i) => i / 7);

export interface CanvasLensBarProps {
  lens: CanvasLens;
  onLensChange: (lens: CanvasLens) => void;
  runsWindow: RunsWindow;
  onRunsWindowChange: (n: RunsWindow) => void;
  /** From legend(lens, stats, ...) in lensStyles.ts. */
  legend: LensLegend;
  /** When set, every lens but "none" is disabled and this text explains why
   * (e.g. "Run the workflow once to unlock lenses"). */
  disabledReason?: string;
  /** Per-lens reasons, e.g. { critical: "Select a run first" }. */
  lensDisabledReasons?: Partial<Record<CanvasLens, string>>;
  /** Runs actually loaded; shown as "12 of 20" when the window is not full. */
  loadedRuns?: number;
  className?: string;
}

/** Roving-tabindex radio group: one Tab stop, arrows move and select. */
function useRovingRadio<T>(items: readonly T[], value: T, onChange: (v: T) => void, isDisabled: (v: T) => boolean) {
  const refs = useRef<(HTMLButtonElement | null)[]>([]);
  const onKeyDown = useCallback(
    (e: KeyboardEvent<HTMLElement>) => {
      const dir = e.key === "ArrowRight" || e.key === "ArrowDown" ? 1 : e.key === "ArrowLeft" || e.key === "ArrowUp" ? -1 : 0;
      const jump = e.key === "Home" ? "first" : e.key === "End" ? "last" : null;
      if (!dir && !jump) return;
      e.preventDefault();
      const enabled = items.map((v, i) => ({ v, i })).filter(({ v }) => !isDisabled(v));
      if (enabled.length === 0) return;
      let next: { v: T; i: number };
      if (jump === "first") next = enabled[0];
      else if (jump === "last") next = enabled[enabled.length - 1];
      else {
        const at = Math.max(0, enabled.findIndex(({ v }) => v === value));
        next = enabled[(at + dir + enabled.length) % enabled.length];
      }
      onChange(next.v);
      refs.current[next.i]?.focus();
    },
    [items, value, onChange, isDisabled],
  );
  return { refs, onKeyDown };
}

export default function CanvasLensBar({
  lens,
  onLensChange,
  runsWindow,
  onRunsWindowChange,
  legend,
  disabledReason,
  lensDisabledReasons,
  loadedRuns,
  className,
}: CanvasLensBarProps) {
  const lensReason = useCallback(
    (l: CanvasLens): string | undefined => (l === "none" ? undefined : disabledReason ?? lensDisabledReasons?.[l]),
    [disabledReason, lensDisabledReasons],
  );
  const lensGroup = useRovingRadio(CANVAS_LENSES, lens, onLensChange, (l) => !!lensReason(l));
  const winDisabled = useCallback(() => !!disabledReason, [disabledReason]);
  const winGroup = useRovingRadio(RUNS_WINDOWS, runsWindow, onRunsWindowChange, winDisabled);

  const showLegend = lens !== "none" || !!disabledReason;
  const partial = loadedRuns !== undefined && loadedRuns < runsWindow;

  return (
    <div className={["wfx-lensbar", className].filter(Boolean).join(" ")} data-lens={lens}>
      <div className="wfx-lensbar-row">
        {/* eslint-disable-next-line jsx-a11y/interactive-supports-focus -- roving tabindex lives on the radios; arrow keys bubble to the group */}
        <div className="wfx-lensbar-seg" role="radiogroup" aria-label="Canvas lens" onKeyDown={lensGroup.onKeyDown}>
          {CANVAS_LENSES.map((l, i) => {
            const { label, hint, Icon } = LENS_META[l];
            const checked = l === lens;
            const reason = lensReason(l);
            return (
              <button
                key={l}
                ref={(el) => { lensGroup.refs.current[i] = el; }}
                type="button"
                role="radio"
                aria-checked={checked}
                aria-label={reason ? `${label} (${reason})` : label}
                title={reason ?? hint}
                tabIndex={checked ? 0 : -1}
                disabled={!!reason}
                data-lens={l}
                className="wfx-lensbar-btn"
                onClick={() => onLensChange(l)}
              >
                <Icon className="wfx-lensbar-ico" aria-hidden="true" />
                <span className="wfx-lensbar-label">{label}</span>
              </button>
            );
          })}
        </div>

        {lens !== "none" && lens !== "critical" && (
          <>
            <span className="wfx-lensbar-sep" aria-hidden="true" />
            {/* eslint-disable-next-line jsx-a11y/interactive-supports-focus -- roving tabindex lives on the radios; arrow keys bubble to the group */}
            <div
              className="wfx-lensbar-window"
              role="radiogroup"
              aria-label="Runs to analyse"
              onKeyDown={winGroup.onKeyDown}
            >
              <span className="wfx-lensbar-window-label" aria-hidden="true">Last</span>
              {RUNS_WINDOWS.map((n, i) => {
                const checked = n === runsWindow;
                return (
                  <button
                    key={n}
                    ref={(el) => { winGroup.refs.current[i] = el; }}
                    type="button"
                    role="radio"
                    aria-checked={checked}
                    aria-label={`Last ${n} runs`}
                    title={`Last ${n} runs`}
                    tabIndex={checked ? 0 : -1}
                    disabled={!!disabledReason}
                    className="wfx-lensbar-win"
                    onClick={() => onRunsWindowChange(n)}
                  >
                    {n}
                  </button>
                );
              })}
            </div>
          </>
        )}
      </div>

      {showLegend && (
        // key restarts the fade-in so switching lenses reads as a new answer.
        <div key={legend.lens} className="wfx-lensbar-legend" role="status" aria-live="polite" aria-atomic="true">
          {disabledReason ? (
            <span className="wfx-lensbar-note">{disabledReason}</span>
          ) : (
            <>
              <span className="wfx-lensbar-q">{legend.question}</span>
              <LegendStops legend={legend} />
              {legend.headline && <span className="wfx-lensbar-headline">{legend.headline}</span>}
              {partial && lens !== "critical" && (
                <span className="wfx-lensbar-note">
                  {loadedRuns === 0 ? "No runs yet" : `${loadedRuns} of ${runsWindow} runs available`}
                </span>
              )}
            </>
          )}
        </div>
      )}
    </div>
  );
}

function LegendStops({ legend }: { legend: LensLegend }) {
  if (legend.kind === "none" || legend.stops.length === 0) return null;
  if (legend.kind === "ramp") {
    const [lo, hi] = [legend.stops[0], legend.stops[legend.stops.length - 1]];
    return (
      <span className="wfx-lensbar-stops">
        <span>{lo.label}</span>
        <span className="wfx-lensbar-ramp" data-lens={legend.lens} aria-hidden="true">
          {RAMP_STEPS.map((i) => (
            <span key={i} style={{ "--wfx-lens-i": i.toFixed(3) } as CSSProperties} />
          ))}
        </span>
        <span>{hi.label}</span>
      </span>
    );
  }
  return (
    <span className="wfx-lensbar-stops">
      {legend.stops.map((s) => (
        <span key={s.label} className="wfx-lensbar-stop">
          <span className={`wfx-lens-swatch ${s.swatchClass}`} aria-hidden="true" />
          {s.label}
        </span>
      ))}
    </span>
  );
}
