"use client";

import { useId, type CSSProperties } from "react";

/**
 * The temperature/top-p/max-tokens sliders from Agents.dc.html's "MODEL &
 * GENERATION" card - a real <input type="range"> (see globals.css's
 * .ax-slider rule for the thin gradient-track/glow-thumb styling), not a
 * decorative div, so drag/keyboard/touch all work natively. The CSS
 * variable --ax-slider-fill drives how much of the track is painted before
 * the thumb, recomputed here on every value change since CSS can't derive
 * "percent between min and max" on its own.
 *
 * `React.CSSProperties` has no index signature for arbitrary custom
 * properties (`--foo`), so the inline style below needs a cast - this file
 * is the first `.tsx` in the console to set a CSS custom property inline
 * (every other call site doing this, e.g. ThemePicker.js's swatch colors,
 * is a plain `.js` file that TypeScript never checks).
 */
type SliderStyle = CSSProperties & { "--ax-slider-fill"?: string };

interface HudSliderProps {
  label: string;
  value: number;
  // Pre-formatted counterpart of `value` for the readout span - a number when
  // the caller shows the raw value, a string when it formats (toFixed) or
  // substitutes a placeholder ("—" while the tuning draft hasn't loaded yet;
  // see AgentWorkbench's maxTokens breakpoint).
  displayValue: string | number;
  min: number;
  max: number;
  step: number;
  onChange: (value: number) => void;
  disabled?: boolean;
}

export default function HudSlider({ label, value, displayValue, min, max, step, onChange, disabled }: HudSliderProps) {
  const id = useId();
  const pct = max > min ? ((value - min) / (max - min)) * 100 : 0;
  return (
    <div className={disabled ? "opacity-60" : undefined}>
      <div className="mb-2 flex items-baseline justify-between">
        <label htmlFor={id} className="text-xs font-medium uppercase tracking-wider text-muted-foreground">{label}</label>
        <span className="text-sm font-semibold tabular-nums text-foreground">{displayValue}</span>
      </div>
      {/* Real <input type="range"> — the drag/keyboard/touch range logic is
       * native; only the head labels above were restyled to tokens. The thin
       * gradient track + glow thumb still come from globals.css's .ax-slider
       * rule (driven by the --ax-slider-fill custom property computed here). */}
      <input
        type="range"
        id={id}
        className="ax-slider"
        min={min}
        max={max}
        step={step}
        value={value}
        disabled={disabled}
        onChange={(e) => onChange(Number(e.target.value))}
        aria-valuetext={String(displayValue)}
        style={{ "--ax-slider-fill": `${pct}%` } as SliderStyle}
      />
    </div>
  );
}
