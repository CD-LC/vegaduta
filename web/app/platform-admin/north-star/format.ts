import type { NorthStarResponse, WeekPoint } from "../lib/adminApi/northStar";

// Pure helpers for the North Star screen. No React, no DOM: everything the
// page shows that involves a decision (hidden vs unavailable, which week is
// the headline week, where a chart line breaks) lives here so it is tested.

/** The server's privacy floor, repeated here only for the label text. */
export const MIN_CONTRIBUTORS = 5;

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

export const HIDDEN_LABEL = `Hidden (<${MIN_CONTRIBUTORS} users)`;

export function fmtCount(v: number | null | undefined): string {
  return v == null || !Number.isFinite(v) ? "n/a" : Math.round(v).toLocaleString("en-US");
}

/** 0.8734 -> "87.3%". */
export function fmtPct(v: number | null | undefined): string {
  return v == null || !Number.isFinite(v) ? "n/a" : `${(v * 100).toFixed(1)}%`;
}

/** Minutes as the unit a human reads: 12.5 min, 3.2 h, 2.1 d. */
export function fmtMinutes(v: number | null | undefined): string {
  if (v == null || !Number.isFinite(v)) return "n/a";
  if (v < 60) return `${v.toFixed(1)} min`;
  if (v < 60 * 24) return `${(v / 60).toFixed(1)} h`;
  return `${(v / (60 * 24)).toFixed(1)} d`;
}

/**
 * One table/tile cell. A null value is either HIDDEN (the privacy floor, when
 * its point is flagged suppressed) or not applicable / not computable ("n/a",
 * e.g. a success rate over zero runs). The two must never read the same: a
 * hidden cell is not zero and an undefined one is not hidden.
 */
export function cellText(
  value: number | null | undefined,
  suppressed: boolean,
  fmt: (n: number) => string = fmtCount
): string {
  if (value == null || !Number.isFinite(value)) return suppressed ? HIDDEN_LABEL : "n/a";
  return fmt(value);
}

/** "2026-09-28" -> "28 Sep". Parsed by hand: `new Date(iso)` would shift by timezone. */
export function weekLabel(iso: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso ?? "");
  if (!m) return iso ?? "";
  return `${Number(m[3])} ${MONTHS[Number(m[2]) - 1] ?? m[2]}`;
}

/**
 * The week the headline tiles describe: the newest COMPLETE week, because the
 * current one is partial and would read as a collapse every Monday. Falls
 * back to the newest week when only a partial one exists.
 */
export function latestCompleteWeek(weeks: WeekPoint[]): WeekPoint | null {
  for (let i = weeks.length - 1; i >= 0; i--) {
    if (!weeks[i].partial) return weeks[i];
  }
  return weeks.length ? weeks[weeks.length - 1] : null;
}

/** The week before `week` in the list, or null. */
export function weekBefore(weeks: WeekPoint[], week: WeekPoint | null): WeekPoint | null {
  if (!week) return null;
  const i = weeks.indexOf(week);
  return i > 0 ? weeks[i - 1] : null;
}

/** Sum of the published values; `hidden` counts the entries withheld, so a total is never silently low. */
export function sumKnown(values: (number | null | undefined)[]): { total: number; known: number; hidden: number } {
  let total = 0;
  let known = 0;
  let hidden = 0;
  for (const v of values) {
    if (v == null || !Number.isFinite(v)) {
      hidden++;
    } else {
      total += v;
      known++;
    }
  }
  return { total, known, hidden };
}

/** True when the window holds any recorded activity at all (suppressed weeks count: something happened). */
export function hasAnyActivity(data: NorthStarResponse | null): boolean {
  if (!data) return false;
  return data.weeks.some((w) => w.suppressed || (w.runs ?? 0) > 0 || (w.wau ?? 0) > 0);
}

export interface ChartPoint {
  index: number;
  /** Slot centre. */
  x: number;
  /** Top of the bar / the line's y. */
  y: number;
  /** Bar height in px (0 for a hidden point). */
  h: number;
  value: number | null;
}

export interface ChartGeometry {
  max: number;
  slot: number;
  points: ChartPoint[];
}

export interface ChartOptions {
  width: number;
  height: number;
  padTop: number;
  padBottom: number;
  fixedMax?: number;
}

/**
 * Pixel geometry for a mini chart. A null value yields a point with
 * `value: null`, which the renderer draws as a gap (line) or a hollow
 * placeholder (bar) - never as a zero-height bar. `fixedMax` pins the axis
 * (1 for a rate) so two weeks' rates stay comparable; otherwise the axis is
 * the largest published value, never below 1.
 */
export function chartGeometry(values: (number | null)[], opts: ChartOptions): ChartGeometry {
  const { width, height, padTop, padBottom, fixedMax } = opts;
  const published = values.filter((v): v is number => v != null && Number.isFinite(v));
  const max = fixedMax ?? Math.max(1, ...published);
  const slot = values.length ? width / values.length : width;
  const plotH = Math.max(1, height - padTop - padBottom);
  const points: ChartPoint[] = values.map((v, index) => {
    const value = v != null && Number.isFinite(v) ? v : null;
    const h = value == null ? 0 : Math.max(0, Math.min(1, value / max)) * plotH;
    return { index, x: slot * index + slot / 2, y: padTop + (plotH - h), h, value };
  });
  return { max, slot, points };
}

/** SVG path for a line that breaks at every hidden point rather than interpolating across it. */
export function linePath(points: ChartPoint[]): string {
  let d = "";
  let pen = false;
  for (const p of points) {
    if (p.value == null) {
      pen = false;
      continue;
    }
    d += `${pen ? "L" : "M"}${p.x.toFixed(1)} ${p.y.toFixed(1)} `;
    pen = true;
  }
  return d.trim();
}
