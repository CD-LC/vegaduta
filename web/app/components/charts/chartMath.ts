// Pure math for the shared chart kit. No React, no DOM.
//
// Every helper here is total: empty arrays, a single point, all-zero series,
// NaN / Infinity and negative inputs all produce a finite, drawable number
// (or an explicit null where "there is no value" is the honest answer). A
// chart component must never have to guard against NaN in path data.

/** The six run/health states that have a `--state-*` colour token in globals.css. */
export type ChartState = "success" | "error" | "warning" | "running" | "queued" | "idle";

/** CSS colour for a state, e.g. `var(--state-success)`. Follows light/dark automatically. */
export function stateColor(state: ChartState | null | undefined): string {
  switch (state) {
    case "success":
    case "error":
    case "warning":
    case "running":
    case "queued":
      return `var(--state-${state})`;
    default:
      return "var(--state-idle)";
  }
}

/** True only for real, finite numbers (not NaN, not +/-Infinity, not strings). */
export function isFiniteNumber(value: unknown): value is number {
  return typeof value === "number" && Number.isFinite(value);
}

/** A magnitude that is safe to draw: non-finite and negative inputs become 0. */
export function toMagnitude(value: unknown): number {
  return isFiniteNumber(value) && value > 0 ? value : 0;
}

/** Clamp into [min, max]. A non-finite value collapses to `min`; swapped bounds are tolerated. */
export function clamp(value: number, min: number, max: number): number {
  const lo = Math.min(min, max);
  const hi = Math.max(min, max);
  if (!isFiniteNumber(value)) return lo;
  return Math.min(hi, Math.max(lo, value));
}

/** Sum of the finite entries. Empty, null or all-invalid input sums to 0. */
export function sum(values: ReadonlyArray<number | null | undefined> | null | undefined): number {
  let total = 0;
  for (const value of values || []) {
    if (isFiniteNumber(value)) total += value;
  }
  return total;
}

/**
 * Largest finite entry, or `fallback` when there is none. Unlike
 * `Math.max(...values)` this never returns -Infinity for an empty array, never
 * returns NaN, and does not overflow the call stack on a huge array.
 */
export function safeMax(values: ReadonlyArray<number | null | undefined> | null | undefined, fallback = 0): number {
  let max = -Infinity;
  for (const value of values || []) {
    if (isFiniteNumber(value) && value > max) max = value;
  }
  return max === -Infinity ? fallback : max;
}

/** Smallest finite entry, or `fallback` when there is none. */
export function safeMin(values: ReadonlyArray<number | null | undefined> | null | undefined, fallback = 0): number {
  let min = Infinity;
  for (const value of values || []) {
    if (isFiniteNumber(value) && value < min) min = value;
  }
  return min === Infinity ? fallback : min;
}

/**
 * Round a positive value UP to a "nice" axis maximum: 1, 2, 2.5, 5 or 10 times
 * a power of ten. 0, negatives and non-finite input return 0.
 */
export function niceCeil(value: number): number {
  if (!isFiniteNumber(value) || value <= 0) return 0;
  const exponent = Math.floor(Math.log10(value));
  const base = Math.pow(10, exponent);
  const fraction = value / base;
  let nice = 10;
  if (fraction <= 1) nice = 1;
  else if (fraction <= 2) nice = 2;
  else if (fraction <= 2.5) nice = 2.5;
  else if (fraction <= 5) nice = 5;
  const result = nice * base;
  return Number.isFinite(result) ? result : value;
}

/**
 * Evenly spaced ticks from 0 to a nice maximum, e.g. niceTicks(87, 4) ->
 * [0, 25, 50, 75, 100]. Returns [] when there is nothing to scale (max <= 0),
 * so a caller cannot draw an axis for an empty chart by accident.
 */
export function niceTicks(max: number, intervals = 4): number[] {
  const top = niceCeil(max);
  if (top <= 0) return [];
  const steps = Math.max(1, Math.floor(isFiniteNumber(intervals) ? intervals : 4));
  const ticks: number[] = [];
  for (let i = 0; i <= steps; i++) {
    // toPrecision trims binary float noise (0.30000000000000004).
    ticks.push(Number(((top * i) / steps).toPrecision(12)));
  }
  return ticks;
}

