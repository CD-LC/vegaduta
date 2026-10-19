// Shared display formatters (FE-016). These replace per-component copies that
// had drifted apart (base 1000 vs 1024, rounding, floor units, labels). Each
// former copy's exact output is kept by passing options at the call site - see
// format.test.ts, which pins every call-site preset.

export type ByteUnit = "B" | "KB" | "MB" | "GB";

const BYTE_UNITS: readonly ByteUnit[] = ["B", "KB", "MB", "GB"];

export interface FormatBytesOptions {
  /** 1024 (binary, default) or 1000 (decimal) bytes per step. */
  base?: 1000 | 1024;
  /** Smallest unit shown. Values below it are shown in it, never below 1 (e.g. 200 B -> "1 KB"). Default "B". */
  minUnit?: ByteUnit;
  /** Largest unit shown (e.g. "MB" renders 5 GB as "5120.0 MB"). Default "GB". */
  maxUnit?: ByteUnit;
  /** Decimal places per unit, or a function of the scaled value. Default: B/KB/MB 0, GB 1. */
  decimals?: Partial<Record<ByteUnit, number | ((scaled: number) => number)>>;
  /** Returned for 0 bytes instead of formatting it. */
  zero?: string;
  /** Returned for a non-number, NaN, Infinity or negative input. Default "0 B". */
  invalid?: string;
}

const DEFAULT_DECIMALS: Record<ByteUnit, number> = { B: 0, KB: 0, MB: 0, GB: 1 };

/**
 * Human-readable byte size, e.g. formatBytes(1536) === "2 KB".
 *
 * Defaults (binary base, integer B/KB/MB, one decimal GB) match the former
 * edge console / EdgeSetupChip copies; other call sites pass options.
 */
export function formatBytes(bytes: number, options: FormatBytesOptions = {}): string {
  const { base = 1024, minUnit = "B", maxUnit = "GB", zero, invalid = "0 B" } = options;
  if (typeof bytes !== "number" || !Number.isFinite(bytes) || bytes < 0) return invalid;
  if (bytes === 0 && zero !== undefined) return zero;

  const lo = BYTE_UNITS.indexOf(minUnit);
  const hi = BYTE_UNITS.indexOf(maxUnit);
  let idx = lo;
  for (let i = hi; i > lo; i--) {
    if (bytes >= Math.pow(base, i)) {
      idx = i;
      break;
    }
  }
  const unit = BYTE_UNITS[idx];
  const scaled = bytes / Math.pow(base, idx);
  const rule = options.decimals?.[unit] ?? DEFAULT_DECIMALS[unit];
  const places = typeof rule === "function" ? rule(scaled) : rule;
  let value: string;
  if (places > 0) {
    value = scaled.toFixed(places);
  } else {
    const rounded = Math.round(scaled);
    // A value below the smallest shown unit never reads as "0 KB".
    value = String(idx === lo && lo > 0 ? Math.max(1, rounded) : rounded);
  }
  return `${value} ${unit}`;
}

export interface TimeAgoLabels {
  justNow: string;
  minutes: (n: number) => string;
  hours: (n: number) => string;
  /** Required unless maxDays is 0. */
  days?: (n: number) => string;
  /** Older than maxDays. Default: the date's toLocaleDateString(). */
  date?: (d: Date) => string;
  /** null / undefined / empty input. Default "". */
  none?: string;
  /** An unparsable date. Default: `none`. */
  invalid?: string;
}

export interface TimeAgoOptions {
  labels?: TimeAgoLabels;
  /**
   * How elapsed time becomes whole units:
   * - "floor": truncate (1h59m -> 1 hour)
   * - "round": round each unit from the rounded seconds
   * - "chained" (default): round seconds -> minutes -> hours -> days in turn
   */
  rounding?: "floor" | "round" | "chained";
  /** Ages of this many days or more show `labels.date`. Default 30; 0 = never show days. */
  maxDays?: number;
  /** "Now" in epoch ms (tests). */
  now?: number;
}

/** Compact English, e.g. "5m ago" - the former NotificationBell copy. */
export const COMPACT_TIME_AGO_LABELS: TimeAgoLabels = {
  justNow: "just now",
  minutes: (n) => `${n}m ago`,
  hours: (n) => `${n}h ago`,
  days: (n) => `${n}d ago`,
};

/**
 * Relative age of a timestamp ("just now", "5m ago", ...). Future times read
 * as "just now". Accepts an ISO string, epoch ms or a Date.
 */
export function timeAgo(input: string | number | Date | null | undefined, options: TimeAgoOptions = {}): string {
  const { labels = COMPACT_TIME_AGO_LABELS, rounding = "chained", maxDays = 30, now = Date.now() } = options;
  const none = labels.none ?? "";
  if (input === null || input === undefined || input === "") return none;
  const then = input instanceof Date ? input.getTime() : new Date(input).getTime();
  if (Number.isNaN(then)) return labels.invalid ?? none;

  const elapsed = Math.max(0, (now - then) / 1000);
  let sec: number;
  let min: number;
  let hr: number;
  let day: number;
  if (rounding === "floor") {
    sec = Math.floor(elapsed);
    min = Math.floor(elapsed / 60);
    hr = Math.floor(elapsed / 3600);
    day = Math.floor(elapsed / 86400);
  } else if (rounding === "round") {
    sec = Math.round(elapsed);
    min = Math.round(sec / 60);
    hr = Math.round(sec / 3600);
    day = Math.round(sec / 86400);
  } else {
    sec = Math.round(elapsed);
    min = Math.round(sec / 60);
    hr = Math.round(min / 60);
    day = Math.round(hr / 24);
  }

  const chained = rounding === "chained";
  if (sec < 60) return labels.justNow;
  if (chained ? min < 60 : sec < 3600) return labels.minutes(min);
  if (chained ? hr < 24 : sec < 86400) return labels.hours(hr);
  if (day < maxDays && labels.days) return labels.days(day);
  return labels.date ? labels.date(new Date(then)) : new Date(then).toLocaleDateString();
}

/** Absolute local date + time, or "" for a missing value. */
export function formatDateTime(input: string | number | Date | null | undefined): string {
  if (input === null || input === undefined || input === "") return "";
  return new Date(input).toLocaleString();
}