/**
 * Map `value` from [0, domainMax] onto [0, rangeMax], clamped. A zero,
 * negative or non-finite domain maps everything to 0 instead of dividing by
 * zero.
 */
export function scaleLinear(value: number, domainMax: number, rangeMax: number): number {
  if (!isFiniteNumber(domainMax) || domainMax <= 0 || !isFiniteNumber(rangeMax)) return 0;
  return clamp(toMagnitude(value) / domainMax, 0, 1) * rangeMax;
}

/**
 * Each entry's share of the total, 0..1. Negative / non-finite entries count
 * as 0. An empty or all-zero series returns all zeros (never NaN).
 */
export function shares(values: ReadonlyArray<number | null | undefined> | null | undefined): number[] {
  const magnitudes = (values || []).map(toMagnitude);
  const total = sum(magnitudes);
  if (total <= 0) return magnitudes.map(() => 0);
  return magnitudes.map((value) => value / total);
}

/**
 * part / whole as a 0..1 rate, or null when there is no honest answer: a
 * zero, negative, missing or non-finite denominator, or a non-finite
 * numerator. "0 of 0 runs succeeded" is not 0% and not 100%.
 */
export function rate(part: number | null | undefined, whole: number | null | undefined): number | null {
  if (!isFiniteNumber(part) || !isFiniteNumber(whole) || whole <= 0) return null;
  return clamp(part / whole, 0, 1);
}

/**
 * Format a 0..1 rate as "87%" (or "87.5%" with digits=1). Returns null - not
 * "NaN%", not "0%" - for a null / non-finite rate; the caller chooses the
 * placeholder, e.g. `formatPercent(r) ?? "-"`.
 */
export function formatPercent(value: number | null | undefined, digits = 0): string | null {
  if (!isFiniteNumber(value)) return null;
  const places = clamp(Math.floor(isFiniteNumber(digits) ? digits : 0), 0, 6);
  return `${(clamp(value, 0, 1) * 100).toFixed(places)}%`;
}

/**
 * Compact count for value labels: 950 -> "950", 1234 -> "1.2k", 2500000 ->
 * "2.5M". Returns null for a non-finite value so the caller picks the
 * placeholder.
 */
export function formatCompact(value: number | null | undefined): string | null {
  if (!isFiniteNumber(value)) return null;
  const abs = Math.abs(value);
  const units: Array<[number, string]> = [
    [1e12, "T"],
    [1e9, "B"],
    [1e6, "M"],
    [1e3, "k"],
  ];
  for (const [size, suffix] of units) {
    if (abs >= size) {
      const scaled = value / size;
      const text = Math.abs(scaled) >= 100 ? scaled.toFixed(0) : scaled.toFixed(1).replace(/\.0$/, "");
      return `${text}${suffix}`;
    }
  }
  return Number.isInteger(value) ? String(value) : value.toFixed(1).replace(/\.0$/, "");
}

/**
 * Polyline points for a sparkline in a `width` x `height` box, min-max
 * normalised with a 1px bottom / 3px top inset (the look ax/Sparkline has
 * always had). Non-finite entries are dropped. Returns "" for no drawable
 * points and a flat mid-height line for a single point, so the result is
 * always safe to hand to <polyline points>.
 */
export function sparklinePoints(values: ReadonlyArray<number | null | undefined> | null | undefined, width: number, height: number): string {
  const finite = (values || []).filter(isFiniteNumber);
  const w = toMagnitude(width);
  const h = toMagnitude(height);
  if (finite.length === 0 || w === 0 || h === 0) return "";
  if (finite.length === 1) {
    const y = (h / 2).toFixed(1);
    return `0.0,${y} ${w.toFixed(1)},${y}`;
  }
  const max = safeMax(finite);
  const min = safeMin(finite);
  const span = max - min || 1;
  const n = finite.length;
  return finite
    .map((value, i) => {
      const x = (i / (n - 1)) * w;
      const y = h - ((value - min) / span) * (h - 4) - 1;
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    })
    .join(" ");
}
